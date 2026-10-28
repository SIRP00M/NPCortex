package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Stage 4 NATIVE world-effect pipeline (timed block break, native item pickup), pure Java: no Minecraft types, so every
 * ordering rule is unit-testable with scripted worlds and fault injection. Minecraft/Forge objects stay behind {@link Env}.
 *
 * Preview (server-owned target, volatile nonce) -> Confirm -> backend RESERVE (durable intent + actor fence + resource claim)
 * -> [break: timed mining, volatile] -> backend START (durable EXECUTION_STARTED marker, ACK required)
 * -> [ONE server-thread task: final identity/policy/ownership/physical checks, native call in a try/catch, observation]
 * -> typed receipt -> backend FINALIZE (terminal + claim release) or HOLD (uncertain: fence and claim kept).
 *
 * Invariants (pinned by NativeEffectFlowSelfTest):
 *  - the native executor needs a {@link Started} token that only {@link #startAck} mints from a validated backend reply of
 *    THIS attempt, while the attempt still awaits it and has not been aborted: no confirmed marker, no native call;
 *  - nothing asynchronous sits between the final checks and the native call, nor between the call and the observation;
 *  - the requester is a parameter taken from the authenticated packet sender; the client never supplies coordinates;
 *  - a critical message (START, FINALIZE, HOLD, CANCEL) is never refused for lack of queue room: capacity is reserved per
 *    attempt (a completion slot) at admission, the message lives in an in-memory registry until the backend ACKs it, and a
 *    retry re-sends IDENTICAL bytes only;
 *  - success is reported only for a matching, backend-committed terminal; a lost ACK is UNKNOWN, never success;
 *  - any throwable around the native call, or evidence that disagrees, is HOLD_IN_DOUBT; nothing is retried by re-executing.
 * It claims no exactly-once physical guarantee and nothing here is atomic with chunk, entity or inventory storage.
 */
final class NativeEffectFlow {
    static final String CONTRACT = "native-effects-v1";
    private static final String BASE = "/v1/cplus/wn/";
    static final String BIND = BASE + "bind", SAVED = BASE + "saved", RESERVE = BASE + "reserve", START = BASE + "start",
            FINALIZE = BASE + "finalize", CANCEL = BASE + "cancel", HOLD = BASE + "hold", REPAIR = BASE + "repair",
            RECONCILE = BASE + "reconcile", STATUS = BASE + "status", ACTIVE = BASE + "active";

    /** Completion slots: an accepted attempt owns one until its last critical message is acknowledged. Provisional (G5 measures). */
    static final int SLOTS = 16;
    static final int PREVIEW_TTL_TICKS = 1200;
    static final int START_WAIT_TICKS = 100;
    static final int RETRY_BACKOFF_TICKS = 20;
    static final int MAX_TRIES = 6;
    static final int START_TRIES = 3;
    static final int POLICY_RECHECK_TICKS = 20;

    enum Kind { OK, DENIED, REJECTED, BUSY, ERROR, UNKNOWN }

    /** {@code nonce} is non-empty only for an accepted preview; {@code state} names the ledger state when one is known. */
    record Result(Kind kind, String message, String state, String nonce) {
        Result(Kind kind, String message, String state) { this(kind, message, state, ""); }
    }

    enum Action {
        BREAK("break_block"), PICKUP("pickup_item");
        final String wire;
        Action(String wire) { this.wire = wire; }
    }

    /** Server-resolved identities. {@code incarnation} is the live avatar's entity id: a respawned actor is a different incarnation. */
    record Actor(String name, UUID world, UUID actorId, UUID profileId, String dimension, UUID session, int incarnation) {}

    record BlockRef(String dimension, int x, int y, int z, String blockId, String stateSha) {}

    record ItemRef(String dimension, UUID entity, String itemKey, String nbtSha, int count, double x, double y, double z,
                   int preCount) {}

    record Pick<T>(T ref, String reason, String text) {
        static <T> Pick<T> no(String reason) { return new Pick<>(null, reason, ""); }
    }

    record MineStep(float perTick, String tool, String problem) {}

    record Drop(UUID uuid, String item, int count) {}

    enum Journal { NOT_REQUIRED, COMMITTED, FAILED }

    record BreakOutcome(boolean threw, boolean destroyReturned, String before, String after, String toolBefore, String toolAfter,
                        boolean toolBroke, List<Drop> drops, boolean collateral, Journal journal, Object evidence) {}

    record PickupOutcome(boolean threw, int entityBefore, int entityAfter, int delta, int events, int preCount, int postCount,
                         Journal journal, boolean evidenceComplete, Object evidence) {}

    record PickupObservation(String journal, String entity) {}

    enum Repair { DONE, REPLAY_PERSISTED, REFUSED }

    /** Proof that the backend stored the EXECUTION_STARTED marker for exactly this attempt. Cannot be built elsewhere. */
    static final class Started {
        final UUID task;
        private Started(UUID task) { this.task = task; }
    }

    /** Raised by the binding when the backend ANSWERED with a refusal (HTTP 4xx): no ledger change can be assumed. */
    static final class BackendRefusal extends RuntimeException {
        BackendRefusal(String message) { super(message); }
    }

    interface Env {
        /** Always a fresh server-thread task (never inline). */
        void onServerThread(Runnable task);
        /** Worker-thread POST. {@code critical} messages use the reserved lane and are not refused for queue room. */
        CompletableFuture<JsonObject> post(String route, JsonObject body, boolean critical);
        Actor resolve(String npcName);
        /** Null when no other subsystem owns this actor, else why. */
        String ownershipBlock(Actor actor);
        /** The newest policy snapshot. File I/O never runs on the server thread, so even {@code fresh} is the poller's last
         * snapshot: it re-reads every 1 s (a withdrawal can be honoured for about one poll interval) and the whole policy turns
         * deny-all when no read succeeded for 4 s (that is the poller-dead cutoff, not the normal revocation window). */
        NativeActionPolicy policy(boolean fresh);
        boolean online(UUID player);
        long tick();
        UUID newId();
        void notifyRequester(UUID requester, String text);
        // ---- binding (world folder + epoch) ----
        JsonObject bindingRequest(Actor actor);
        void bindingAdvanced(long epoch);
        // ---- break ----
        Pick<BlockRef> pickBlock(UUID requester, Actor actor);
        /** Null = fine. Reach, line of sight, loaded chunk, no block entity, harvestable, mineable, survival mode. */
        String breakCheck(Actor actor, BlockRef ref);
        String blockState(BlockRef ref);
        MineStep mineStep(Actor actor, BlockRef ref);
        void crack(Actor actor, BlockRef ref, int stage);
        BreakOutcome executeBreak(Started marker, Actor actor, BlockRef ref, UUID attempt);
        // ---- pickup ----
        Pick<ItemRef> pickItem(UUID requester, Actor actor);
        /** Null = fine: entity present, same item, count, pickup delay, ownership of the stack, proximity geometry. */
        String pickupCheck(Actor actor, ItemRef ref);
        PickupOutcome executePickup(Started marker, Actor actor, ItemRef ref, UUID attempt);
        // ---- inventory journal ----
        String journalPrecheck(Actor actor, UUID attempt, String fingerprint);
        Repair repair(Actor actor, UUID attempt, String fingerprint, Object evidence);
        // ---- reconcile observations (read-only) ----
        String observeBlock(String dimension, int x, int y, int z);
        PickupObservation observePickup(String dimension, UUID entity, String itemKey, int count, double x, double y, double z,
                                        UUID profile, UUID attempt, String fingerprint);
    }

    private enum Phase { RESERVING, MINING, AWAITING_START, SETTLING, CANCELLING, UNCONFIRMED, DONE }

    private final class Msg {
        final String route;
        final JsonObject body;
        final Consumer<JsonObject> onAck;
        final Consumer<String> onRefused;
        final Runnable onGiveUp;
        final int maxTries;
        int tries;
        long nextTick;
        boolean inLane;

        Msg(String route, JsonObject body, int maxTries, Consumer<JsonObject> onAck, Consumer<String> onRefused, Runnable onGiveUp) {
            this.route = route; this.body = body; this.maxTries = maxTries;
            this.onAck = onAck; this.onRefused = onRefused; this.onGiveUp = onGiveUp;
        }
    }

    private final class Attempt {
        final UUID task, request, attemptId, requester;
        final Action action;
        final Actor actor;
        final BlockRef block;
        final ItemRef item;
        final JsonObject reserve;
        final String fingerprint;
        Phase phase = Phase.RESERVING;
        MiningProgress mining = new MiningProgress();
        int lastStage = -2;
        boolean abort;
        String abortReason = "ABORTED";
        String abortText = "";
        boolean cancelOperator;
        Msg msg;
        long startRequestedTick;
        long startRequestedNanos;
        boolean holdAcked;
        JsonObject startBody;
        boolean markerAcked;
        long lastPolicyTick;
        JsonObject receipt;          // the terminal evidence this attempt sent (kept for hash matching)
        JsonObject intended;         // INVENTORY_COMMIT_FAILED: the receipt that a persist-only repair would record
        Object evidenceToken;
        String holdReason = "";
        boolean slot = true;

        Attempt(UUID requester, Action action, Actor actor, BlockRef block, ItemRef item) {
            this.task = env.newId(); this.request = env.newId(); this.attemptId = env.newId();
            this.requester = requester; this.action = action; this.actor = actor; this.block = block; this.item = item;
            this.fingerprint = (action == Action.BREAK ? "native-break|" : "native-pickup|") + attemptId;
            this.reserve = new JsonObject();
        }
    }

    private record Preview(String nonce, Action action, Actor actor, BlockRef block, ItemRef item, long expires) {}

    private final Env env;
    private final Map<UUID, Attempt> pending = new LinkedHashMap<>();      // actor -> live attempt; server thread only
    private final Map<UUID, Attempt> held = new LinkedHashMap<>();         // actor -> HOLD / unconfirmed attempt (no slot)
    private final Map<String, Preview> previews = new HashMap<>();
    private UUID boundSession;
    private String bindProblem;
    private long bindRetryTick;
    private boolean binding;
    private int slotsUsed;

    NativeEffectFlow(Env env) { this.env = env; }

    boolean pending(UUID actorId) { return pending.containsKey(actorId) || held.containsKey(actorId); }

    boolean pendingProfile(UUID profileId) {
        for (Attempt a : pending.values()) if (a.actor.profileId().equals(profileId)) return true;
        for (Attempt a : held.values()) if (a.actor.profileId().equals(profileId)) return true;
        return false;
    }

    int slotsUsed() { return slotsUsed; }

    int outstanding() {
        int n = 0;
        for (Attempt a : pending.values()) if (a.msg != null) n++;
        return n;
    }

    boolean bound(UUID session) { return session != null && session.equals(boundSession); }

    UUID boundSessionOrNull() { return boundSession; }

    // ------------------------------------------------------------------------------------------------ binding
    /** Server thread. Starts the handshake when needed; completes with null on success or the refusal text. */
    private CompletableFuture<String> ensureBound(Actor actor) {
        if (bound(actor.session())) return CompletableFuture.completedFuture(null);
        if (binding) return CompletableFuture.completedFuture("Binding with the backend is in progress; try again in a moment.");
        if (bindProblem != null && env.tick() < bindRetryTick) return CompletableFuture.completedFuture(bindProblem);
        binding = true;
        JsonObject request = env.bindingRequest(actor);
        CompletableFuture<String> out = new CompletableFuture<>();
        CompletableFuture<JsonObject> reply;
        try {
            reply = env.post(BIND, request, false);
        } catch (RuntimeException e) {
            binding = false;
            return CompletableFuture.completedFuture("Backend transport busy; try again.");
        }
        reply.whenComplete((r, error) -> env.onServerThread(() -> {
            binding = false;
            String problem = null;
            if (error != null) problem = "Native effects refused: " + describe(error);
            else if (!"BOUND".equals(str(r, "state")) || !request.get("world_id").equals(r.get("world_id"))
                    || !request.get("session_id").equals(r.get("session_id")) || !hasCapability(r)
                    || !r.has("epoch") || !r.get("epoch").isJsonPrimitive())
                problem = "Native effects refused: the backend reply did not confirm this world binding.";
            if (problem == null) {
                try {
                    env.bindingAdvanced(r.get("epoch").getAsLong());
                } catch (RuntimeException e) {
                    problem = "Native effects refused: the world binding could not be stored.";
                }
            }
            if (problem == null) {
                boundSession = actor.session();
                bindProblem = null;
            } else {
                bindProblem = problem;
                bindRetryTick = env.tick() + 100;
            }
            out.complete(problem);
        }));
        return out;
    }

    private static boolean hasCapability(JsonObject r) {
        JsonElement caps = r.get("capabilities");
        if (caps == null || !caps.isJsonArray()) return false;
        for (JsonElement c : caps.getAsJsonArray()) if (c.isJsonPrimitive() && CONTRACT.equals(c.getAsString())) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ preview
    private static String previewKey(UUID requester, String npc) { return requester + "|" + npc.toLowerCase(Locale.ROOT); }

    /** Server thread. Chooses the target on the SERVER, takes no claim and writes nothing. */
    CompletableFuture<Result> preview(UUID requester, String npcName, Action action) {
        try {
            return CompletableFuture.completedFuture(previewNow(requester, npcName, action));
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(new Result(Kind.ERROR, "Internal error; nothing was written.", ""));
        }
    }

    private Result previewNow(UUID requester, String npcName, Action action) {
        if (requester == null) return new Result(Kind.DENIED, "Unknown requester.", "");
        Actor actor = env.resolve(npcName);
        if (actor == null) return new Result(Kind.REJECTED, "That NPC is not a live, alive registered actor.", "");
        NativeActionPolicy policy = env.policy(true);
        if (!policy.loaded()) return new Result(Kind.DENIED, "Denied: " + policy.problem(), "");
        if (!bound(actor.session())) {
            if (binding) return new Result(Kind.BUSY, "Binding with the backend is in progress; try again in a moment.", "");
            if (bindProblem != null && env.tick() < bindRetryTick) return new Result(Kind.REJECTED, bindProblem, "");
            ensureBound(actor).thenAccept(problem -> { });           // start it now; the next click finds it done
            return new Result(Kind.BUSY, "Checking the world binding with the backend; try again in a moment.", "");
        }
        if (pending(actor.actorId())) return new Result(Kind.BUSY, "A world action for this NPC is still running or unresolved.", "");
        String block = env.ownershipBlock(actor);
        if (block != null) return new Result(Kind.REJECTED, "NPC busy: " + block, "");
        if (slotsUsed >= SLOTS) return new Result(Kind.BUSY, "Too many native actions are in flight; wait for one to finish.", "");
        Preview preview;
        String text;
        if (action == Action.BREAK) {
            Pick<BlockRef> pick = env.pickBlock(requester, actor);
            if (pick.ref() == null) return new Result(Kind.REJECTED, pick.reason(), "");
            BlockRef ref = pick.ref();
            NativeActionPolicy.Decision d = policy.decideBreak(requester, ref.dimension(), actor.name(), ref.blockId(), ref.x(), ref.y(), ref.z());
            if (!d.allow()) return new Result(Kind.DENIED, "Denied: " + d.reason(), "");
            String why = env.breakCheck(actor, ref);
            if (why != null) return new Result(Kind.REJECTED, "Cannot break that block: " + why, "");
            preview = new Preview(env.newId().toString().replace("-", ""), action, actor, ref, null, env.tick() + PREVIEW_TTL_TICKS);
            text = pick.text();
        } else {
            Pick<ItemRef> pick = env.pickItem(requester, actor);
            if (pick.ref() == null) return new Result(Kind.REJECTED, pick.reason(), "");
            ItemRef ref = pick.ref();
            NativeActionPolicy.Decision d = policy.decidePickup(requester, ref.dimension(), actor.name(), ref.itemKey(),
                    (int) Math.floor(ref.x()), (int) Math.floor(ref.y()), (int) Math.floor(ref.z()));
            if (!d.allow()) return new Result(Kind.DENIED, "Denied: " + d.reason(), "");
            String why = env.pickupCheck(actor, ref);
            if (why != null) return new Result(Kind.REJECTED, "Cannot pick that up: " + why, "");
            preview = new Preview(env.newId().toString().replace("-", ""), action, actor, null, ref, env.tick() + PREVIEW_TTL_TICKS);
            text = pick.text();
        }
        previews.put(previewKey(requester, npcName), preview);       // a new preview replaces the old one
        return new Result(Kind.OK, "Preview: " + text + " Confirm to run it; the outcome is reported in chat.", "PREVIEW", preview.nonce());
    }

    /** Operator cancel: discards a preview, or aborts an attempt that has not crossed the durable marker. */
    Result cancel(UUID requester, String npcName) {
        if (requester == null) return new Result(Kind.DENIED, "Unknown requester.", "");
        Preview dropped = previews.remove(previewKey(requester, npcName));
        Actor actor = env.resolve(npcName);
        Attempt a = actor == null ? null : pending.get(actor.actorId());
        if (a != null && a.requester.equals(requester)) {
            if (a.phase == Phase.MINING || a.phase == Phase.AWAITING_START || a.phase == Phase.RESERVING) {
                a.cancelOperator = true;
                abort(a, "OPERATOR", "Cancelled by the operator.");
                return new Result(Kind.OK, "Cancel requested: no effect will be produced if the marker was not yet confirmed.", "CANCELLING");
            }
            return new Result(Kind.REJECTED, "Too late to cancel: the native call is being settled; the outcome is reported in chat.", "");
        }
        return dropped != null ? new Result(Kind.OK, "Preview discarded.", "")
                : new Result(Kind.REJECTED, "Nothing to cancel.", "");
    }

    // ------------------------------------------------------------------------------------------------ confirm
    CompletableFuture<Result> confirm(UUID requester, String npcName, String nonce) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        try {
            confirmNow(requester, npcName, nonce, out);
        } catch (RuntimeException e) {
            if (!out.isDone()) out.complete(new Result(Kind.ERROR, "Internal error before the reservation reply; use Rec. if the NPC stays busy.", ""));
        }
        return out;
    }

    private void confirmNow(UUID requester, String npcName, String nonce, CompletableFuture<Result> out) {
        if (requester == null) { out.complete(new Result(Kind.DENIED, "Unknown requester.", "")); return; }
        Preview pv = previews.remove(previewKey(requester, npcName));      // one use, whatever happens next
        if (pv == null || nonce == null || !pv.nonce().equals(nonce)) { out.complete(new Result(Kind.REJECTED, "No matching preview: preview again.", "")); return; }
        if (env.tick() > pv.expires()) { out.complete(new Result(Kind.REJECTED, "The preview expired: preview again.", "")); return; }
        Actor actor = env.resolve(npcName);
        if (actor == null) { out.complete(new Result(Kind.REJECTED, "That NPC is not a live, alive registered actor.", "")); return; }
        if (!actor.equals(pv.actor())) { out.complete(new Result(Kind.REJECTED, "The NPC changed since the preview: preview again.", "")); return; }
        if (!bound(actor.session())) { out.complete(new Result(Kind.REJECTED, "Native effects are not bound to the backend for this session.", "")); return; }
        NativeActionPolicy policy = env.policy(true);
        NativeActionPolicy.Decision d;
        String why;
        if (pv.action() == Action.BREAK) {
            BlockRef r = pv.block();
            d = policy.decideBreak(requester, r.dimension(), actor.name(), r.blockId(), r.x(), r.y(), r.z());
            why = d.allow() ? env.breakCheck(actor, r) : null;
            if (why == null && d.allow() && !r.stateSha().equals(env.blockState(r))) why = "the block changed since the preview";
        } else {
            ItemRef r = pv.item();
            d = policy.decidePickup(requester, r.dimension(), actor.name(), r.itemKey(), (int) Math.floor(r.x()), (int) Math.floor(r.y()), (int) Math.floor(r.z()));
            why = d.allow() ? env.pickupCheck(actor, r) : null;
        }
        if (!d.allow()) { out.complete(new Result(Kind.DENIED, "Denied: " + d.reason(), "")); return; }
        if (why != null) { out.complete(new Result(Kind.REJECTED, "Cannot proceed: " + why, "")); return; }
        if (pending(actor.actorId())) { out.complete(new Result(Kind.BUSY, "A world action for this NPC is still running or unresolved.", "")); return; }
        String block = env.ownershipBlock(actor);
        if (block != null) { out.complete(new Result(Kind.REJECTED, "NPC busy: " + block, "")); return; }
        if (slotsUsed >= SLOTS) { out.complete(new Result(Kind.BUSY, "Too many native actions are in flight; wait for one to finish.", "")); return; }
        Attempt a = new Attempt(requester, pv.action(), actor, pv.block(), pv.item());
        String journal = env.journalPrecheck(actor, a.attemptId, a.fingerprint);
        if (journal != null) { out.complete(new Result(Kind.REJECTED, "Inventory journal unavailable: " + journal, "")); return; }
        buildReserve(a, d);
        slotsUsed++;
        pending.put(actor.actorId(), a);
        CompletableFuture<JsonObject> reserved;
        try {
            reserved = env.post(RESERVE, a.reserve, false);
        } catch (RuntimeException e) {
            release(a);
            pending.remove(actor.actorId(), a);
            out.complete(new Result(Kind.BUSY, "Backend transport busy; nothing was sent.", ""));
            return;
        }
        reserved.whenComplete((reply, error) -> env.onServerThread(() -> {
            try {
                afterReserve(a, reply, error, out);
            } catch (RuntimeException e) {
                holdLocal(a, "UNEXPECTED_STATE", "Internal error after the reservation; ledger state UNKNOWN. Use Rec.");
                if (!out.isDone()) out.complete(new Result(Kind.UNKNOWN, "Internal error after the reservation; ledger state UNKNOWN. Use Rec.", ""));
            }
        }));
    }

    private void buildReserve(Attempt a, NativeActionPolicy.Decision d) {
        JsonObject r = a.reserve;
        r.addProperty("schema_version", 1);
        r.addProperty("task_id", a.task.toString());
        r.addProperty("request_id", a.request.toString());
        r.addProperty("attempt_id", a.attemptId.toString());
        r.addProperty("world_id", a.actor.world().toString());
        r.addProperty("actor_id", a.actor.actorId().toString());
        r.addProperty("profile_id", a.actor.profileId().toString());
        r.addProperty("session_id", a.actor.session().toString());
        r.addProperty("requester_id", a.requester.toString());
        r.addProperty("dimension_id", a.actor.dimension());
        r.addProperty("action_kind", a.action.wire);
        r.addProperty("origin", "MANAGER_PACKET");
        JsonObject auth = new JsonObject();
        auth.addProperty("decision", "ALLOW");
        auth.addProperty("entry_id", d.entryId());
        auth.addProperty("policy_sha256", d.policySha256());
        r.add("authorization", auth);
        JsonObject t = new JsonObject();
        if (a.action == Action.BREAK) {
            t.addProperty("kind", "block");
            t.addProperty("x", a.block.x()); t.addProperty("y", a.block.y()); t.addProperty("z", a.block.z());
            t.addProperty("block_id", a.block.blockId());
            t.addProperty("state_sha256", a.block.stateSha());
        } else {
            t.addProperty("kind", "item");
            t.addProperty("entity_uuid", a.item.entity().toString());
            t.addProperty("item_key", a.item.itemKey());
            t.addProperty("nbt_sha256", a.item.nbtSha());
            t.addProperty("count", a.item.count());
            t.addProperty("x", a.item.x()); t.addProperty("y", a.item.y()); t.addProperty("z", a.item.z());
            t.addProperty("pre_count", a.item.preCount());
        }
        r.add("target", t);
    }

    private void afterReserve(Attempt a, JsonObject reply, Throwable error, CompletableFuture<Result> out) {
        if (error != null || !echoes(reply, a.reserve, "RESERVED")) {
            if (refused(error)) {                                  // the backend answered NO: nothing was written
                pending.remove(a.actor.actorId(), a);
                release(a);
                out.complete(new Result(Kind.REJECTED, "Backend refused the reservation: " + describe(error), ""));
                return;
            }
            // Unknown whether a RESERVED row exists. Compensate with a cancel (RESERVED -> CANCELLED_NO_EFFECT); never execute.
            a.phase = Phase.CANCELLING;
            out.complete(new Result(Kind.UNKNOWN, "Reservation reply unusable (" + (error == null ? "unexpected reply: " + clip(String.valueOf(reply)) : describe(error)) + "); a cancel was queued and nothing was executed.", ""));
            sendCancel(a, "OPERATOR", "Reservation reply unusable; cancelled without effect.");
            return;
        }
        if (a.abort) {                                             // cancelled while the reserve was in flight
            out.complete(new Result(Kind.REJECTED, "Cancelled before it started.", "CANCELLED_NO_EFFECT"));
            settleNoEffect(a);
            return;
        }
        if (!env.online(a.requester)) {
            out.complete(new Result(Kind.REJECTED, "Requester left before execution; cancelling without effect.", ""));
            a.abortReason = "REQUESTER_GONE"; a.abortText = "Requester left before execution.";
            sendCancel(a, "REQUESTER_GONE", "Requester left before execution.");
            return;
        }
        out.complete(new Result(Kind.OK, a.action == Action.BREAK
                ? "Accepted: the NPC starts mining. The game must be running (not paused). The outcome is reported in chat."
                : "Accepted: picking up. The outcome is reported in chat.", "RESERVED"));
        a.lastPolicyTick = env.tick();
        if (a.action == Action.BREAK) a.phase = Phase.MINING;
        else requestStart(a);
    }

    // ------------------------------------------------------------------------------------------------ tick
    /** Server thread, once per game tick. */
    void tick() {
        long now = env.tick();
        for (Attempt a : new ArrayList<>(pending.values())) {
            try {
                Msg m = a.msg;
                if (m != null && !m.inLane && now >= m.nextTick) pump(a);
                if (a.phase == Phase.MINING) mine(a, now);
                else if (a.phase == Phase.AWAITING_START && !a.abort && now - a.startRequestedTick > START_WAIT_TICKS)
                    abort(a, "START_ACK_LOST", "The start marker was not confirmed in time" + startTiming(a, now) + ".");
            } catch (RuntimeException e) {
                abort(a, "ABORTED", "Internal error while mining.");
            }
        }
        previews.values().removeIf(p -> now > p.expires());
    }

    private void mine(Attempt a, long now) {
        if (a.abort) return;
        Actor cur = env.resolve(a.actor.name());
        if (cur == null || !cur.equals(a.actor)) { abort(a, "ACTOR_CHANGED", "The NPC was despawned, respawned or changed."); return; }
        if (!env.online(a.requester)) { abort(a, "REQUESTER_GONE", "The requester left."); return; }
        if (now - a.lastPolicyTick >= POLICY_RECHECK_TICKS) {
            a.lastPolicyTick = now;
            NativeActionPolicy.Decision d = env.policy(false).decideBreak(a.requester, a.block.dimension(), a.actor.name(),
                    a.block.blockId(), a.block.x(), a.block.y(), a.block.z());
            if (!d.allow()) { abort(a, "PERMISSION_WITHDRAWN", "Permission was withdrawn: " + d.reason()); return; }
        }
        if (!a.block.stateSha().equals(env.blockState(a.block))) { abort(a, "TARGET_CHANGED", "The block changed while mining."); return; }
        String why = env.breakCheck(a.actor, a.block);
        if (why != null) { abort(a, why.startsWith("out of reach") || why.startsWith("no line") ? "OUT_OF_REACH" : "PRECONDITION_FAILED", why); return; }
        MineStep step = env.mineStep(a.actor, a.block);
        if (step.problem() != null) { abort(a, "TOOL_CHANGED", step.problem()); return; }
        a.mining.advance(now, step.tool(), step.perTick());
        if (a.mining.stage() != a.lastStage) { a.lastStage = a.mining.stage(); env.crack(a.actor, a.block, a.lastStage); }
        if (!a.mining.withinBound()) { abort(a, "STALLED", "Mining did not complete in a reasonable time."); return; }
        if (a.mining.ready()) requestStart(a);
    }

    // ------------------------------------------------------------------------------------------------ START
    private void requestStart(Attempt a) {
        a.phase = Phase.AWAITING_START;
        a.startRequestedTick = env.tick();
        a.startRequestedNanos = System.nanoTime();
        JsonObject start = identity(a.reserve);
        a.startBody = start;
        send(a, START, start, START_TRIES, reply -> onStartReply(a, reply),
                text -> failBeforeEffect(a, "PRECONDITION_FAILED", "Backend refused the start: " + text),
                () -> failBeforeEffect(a, "START_ACK_LOST", "The start marker was not confirmed."));
    }

    /** The only factory of {@link Started}: the backend must echo the exact attempt identity in state EXECUTING. */
    static Started startAck(JsonObject reply, JsonObject sent, UUID task) {
        if (!echoes(reply, sent, "EXECUTING") || !reply.has("execution_started")
                || !reply.get("execution_started").isJsonPrimitive() || !reply.get("execution_started").getAsBoolean())
            return null;
        return new Started(task);
    }

    private void onStartReply(Attempt a, JsonObject reply) {
        if (a.phase != Phase.AWAITING_START) return;                    // a stale reply never dispatches
        Started marker = startAck(reply, a.startBody, a.task);
        a.markerAcked = marker != null;
        if (marker == null) { failBeforeEffect(a, "START_ACK_LOST", "The start marker was not confirmed (reply did not match)."); return; }
        if (a.abort || env.tick() - a.startRequestedTick > START_WAIT_TICKS) {
            failBeforeEffect(a, a.abort ? a.abortReason : "START_ACK_LOST", a.abort ? a.abortText : "The start marker arrived too late" + startTiming(a, env.tick()) + ".");
            return;
        }
        // ---- from here to the observation there is no asynchronous hop: this is one server-thread task ----
        a.phase = Phase.SETTLING;
        boolean entered = false;
        try {
            clearCrack(a);
            String why = finalPrecondition(a);
            if (why != null) { failBeforeEffect(a, reasonOf(why), "Final precondition failed: " + stripReason(why) + "; nothing was executed."); return; }
            entered = true;                                          // from here the native call may have happened
            if (a.action == Action.BREAK) finishBreak(a, marker);
            else finishPickup(a, marker);
        } catch (RuntimeException e) {
            if (a.phase == Phase.DONE || a.phase == Phase.UNCONFIRMED || a.msg != null) return;   // a FINALIZE/HOLD is already owed
            if (!entered) failBeforeEffect(a, "PRECONDITION_FAILED", "Internal error before the native call; nothing was executed.");
            else hold(a, "UNEXPECTED_STATE", null, "Internal error around the native call; the world may have changed.");
        }
    }

    /** Diagnostics for START_ACK_LOST: the deadline is in game ticks, so also report how much wall time they were. */
    private String startTiming(Attempt a, long nowTick) {
        return " (" + (nowTick - a.startRequestedTick) + " ticks, " + (System.nanoTime() - a.startRequestedNanos) / 1_000_000L + " ms since the request)";
    }

    private String finalPrecondition(Attempt a) {
        Actor now = env.resolve(a.actor.name());
        if (now == null) return "ACTOR_CHANGED:NPC is no longer live";
        if (!now.equals(a.actor)) return "ACTOR_CHANGED:actor, world, dimension, session or incarnation changed";
        if (!bound(now.session())) return "BINDING_LOST:the world binding is no longer verified";
        if (!env.online(a.requester)) return "REQUESTER_GONE:requester left";
        NativeActionPolicy policy = env.policy(true);
        NativeActionPolicy.Decision d = a.action == Action.BREAK
                ? policy.decideBreak(a.requester, a.block.dimension(), now.name(), a.block.blockId(), a.block.x(), a.block.y(), a.block.z())
                : policy.decidePickup(a.requester, a.item.dimension(), now.name(), a.item.itemKey(),
                (int) Math.floor(a.item.x()), (int) Math.floor(a.item.y()), (int) Math.floor(a.item.z()));
        if (!d.allow()) return "PERMISSION_WITHDRAWN:" + d.reason();
        String block = env.ownershipBlock(now);
        if (block != null) return "PRECONDITION_FAILED:NPC busy: " + block;
        String journal = env.journalPrecheck(now, a.attemptId, a.fingerprint);
        if (journal != null) return "JOURNAL_UNAVAILABLE:" + journal;
        if (a.action == Action.BREAK) {
            if (!a.mining.ready()) return "PRECONDITION_FAILED:mining progress is not complete";
            if (!a.block.stateSha().equals(env.blockState(a.block))) return "TARGET_CHANGED:the block changed";
            String why = env.breakCheck(now, a.block);
            if (why != null) return (why.startsWith("out of reach") || why.startsWith("no line") ? "OUT_OF_REACH:" : "PRECONDITION_FAILED:") + why;
            MineStep step = env.mineStep(now, a.block);
            if (step.problem() != null) return "TOOL_CHANGED:" + step.problem();
            if (!step.tool().equals(a.mining.tool())) return "TOOL_CHANGED:the held tool changed";
            return null;
        }
        String why = env.pickupCheck(now, a.item);
        return why == null ? null : (why.startsWith("gone") ? "ENTITY_GONE:" : "NOT_ELIGIBLE:") + why;
    }

    private static String reasonOf(String why) { int i = why.indexOf(':'); return i < 0 ? "PRECONDITION_FAILED" : why.substring(0, i); }

    private static String stripReason(String why) { int i = why.indexOf(':'); return i < 0 ? why : why.substring(i + 1); }

    // ------------------------------------------------------------------------------------------------ the native call
    private void finishBreak(Attempt a, Started marker) {
        BreakOutcome o = env.executeBreak(marker, a.actor, a.block, a.attemptId);
        a.evidenceToken = o.evidence();
        long tick = env.tick();
        if (o.threw()) { hold(a, "EXECUTOR_EXCEPTION", null, "The native break threw; the world may have changed."); return; }
        JsonObject e = new JsonObject();
        e.addProperty("x", a.block.x()); e.addProperty("y", a.block.y()); e.addProperty("z", a.block.z());
        e.addProperty("before_block", o.before()); e.addProperty("after_block", o.after());
        e.addProperty("destroy_returned", o.destroyReturned());
        if (!o.destroyReturned()) {
            if (o.after().equals(o.before()) && o.toolBefore().equals(o.toolAfter()) && o.drops().isEmpty() && o.journal() == Journal.NOT_REQUIRED) {
                finalizeReceipt(a, "NO_EFFECT", "BREAK_REFUSED", true, e, tick);
            } else {
                hold(a, "AMBIGUOUS_EVIDENCE", null, "The break was refused but the world or the tool changed.");
            }
            return;
        }
        if (o.after().equals(o.before())) { hold(a, "AMBIGUOUS_EVIDENCE", null, "The break reported success but the same block is still there."); return; }
        e.addProperty("tool_before", o.toolBefore()); e.addProperty("tool_after", o.toolAfter());
        e.addProperty("tool_broke", o.toolBroke());
        JsonArray drops = new JsonArray();
        for (Drop d : o.drops()) {
            JsonObject j = new JsonObject();
            j.addProperty("uuid", d.uuid().toString()); j.addProperty("item", d.item()); j.addProperty("count", d.count());
            drops.add(j);
        }
        e.add("drops", drops);
        e.addProperty("collateral", o.collateral() ? "COLLATERAL" : "NONE");
        e.addProperty("journal", o.journal() == Journal.NOT_REQUIRED ? "NOT_REQUIRED" : "COMMITTED");
        if (o.journal() == Journal.FAILED) {
            JsonObject intended = receipt(a, "EFFECT_VERIFIED", "", true, e, tick);
            hold(a, "INVENTORY_COMMIT_FAILED", intended, "The block was broken but the inventory journal commit failed.");
            return;
        }
        finalizeReceipt(a, "EFFECT_VERIFIED", "", true, e, tick);
    }

    private void finishPickup(Attempt a, Started marker) {
        PickupOutcome o = env.executePickup(marker, a.actor, a.item, a.attemptId);
        a.evidenceToken = o.evidence();
        long tick = env.tick();
        if (o.threw()) { hold(a, "EXECUTOR_EXCEPTION", null, "The native pickup threw; the inventory and the item may have changed."); return; }
        int reduced = o.entityBefore() - o.entityAfter();
        JsonObject e = new JsonObject();
        e.addProperty("entity_uuid", a.item.entity().toString());
        e.addProperty("item_key", a.item.itemKey());
        e.addProperty("entity_count_before", o.entityBefore());
        e.addProperty("entity_count_after", o.entityAfter());
        e.addProperty("delta", o.delta());
        e.addProperty("event_count", o.events());
        e.addProperty("pre_count", o.preCount());
        e.addProperty("post_count", o.postCount());
        e.addProperty("journal", o.journal() == Journal.COMMITTED ? "COMMITTED" : "NOT_REQUIRED");
        // Vanilla fires the pickup event (and takes the item) only when Inventory.add reports progress in its LAST loop iteration; a
        // PARTIAL add returns false there although the stack has shrunk and the inventory has grown (found by the real GameTest).
        // So the event is a required witness of a FULL transfer only; a partial one is witnessed by inventory and entity alone.
        boolean full = o.delta() >= 1 && o.delta() == o.events() && o.delta() == reduced && o.entityAfter() == 0 && o.entityBefore() >= 1
                && o.postCount() - o.preCount() == o.delta();
        boolean partial = o.delta() >= 1 && o.delta() == reduced && o.entityAfter() > 0 && (o.events() == 0 || o.events() == o.delta())
                && o.postCount() - o.preCount() == o.delta();
        if ((full || partial) && o.evidenceComplete()) {
            String result = o.entityAfter() == 0 ? "EFFECT_VERIFIED" : "EFFECT_PARTIAL";
            if (o.journal() == Journal.FAILED) {
                e.addProperty("journal", "COMMITTED");
                hold(a, "INVENTORY_COMMIT_FAILED", receipt(a, result, "", true, e, tick),
                        "The item was taken but the inventory journal commit failed.");
                return;
            }
            if (o.journal() == Journal.COMMITTED) { finalizeReceipt(a, result, "", true, e, tick); return; }
        }
        boolean quiet = o.delta() == 0 && o.events() == 0 && reduced == 0 && o.postCount() == o.preCount()
                && o.journal() == Journal.NOT_REQUIRED && o.entityBefore() >= 1;
        if (quiet) { finalizeReceipt(a, "NO_EFFECT", "NO_TRANSFER", true, e, tick); return; }
        hold(a, "AMBIGUOUS_EVIDENCE", null, "The inventory, the pickup event and the item disagree (inventory " + o.preCount() + "->" + o.postCount()
                + ", event " + o.events() + ", item " + o.entityBefore() + "->" + o.entityAfter() + ", journal " + o.journal()
                + (o.evidenceComplete() ? "" : ", no post-effect snapshot") + ").");
    }

    // ------------------------------------------------------------------------------------------------ settle
    private JsonObject receipt(Attempt a, String result, String reason, boolean executed, JsonObject evidence, long tick) {
        JsonObject r = identity(a.reserve);
        r.remove("requester_id");
        r.addProperty("schema_version", 1);
        r.addProperty("kind", "native_effect_receipt");
        r.addProperty("action_kind", a.action.wire);
        r.addProperty("result", result);
        r.addProperty("reason", reason);
        r.addProperty("server_tick", tick);
        r.addProperty("executed", executed);
        r.add("evidence", evidence);
        return r;
    }

    private void finalizeReceipt(Attempt a, String result, String reason, boolean executed, JsonObject evidence, long tick) {
        JsonObject receipt = receipt(a, result, reason, executed, evidence, tick);
        a.receipt = receipt;
        String expected = result.equals("EFFECT_VERIFIED") || result.equals("EFFECT_PARTIAL") ? result : "FAILED_NO_EFFECT";
        a.phase = Phase.SETTLING;
        send(a, FINALIZE, receipt, MAX_TRIES, ack -> {
            boolean ok = echoes(ack, receipt, expected) && bool(ack, "backend_commit_ack") && bool(ack, "release_actor_fence")
                    && str(ack, "receipt_hash").equals(digest(receipt));
            if (ok) finish(a, expected, summary(a, result, reason, evidence));
            else unconfirmed(a, "The terminal ACK did not match what was sent (" + expected + ").");
        }, text -> unconfirmed(a, "The backend refused the terminal: " + text),
                () -> unconfirmed(a, "The terminal is not confirmed by the backend yet."));
    }

    private static String summary(Attempt a, String result, String reason, JsonObject evidence) {
        return switch (result) {
            case "EFFECT_VERIFIED" -> a.action == Action.BREAK
                    ? "Break complete: EFFECT_VERIFIED (" + evidence.get("before_block").getAsString() + " removed; drops are on the ground until picked up). Applied in the game; world save pending."
                    : "Pickup complete: EFFECT_VERIFIED (" + evidence.get("delta").getAsInt() + " item(s) moved into the inventory). Applied in memory; world save pending.";
            case "EFFECT_PARTIAL" -> "Pickup partial: " + evidence.get("delta").getAsInt() + " of " + evidence.get("entity_count_before").getAsInt()
                    + " item(s) fit; the rest stays on the ground (inventory full?). Verified.";
            default -> "No effect: " + reason + ". Nothing was changed.";
        };
    }

    /** Before the native call: close the attempt as failed-without-effect (a FINALIZE with executed=false, from RESERVED or EXECUTING). */
    private void failBeforeEffect(Attempt a, String reason, String text) {
        if (a.phase == Phase.DONE || a.phase == Phase.UNCONFIRMED) return;
        clearCrack(a);
        a.phase = Phase.SETTLING;
        if (reason.equals("OPERATOR")) reason = "ABORTED";                // not a backend failure reason
        JsonObject receipt = receipt(a, "PRECONDITION_FAILED", reason, false, new JsonObject(), env.tick());
        a.receipt = receipt;
        send(a, FINALIZE, receipt, MAX_TRIES, ack -> {
            boolean ok = echoes(ack, receipt, "FAILED_NO_EFFECT") && bool(ack, "backend_commit_ack") && str(ack, "receipt_hash").equals(digest(receipt));
            if (ok) finish(a, "FAILED_NO_EFFECT", text + " (FAILED_NO_EFFECT; nothing was executed)");
            else unconfirmed(a, text + " The failure terminal did not match; use Rec.");
        }, refusal -> unconfirmed(a, text + " The backend refused the failure terminal: " + refusal),
                () -> unconfirmed(a, text + " The failure terminal is unconfirmed; use Rec."));
    }

    private void settleNoEffect(Attempt a) {
        String text = a.abortText.isEmpty() ? "Cancelled." : a.abortText;
        if (a.cancelOperator) sendCancel(a, "OPERATOR", text);
        else failBeforeEffect(a, a.abortReason, text);
    }

    private void clearCrack(Attempt a) {
        if (a.block != null) env.crack(a.actor, a.block, -1);
    }

    private void sendCancel(Attempt a, String reason, String text) {
        a.phase = Phase.CANCELLING;
        JsonObject body = identity(a.reserve);
        body.addProperty("reason", reason);
        send(a, CANCEL, body, MAX_TRIES, ack -> {
            if (echoes(ack, body, "CANCELLED_NO_EFFECT")) finish(a, "CANCELLED_NO_EFFECT", text);
            else unconfirmed(a, text + " The cancel did not match; use Rec.");
        }, refusal -> unconfirmed(a, text + " The backend refused the cancel: " + refusal),
                () -> unconfirmed(a, text + " The cancel is unconfirmed; use Rec."));
    }

    /** Aborts an attempt that has not dispatched the native call. */
    private void abort(Attempt a, String reason, String text) {
        if (a.abort || a.phase == Phase.DONE || a.phase == Phase.SETTLING || a.phase == Phase.UNCONFIRMED || a.phase == Phase.CANCELLING) return;
        a.abort = true;
        a.abortReason = reason;
        a.abortText = text;
        clearCrack(a);
        if (a.phase == Phase.MINING) {                       // the marker was never requested: a RESERVED row
            if (a.cancelOperator) sendCancel(a, "OPERATOR", text);
            else if (reason.equals("REQUESTER_GONE")) sendCancel(a, "REQUESTER_GONE", text);
            else failBeforeEffect(a, reason, text);
        }
        // RESERVING / AWAITING_START: the pending reply or START message decides what to send; nothing is dispatched.
    }

    private void hold(Attempt a, String reason, JsonObject intended, String text) {
        a.holdReason = reason;
        a.intended = intended;
        a.phase = Phase.SETTLING;
        JsonObject body = identity(a.reserve);
        body.addProperty("reason", reason);
        body.add("intended", intended == null ? new JsonObject() : intended);
        send(a, HOLD, body, MAX_TRIES, ack -> {
            if (echoes(ack, body, "HOLD_IN_DOUBT") && bool(ack, "durable_ack")) {
                a.holdAcked = true;
                holdLocal(a, reason, text + " HOLD_IN_DOUBT is recorded; nothing is replayed. Use Rec. after checking the game.");
            } else {
                unconfirmed(a, text + " The HOLD ACK did not match; the ledger holds the attempt after a restart.");
            }
        }, refusal -> unconfirmed(a, text + " The backend refused the HOLD: " + refusal),
                () -> unconfirmed(a, text + " The HOLD is not confirmed by the backend yet."));
    }

    /** A confirmed (or locally certain) HOLD: the slot is released, the actor stays fenced. */
    private void holdLocal(Attempt a, String reason, String text) {
        a.holdReason = reason;
        a.phase = Phase.UNCONFIRMED;
        a.msg = null;
        pending.remove(a.actor.actorId(), a);
        held.put(a.actor.actorId(), a);
        release(a);
        env.notifyRequester(a.requester, "[NPCortex] " + text);
    }

    private void unconfirmed(Attempt a, String text) {
        a.phase = Phase.UNCONFIRMED;
        a.msg = null;
        if (pending.get(a.actor.actorId()) == a) {              // keeps its slot: the critical message is still owed to the backend
            pending.remove(a.actor.actorId(), a);
            held.put(a.actor.actorId(), a);
        }
        env.notifyRequester(a.requester, "[NPCortex] UNKNOWN: " + text + " Use Rec. in the Manager (nothing is replayed).");
    }

    private void finish(Attempt a, String state, String text) {
        a.phase = Phase.DONE;
        a.msg = null;
        pending.remove(a.actor.actorId(), a);
        held.remove(a.actor.actorId(), a);
        release(a);
        env.notifyRequester(a.requester, "[NPCortex] " + text);
    }

    private void release(Attempt a) {
        if (a.slot) { a.slot = false; slotsUsed = Math.max(0, slotsUsed - 1); }
    }

    // ------------------------------------------------------------------------------------------------ critical messages
    private void send(Attempt a, String route, JsonObject body, int maxTries, Consumer<JsonObject> onAck,
                      Consumer<String> onRefused, Runnable onGiveUp) {
        a.msg = new Msg(route, body, maxTries, onAck, onRefused, onGiveUp);
        pump(a);
    }

    private void pump(Attempt a) {
        Msg m = a.msg;
        if (m == null || m.inLane) return;
        m.inLane = true;
        m.tries++;
        CompletableFuture<JsonObject> future;
        try {
            future = env.post(m.route, m.body, true);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        future.whenComplete((reply, error) -> env.onServerThread(() -> msgDone(a, m, reply, error)));
    }

    private void msgDone(Attempt a, Msg m, JsonObject reply, Throwable error) {
        m.inLane = false;
        if (a.msg != m) return;
        try {
            if (error == null) { a.msg = null; m.onAck.accept(reply); return; }
            if (refused(error)) { a.msg = null; m.onRefused.accept(describe(error)); return; }
        } catch (RuntimeException e) {                      // never leave an attempt in a phase nothing will revive
            if (a.msg == null && a.phase != Phase.DONE && a.phase != Phase.UNCONFIRMED)
                unconfirmed(a, "Internal error while handling a backend reply.");
            return;
        }
        if (m.tries >= m.maxTries) { a.msg = null; m.onGiveUp.run(); return; }
        m.nextTick = env.tick() + RETRY_BACKOFF_TICKS;
    }

    // ------------------------------------------------------------------------------------------------ reconcile
    /** Operator action. Repairs the journal bookkeeping of an INVENTORY_COMMIT_FAILED hold (same session, strict rules), or
     * releases an unresolved row from OBSERVED persisted state. State {@code NO_NATIVE_ATTEMPT} means: not a native row. */
    CompletableFuture<Result> reconcile(UUID requester, String npcName) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        try {
            reconcileNow(requester, npcName, out);
        } catch (RuntimeException e) {
            if (!out.isDone()) out.complete(new Result(Kind.ERROR, "Internal error; nothing was changed.", ""));
        }
        return out;
    }

    private void reconcileNow(UUID requester, String npcName, CompletableFuture<Result> out) {
        if (requester == null) { out.complete(new Result(Kind.DENIED, "Unknown requester.", "")); return; }
        Actor actor = env.resolve(npcName);
        if (actor == null) { out.complete(new Result(Kind.REJECTED, "That NPC is not a live, alive registered actor.", "")); return; }
        NativeActionPolicy policy = env.policy(true);
        if (!policy.allowsRequester(requester, actor.dimension(), actor.name())) {
            out.complete(new Result(Kind.REJECTED, "Not a native-action requester for this NPC.", "NO_NATIVE_ATTEMPT")); return;
        }
        if (pending.containsKey(actor.actorId())) { out.complete(new Result(Kind.BUSY, "A native action for this NPC is still running.", "")); return; }
        Attempt local = held.get(actor.actorId());
        if (local != null && !local.requester.equals(requester)) { out.complete(new Result(Kind.DENIED, "Only the requester of that attempt may reconcile it.", "")); return; }
        ensureBound(actor).thenAccept(problem -> env.onServerThread(() -> {
            try {
                if (problem != null) { out.complete(new Result(Kind.REJECTED, problem, "")); return; }
                if (local != null && local.holdReason.equals("INVENTORY_COMMIT_FAILED") && local.intended != null && local.evidenceToken != null) {
                    if (local.holdAcked) repair(local, out);
                    else resendHoldThenRepair(local, out);       // the backend must hold the attempt before it can accept a repair
                } else if (local != null && local.receipt != null && local.phase == Phase.UNCONFIRMED && local.holdReason.isEmpty())
                    adopt(local, out);
                else discover(requester, actor, out);
            } catch (RuntimeException e) {
                if (!out.isDone()) out.complete(new Result(Kind.ERROR, "Internal error during reconcile; nothing was changed.", ""));
            }
        }));
    }

    /** The HOLD was never acknowledged: resend the identical bytes first, then repair only after the durable ACK. */
    private void resendHoldThenRepair(Attempt a, CompletableFuture<Result> out) {
        JsonObject body = identity(a.reserve);
        body.addProperty("reason", a.holdReason);
        body.add("intended", a.intended == null ? new JsonObject() : a.intended);
        env.post(HOLD, body, true).whenComplete((ack, error) -> env.onServerThread(() -> {
            try {
                if (error == null && echoes(ack, body, "HOLD_IN_DOUBT") && bool(ack, "durable_ack")) {
                    a.holdAcked = true;
                    repair(a, out);
                } else {
                    out.complete(new Result(Kind.UNKNOWN, "The backend did not confirm the HOLD (" + describe(error) + "); nothing was repaired. Use Rec. again.", "HOLD_IN_DOUBT"));
                }
            } catch (RuntimeException e) {
                if (!out.isDone()) out.complete(new Result(Kind.ERROR, "Internal error during reconcile; nothing was changed.", ""));
            }
        }));
    }

    /** The backend says no native row is unresolved for the actor: the local fence and its completion slot are obsolete. */
    private void dropHeld(UUID actorId) {
        Attempt h = held.remove(actorId);
        if (h != null) { h.phase = Phase.DONE; release(h); }
    }

    /** Persist-only repair: no physical effect is repeated and no new evidence is accepted. */
    private void repair(Attempt a, CompletableFuture<Result> out) {
        Actor now = env.resolve(a.actor.name());
        if (now == null || !now.equals(a.actor)) {
            out.complete(new Result(Kind.REJECTED, "Repair refused: the NPC was respawned or changed; reconcile by observation after a restart.", "HOLD_IN_DOUBT"));
            return;
        }
        Repair r = env.repair(now, a.attemptId, a.fingerprint, a.evidenceToken);
        if (r == Repair.REFUSED) {
            out.complete(new Result(Kind.REJECTED, "Repair refused: the inventory changed, the journal is unavailable or the evidence is gone. The attempt stays HOLD_IN_DOUBT.", "HOLD_IN_DOUBT"));
            return;
        }
        JsonObject body = identity(a.reserve);
        body.addProperty("intended_sha256", digest(a.intended));
        env.post(REPAIR, body, true).whenComplete((ack, error) -> env.onServerThread(() -> {
            boolean ok = error == null && echoes(ack, body, str(a.intended.getAsJsonObject(), "result")) && bool(ack, "backend_commit_ack")
                    && str(ack, "receipt_hash").equals(digest(a.intended));
            if (ok) {
                finish(a, str(ack, "state"), "Repaired: the inventory journal now holds this attempt; no pickup or break was repeated. Applied in memory; world save pending.");
                out.complete(new Result(Kind.OK, "Repaired (persist-only): " + str(ack, "state") + ". Nothing was repeated.", str(ack, "state")));
            } else {
                out.complete(new Result(Kind.UNKNOWN, "The journal was repaired locally but the backend did not confirm it (" + describe(error) + "); use Rec. again.", "HOLD_IN_DOUBT"));
            }
        }));
    }

    /** A FINALIZE that never got a matching ACK: ask the backend about EXACTLY this attempt; adopt only an identical receipt hash. */
    private void adopt(Attempt a, CompletableFuture<Result> out) {
        JsonObject body = identity(a.reserve);
        env.post(STATUS, body, false).whenComplete((st, error) -> env.onServerThread(() -> {
            if (error != null) {
                out.complete(new Result(Kind.UNKNOWN, "The backend status is unavailable (" + describe(error) + "). Nothing was adopted; use Rec. again later.", ""));
                return;
            }
            if (!echoes(st, body, str(st, "state")) || !str(st, "receipt_hash").equals(digest(a.receipt))) {
                // The ledger holds something else for this attempt (for example a restart sweep settled it first). Never adopt it;
                // judge from the ledger's own unresolved row and the OBSERVED world instead.
                try {
                    discover(a.requester, a.actor, out);
                } catch (RuntimeException e) {
                    if (!out.isDone()) out.complete(new Result(Kind.ERROR, "Internal error during reconcile; nothing was changed.", ""));
                }
                return;
            }
            finish(a, str(st, "state"), "Confirmed after a status check: " + str(st, "state") + ".");
            out.complete(new Result(Kind.OK, "Confirmed by the backend: " + str(st, "state") + ".", str(st, "state")));
        }));
    }

    /** No local evidence: ask the backend which native attempt owns the actor, then judge by OBSERVED persisted state. */
    private void discover(UUID requester, Actor actor, CompletableFuture<Result> out) {
        JsonObject lookup = new JsonObject();
        lookup.addProperty("world_id", actor.world().toString());
        lookup.addProperty("actor_id", actor.actorId().toString());
        lookup.addProperty("requester_id", requester.toString());
        env.post(ACTIVE, lookup, false).whenComplete((active, error) -> env.onServerThread(() -> {
            try {
                afterActive(requester, actor, lookup, active, error, out);
            } catch (RuntimeException e) {
                out.complete(new Result(Kind.ERROR, "Internal error during reconcile; nothing was changed.", ""));
            }
        }));
    }

    private void afterActive(UUID requester, Actor actor, JsonObject lookup, JsonObject active, Throwable error, CompletableFuture<Result> out) {
        if (error != null || active == null || !lookup.get("world_id").equals(active.get("world_id")) || !lookup.get("actor_id").equals(active.get("actor_id"))) {
            out.complete(new Result(Kind.UNKNOWN, "Native ledger status unavailable (" + describe(error) + "); nothing was changed.", ""));
            return;
        }
        if (!bool(active, "active")) {
            dropHeld(actor.actorId());                          // the ledger has nothing unresolved: the local fence and slot are obsolete
            out.complete(new Result(Kind.OK, "No unresolved native action.", "NO_NATIVE_ATTEMPT"));
            return;
        }
        JsonObject target = active.has("target") && active.get("target").isJsonObject() ? active.getAsJsonObject("target") : new JsonObject();
        String kind = str(active, "action_kind");
        JsonObject body = new JsonObject();
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id", "session_id"}) body.add(key, active.get(key));
        body.addProperty("requester_id", requester.toString());
        String klass;
        JsonObject observed = new JsonObject();
        if (str(active, "state").equals("RESERVED")) {
            klass = "ANY";
        } else if (kind.equals("break_block")) {
            String sha = env.observeBlock(str(active, "dimension_id"), intOf(target, "x"), intOf(target, "y"), intOf(target, "z"));
            if (sha == null) { out.complete(new Result(Kind.REJECTED, "Cannot observe the block (chunk not loaded); nothing was changed.", "HOLD_IN_DOUBT")); return; }
            observed.addProperty("x", intOf(target, "x")); observed.addProperty("y", intOf(target, "y")); observed.addProperty("z", intOf(target, "z"));
            observed.addProperty("state_sha256", sha);
            klass = sha.equals(str(target, "state_sha256")) ? "PERSISTED_STATE_UNCHANGED" : "EFFECT_OBSERVED";
        } else {
            UUID profile = UUID.fromString(str(active, "profile_id"));
            UUID attempt = UUID.fromString(str(active, "attempt_id"));
            String fp = "native-pickup|" + attempt;
            PickupObservation o = env.observePickup(str(active, "dimension_id"), UUID.fromString(str(target, "entity_uuid")),
                    str(target, "item_key"), intOf(target, "count"), dbl(target, "x"), dbl(target, "y"), dbl(target, "z"), profile, attempt, fp);
            if (o == null) { out.complete(new Result(Kind.REJECTED, "Cannot observe the item or the journal; nothing was changed.", "HOLD_IN_DOUBT")); return; }
            observed.addProperty("journal", o.journal());
            observed.addProperty("entity", o.entity());
            if (o.journal().equals("PRESENT") && o.entity().equals("ABSENT")) klass = "PERSISTED_TRANSFER";
            else if (o.journal().equals("ABSENT") && o.entity().equals("PRESENT_UNCHANGED")) klass = "PERSISTED_STATE_UNCHANGED";
            else {
                out.complete(new Result(Kind.REJECTED, "Stays HOLD_IN_DOUBT: journal " + o.journal() + ", item " + o.entity()
                        + " (" + (o.journal().equals("PRESENT") ? "duplication possible" : o.entity().equals("ABSENT") ? "loss possible" : "the item changed")
                        + "). Decide by hand in the game; nothing was changed.", "HOLD_IN_DOUBT"));
                return;
            }
        }
        body.addProperty("evidence_class", klass);
        body.add("observed", observed);
        env.post(RECONCILE, body, true).whenComplete((ack, e) -> env.onServerThread(() -> {
            String state = str(ack, "state");
            boolean ok = e == null && bool(ack, "backend_commit_ack") && bool(ack, "release_actor_fence") && body.get("task_id").equals(ack.get("task_id"));
            if (ok) {
                dropHeld(actor.actorId());
                out.complete(new Result(Kind.OK, "Released as " + state + " (operator reconcile from observed persisted state; the original attempt is NOT claimed to have happened or not).", state));
            } else {
                out.complete(new Result(Kind.UNKNOWN, "Reconcile unconfirmed (" + describe(e) + "); the ledger may already have released the row. Check again.", ""));
            }
        }));
    }

    // ------------------------------------------------------------------------------------------------ shutdown
    /** Server stopping: nothing more is dispatched and nothing is sent. The backend settles the session on its next start. */
    void shutdown() {
        previews.clear();
        for (Attempt a : pending.values()) {
            a.abort = true;
            a.phase = Phase.DONE;
            a.msg = null;
        }
        pending.clear();
        held.clear();
        slotsUsed = 0;
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private static JsonObject identity(JsonObject reserve) {
        JsonObject o = new JsonObject();
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id", "session_id", "requester_id"}) o.add(key, reserve.get(key));
        return o;
    }

    private static boolean echoes(JsonObject reply, JsonObject sent, String state) {
        if (reply == null || !state.equals(str(reply, "state"))) return false;
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id", "session_id"})
            if (!sent.has(key) || !sent.get(key).equals(reply.get(key))) return false;
        return !sent.has("requester_id") || !reply.has("requester_id") || sent.get("requester_id").equals(reply.get("requester_id"));
    }

    private static boolean refused(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof BackendRefusal) return true;
        return false;
    }

    private static String describe(Throwable error) {
        for (Throwable t = error; t != null && t.getCause() != t; t = t.getCause())
            if (t instanceof BackendRefusal r) return clip(r.getMessage());
        return error == null ? "unexpected reply" : "transport failure: " + clip(error.toString());
    }

    private static String clip(String text) {
        String flat = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ");
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? (int) Math.floor(o.get(key).getAsDouble()) : 0;
    }

    private static double dbl(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0.0;
    }

    static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean() && o.get(key).getAsBoolean();
    }

    // ---- canonical JSON, byte-compatible with the backend's json.dumps(sort_keys, separators=(',',':'), ensure_ascii) ----
    static String canonical(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        canonical(e, sb);
        return sb.toString();
    }

    private static void canonical(JsonElement e, StringBuilder sb) {
        if (e == null || e.isJsonNull()) { sb.append("null"); return; }
        if (e.isJsonObject()) {
            TreeMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) sorted.put(m.getKey(), m.getValue());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> m : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(m.getKey(), sb);
                sb.append(':');
                canonical(m.getValue(), sb);
            }
            sb.append('}');
        } else if (e.isJsonArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonElement i : e.getAsJsonArray()) {
                if (!first) sb.append(',');
                first = false;
                canonical(i, sb);
            }
            sb.append(']');
        } else if (e.getAsJsonPrimitive().isString()) {
            string(e.getAsString(), sb);
        } else if (e.getAsJsonPrimitive().isBoolean()) {
            sb.append(e.getAsBoolean() ? "true" : "false");
        } else {
            java.math.BigDecimal n = e.getAsBigDecimal();
            sb.append(n.stripTrailingZeros().scale() <= 0 ? n.toBigIntegerExact().toString() : n.toString());
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));          // Python escapes DEL (0x7f) too
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    static String digest(JsonElement e) {
        return WorldActionPolicy.sha(canonical(e).getBytes(StandardCharsets.UTF_8));
    }
}
