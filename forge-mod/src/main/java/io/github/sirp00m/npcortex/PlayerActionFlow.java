package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** PAP-G5 player-action pipeline for the NEW kinds place_block and use_block, pure Java (no Minecraft types), additive next to the
 * accepted {@link NativeEffectFlow} (break/pickup), which it neither extends nor changes.
 *
 * Preview (server-owned target, volatile nonce) -> Confirm -> backend RESERVE (durable intent + actor fence + block claims,
 * arbitrated atomically with the native ledger) -> backend START (durable EXECUTION_STARTED marker, ACK required)
 * -> [ONE server-thread task: final identity/policy/ownership/facade checks, native call in a try/catch, observation]
 * -> typed receipt -> backend FINALIZE (terminal + claim release) or HOLD (uncertain: fence and claims kept).
 *
 * Invariants (pinned by PlayerActionFlowSelfTest, same rules as the native flow):
 *  - the native call needs a {@link Started} token that only {@link #startAck} mints from a validated backend reply of THIS
 *    attempt: no confirmed marker, no native call;
 *  - nothing asynchronous sits between the final checks and the native call, nor between the call and the observation;
 *  - the requester comes from the authenticated sender; the client never supplies coordinates or block ids;
 *  - a LLM intention is never authorization: the only authority is the Owner allow-list decision recorded in RESERVE;
 *  - success is reported only for a matching, backend-committed terminal; a lost ACK is UNKNOWN, never success;
 *  - any throwable around the native call, evidence that disagrees, or an inventory persistence failure AFTER a real world effect
 *    is HOLD_IN_DOUBT: the effect is neither rolled back nor repeated; the operator reconciles from observed state.
 * No exactly-once physical guarantee is claimed and nothing here is atomic with chunk, entity or inventory storage.
 */
final class PlayerActionFlow {
    static final String CONTRACT = "player-actions-v1";
    private static final String BASE = "/v1/cplus/pa/";
    static final String RESERVE = BASE + "reserve", START = BASE + "start", FINALIZE = BASE + "finalize", CANCEL = BASE + "cancel",
            HOLD = BASE + "hold", RECONCILE = BASE + "reconcile", STATUS = BASE + "status", ACTIVE = BASE + "active";
    static final int SLOTS = 16;
    static final int PREVIEW_TTL_TICKS = 1200;
    static final int START_WAIT_TICKS = 100;
    static final int RETRY_BACKOFF_TICKS = 20;
    static final int MAX_TRIES = 6;
    static final int START_TRIES = 3;

    enum Action {
        PLACE("place_block", "pap-place|"), USE("use_block", "pap-use|");
        final String wire, fingerprintPrefix;
        Action(String wire, String fingerprintPrefix) { this.wire = wire; this.fingerprintPrefix = fingerprintPrefix; }
    }

    /** Server-resolved target. For PLACE (x,y,z) is the cell that changes, (sx,sy,sz) the supporting block that is clicked and
     * {@code blockId} the block that must appear; for USE (x,y,z) is the used block. {@code slot} < 0 means "not applicable". */
    record Target(String dimension, int x, int y, int z, String blockId, String stateSha, int sx, int sy, int sz, String face,
                  String itemKey, int itemCount, int slot) {}

    /** What the native call observed. {@code interaction} is the vanilla InteractionResult class: SUCCESS, CONSUME, PASS or FAIL. */
    record Outcome(boolean threw, String interaction, String before, String after, String stateBefore, String stateAfter,
                   int itemBefore, int itemAfter, int slot, NativeEffectFlow.Journal journal) {}

    /** Proof that the backend stored the EXECUTION_STARTED marker for exactly this attempt. Cannot be built elsewhere. */
    static final class Started {
        final UUID task;
        private Started(UUID task) { this.task = task; }
    }

    interface Env {
        void onServerThread(Runnable task);
        CompletableFuture<JsonObject> post(String route, JsonObject body, boolean critical);
        NativeEffectFlow.Actor resolve(String npcName);
        /** Null when no OTHER subsystem (including the native break/pickup flow) owns this actor, else why. */
        String ownershipBlock(NativeEffectFlow.Actor actor);
        NativeActionPolicy policy(boolean fresh);
        boolean online(UUID player);
        long tick();
        UUID newId();
        void notifyRequester(UUID requester, String text);
        boolean bound(UUID session);
        /** The accepted native binding handshake (the PAP ledger shares its world/session binding). Completes with null or why not. */
        CompletableFuture<String> ensureBound(NativeEffectFlow.Actor actor);
        NativeEffectFlow.Pick<Target> pick(Action action, UUID requester, NativeEffectFlow.Actor actor);
        /** Null = fine. Reach, line of sight, loaded chunk, survival, held item, replaceable cell, safe-target ceiling, protection. */
        String check(NativeEffectFlow.Actor actor, Action action, Target target);
        /** Hash of the current state of the target cell, null when its chunk is not loaded. */
        String state(Target target);
        Outcome execute(Started marker, NativeEffectFlow.Actor actor, Action action, Target target, UUID attempt);
        String journalPrecheck(NativeEffectFlow.Actor actor, UUID attempt, String fingerprint);
        /** Read-only observation of the target cell for Reconcile; null when it cannot be observed. */
        Observed observe(Target target);
    }

    record Observed(String blockId, String stateSha) {}

    private enum Phase { RESERVING, AWAITING_START, SETTLING, CANCELLING, UNCONFIRMED, DONE }

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
        final NativeEffectFlow.Actor actor;
        final Target target;
        final JsonObject reserve = new JsonObject();
        final String fingerprint;
        Phase phase = Phase.RESERVING;
        boolean abort;
        String abortReason = "ABORTED";
        String abortText = "";
        boolean cancelOperator;
        Msg msg;
        long startRequestedTick;
        long startRequestedNanos;
        JsonObject startBody;
        JsonObject receipt;
        String holdReason = "";
        boolean slot = true;

        Attempt(UUID requester, Action action, NativeEffectFlow.Actor actor, Target target) {
            this.task = env.newId(); this.request = env.newId(); this.attemptId = env.newId();
            this.requester = requester; this.action = action; this.actor = actor; this.target = target;
            this.fingerprint = action.fingerprintPrefix + attemptId;
        }
    }

    private record Preview(String nonce, Action action, NativeEffectFlow.Actor actor, Target target, long expires) {}

    private final Env env;
    private final Map<UUID, Attempt> pending = new LinkedHashMap<>();      // actor -> live attempt; server thread only
    private final Map<UUID, Attempt> held = new LinkedHashMap<>();         // actor -> HOLD / unconfirmed attempt (no slot)
    private final Map<String, Preview> previews = new HashMap<>();
    private int slotsUsed;

    PlayerActionFlow(Env env) { this.env = env; }

    boolean pending(UUID actorId) { return pending.containsKey(actorId) || held.containsKey(actorId); }

    boolean pendingProfile(UUID profileId) {
        for (Attempt a : pending.values()) if (a.actor.profileId().equals(profileId)) return true;
        for (Attempt a : held.values()) if (a.actor.profileId().equals(profileId)) return true;
        return false;
    }

    int slotsUsed() { return slotsUsed; }

    boolean hasPreview(UUID requester, String npc) { return requester != null && previews.containsKey(previewKey(requester, npc)); }

    private static String previewKey(UUID requester, String npc) { return requester + "|" + npc.toLowerCase(Locale.ROOT); }

    // ------------------------------------------------------------------------------------------------ policy
    /** The single place where an attempt is authorized: the Owner allow-list, nothing else. */
    static NativeActionPolicy.Decision decide(NativeActionPolicy policy, Action action, UUID requester, NativeEffectFlow.Actor actor, Target t) {
        return action == Action.PLACE
                ? policy.decidePlace(requester, t.dimension(), actor.name(), t.blockId(), t.x(), t.y(), t.z(), t.sx(), t.sy(), t.sz())
                : policy.decideUse(requester, t.dimension(), actor.name(), t.blockId(), t.x(), t.y(), t.z());
    }

    // ------------------------------------------------------------------------------------------------ preview
    /** Server thread. Chooses the target on the SERVER, takes no claim and writes nothing. */
    NativeEffectFlow.Result preview(UUID requester, String npcName, Action action) {
        try {
            return previewNow(requester, npcName, action);
        } catch (RuntimeException e) {
            return new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR, "Internal error; nothing was written.", "");
        }
    }

    private NativeEffectFlow.Result previewNow(UUID requester, String npcName, Action action) {
        if (requester == null) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.DENIED, "Unknown requester.", "");
        NativeEffectFlow.Actor actor = env.resolve(npcName);
        if (actor == null) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "That NPC is not a live, alive registered actor.", "");
        NativeActionPolicy policy = env.policy(true);
        if (!policy.loaded()) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.DENIED, "Denied: " + policy.problem(), "");
        if (!env.bound(actor.session())) {
            env.ensureBound(actor).thenAccept(problem -> { });
            return new NativeEffectFlow.Result(NativeEffectFlow.Kind.BUSY, "Checking the world binding with the backend; try again in a moment.", "");
        }
        if (pending(actor.actorId())) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.BUSY, "A world action for this NPC is still running or unresolved.", "");
        String block = env.ownershipBlock(actor);
        if (block != null) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "NPC busy: " + block, "");
        if (slotsUsed >= SLOTS) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.BUSY, "Too many player actions are in flight; wait for one to finish.", "");
        NativeEffectFlow.Pick<Target> pick = env.pick(action, requester, actor);
        if (pick.ref() == null) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, pick.reason(), "");
        Target t = pick.ref();
        NativeActionPolicy.Decision d = decide(policy, action, requester, actor, t);
        if (!d.allow()) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.DENIED, "Denied: " + d.reason(), "");
        String why = env.check(actor, action, t);
        if (why != null) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "Cannot " + (action == Action.PLACE ? "place" : "use") + " that: " + why, "");
        Preview preview = new Preview(env.newId().toString().replace("-", ""), action, actor, t, env.tick() + PREVIEW_TTL_TICKS);
        previews.put(previewKey(requester, npcName), preview);           // a new preview replaces the old one
        return new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "Preview: " + pick.text() + " Confirm to run it; the outcome is reported in chat.", "PREVIEW", preview.nonce());
    }

    /** Operator cancel: discards a preview, or aborts an attempt that has not crossed the durable marker. */
    NativeEffectFlow.Result cancel(UUID requester, String npcName) {
        if (requester == null) return new NativeEffectFlow.Result(NativeEffectFlow.Kind.DENIED, "Unknown requester.", "");
        Preview dropped = previews.remove(previewKey(requester, npcName));
        NativeEffectFlow.Actor actor = env.resolve(npcName);
        Attempt a = actor == null ? null : pending.get(actor.actorId());
        if (a != null && a.requester.equals(requester)) {
            if (a.phase == Phase.AWAITING_START || a.phase == Phase.RESERVING) {
                a.cancelOperator = true;
                abort(a, "OPERATOR", "Cancelled by the operator.");
                return new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "Cancel requested: no effect will be produced if the marker was not yet confirmed.", "CANCELLING");
            }
            return new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "Too late to cancel: the native call is being settled; the outcome is reported in chat.", "");
        }
        return dropped != null ? new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "Preview discarded.", "")
                : new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "Nothing to cancel.", "");
    }

    // ------------------------------------------------------------------------------------------------ confirm
    CompletableFuture<NativeEffectFlow.Result> confirm(UUID requester, String npcName, String nonce) {
        CompletableFuture<NativeEffectFlow.Result> out = new CompletableFuture<>();
        try {
            confirmNow(requester, npcName, nonce, out);
        } catch (RuntimeException e) {
            if (!out.isDone()) out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR, "Internal error before the reservation reply; use Rec. if the NPC stays busy.", ""));
        }
        return out;
    }

    private static NativeEffectFlow.Result r(NativeEffectFlow.Kind k, String text) { return new NativeEffectFlow.Result(k, text, ""); }

    private void confirmNow(UUID requester, String npcName, String nonce, CompletableFuture<NativeEffectFlow.Result> out) {
        if (requester == null) { out.complete(r(NativeEffectFlow.Kind.DENIED, "Unknown requester.")); return; }
        Preview pv = previews.remove(previewKey(requester, npcName));      // one use, whatever happens next
        if (pv == null || nonce == null || !pv.nonce().equals(nonce)) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "No matching preview: preview again.")); return; }
        if (env.tick() > pv.expires()) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "The preview expired: preview again.")); return; }
        NativeEffectFlow.Actor actor = env.resolve(npcName);
        if (actor == null) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "That NPC is not a live, alive registered actor.")); return; }
        if (!actor.equals(pv.actor())) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "The NPC changed since the preview: preview again.")); return; }
        if (!env.bound(actor.session())) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "Player actions are not bound to the backend for this session.")); return; }
        NativeActionPolicy.Decision d = decide(env.policy(true), pv.action(), requester, actor, pv.target());
        if (!d.allow()) { out.complete(r(NativeEffectFlow.Kind.DENIED, "Denied: " + d.reason())); return; }
        if (!pv.target().stateSha().equals(env.state(pv.target()))) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "Cannot proceed: the target changed since the preview.")); return; }
        String why = env.check(actor, pv.action(), pv.target());
        if (why != null) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "Cannot proceed: " + why)); return; }
        if (pending(actor.actorId())) { out.complete(r(NativeEffectFlow.Kind.BUSY, "A world action for this NPC is still running or unresolved.")); return; }
        String block = env.ownershipBlock(actor);
        if (block != null) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "NPC busy: " + block)); return; }
        if (slotsUsed >= SLOTS) { out.complete(r(NativeEffectFlow.Kind.BUSY, "Too many player actions are in flight; wait for one to finish.")); return; }
        Attempt a = new Attempt(requester, pv.action(), actor, pv.target());
        if (a.action == Action.PLACE) {
            String journal = env.journalPrecheck(actor, a.attemptId, a.fingerprint);
            if (journal != null) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "Inventory journal unavailable: " + journal)); return; }
        }
        buildReserve(a, d);
        slotsUsed++;
        pending.put(actor.actorId(), a);
        CompletableFuture<JsonObject> reserved;
        try {
            reserved = env.post(RESERVE, a.reserve, false);
        } catch (RuntimeException e) {
            release(a);
            pending.remove(actor.actorId(), a);
            out.complete(r(NativeEffectFlow.Kind.BUSY, "Backend transport busy; nothing was sent."));
            return;
        }
        reserved.whenComplete((reply, error) -> env.onServerThread(() -> {
            try {
                afterReserve(a, reply, error, out);
            } catch (RuntimeException e) {
                holdLocal(a, "UNEXPECTED_STATE", "Internal error after the reservation; ledger state UNKNOWN. Use Rec.");
                if (!out.isDone()) out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.UNKNOWN, "Internal error after the reservation; ledger state UNKNOWN. Use Rec.", ""));
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
        r.add("target", targetJson(a.action, a.target));
    }

    static JsonObject targetJson(Action action, Target t) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "block");
        o.addProperty("x", t.x()); o.addProperty("y", t.y()); o.addProperty("z", t.z());
        o.addProperty("block_id", t.blockId());
        o.addProperty("state_sha256", t.stateSha());
        o.addProperty("face", t.face());
        if (action == Action.PLACE) {
            o.addProperty("support_x", t.sx()); o.addProperty("support_y", t.sy()); o.addProperty("support_z", t.sz());
            o.addProperty("item_key", t.itemKey()); o.addProperty("item_count", t.itemCount()); o.addProperty("selected_slot", t.slot());
        }
        return o;
    }

    private void afterReserve(Attempt a, JsonObject reply, Throwable error, CompletableFuture<NativeEffectFlow.Result> out) {
        if (error != null || !NativeEffectFlow.echoes(reply, a.reserve, "RESERVED")) {
            if (NativeEffectFlow.refused(error)) {                     // the backend answered NO: nothing was written
                pending.remove(a.actor.actorId(), a);
                release(a);
                out.complete(r(NativeEffectFlow.Kind.REJECTED, "Backend refused the reservation: " + NativeEffectFlow.describe(error)));
                return;
            }
            // Unknown whether a RESERVED row exists. Compensate with a cancel (RESERVED -> CANCELLED_NO_EFFECT); never execute.
            a.phase = Phase.CANCELLING;
            out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.UNKNOWN, "Reservation reply unusable ("
                    + (error == null ? "unexpected reply" : NativeEffectFlow.describe(error)) + "); a cancel was queued and nothing was executed.", ""));
            sendCancel(a, "OPERATOR", "Reservation reply unusable; cancelled without effect.");
            return;
        }
        if (a.abort) {                                                // cancelled while the reserve was in flight
            out.complete(r(NativeEffectFlow.Kind.REJECTED, "Cancelled before it started."));
            settleNoEffect(a);
            return;
        }
        if (!env.online(a.requester)) {
            out.complete(r(NativeEffectFlow.Kind.REJECTED, "Requester left before execution; cancelling without effect."));
            a.abortReason = "REQUESTER_GONE"; a.abortText = "Requester left before execution.";
            sendCancel(a, "REQUESTER_GONE", "Requester left before execution.");
            return;
        }
        out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "Accepted: the NPC " + (a.action == Action.PLACE ? "places the block" : "uses the block")
                + ". The outcome is reported in chat.", "RESERVED"));
        requestStart(a);
    }

    // ------------------------------------------------------------------------------------------------ tick
    /** Server thread, once per game tick. */
    void tick() {
        long now = env.tick();
        for (Attempt a : new ArrayList<>(pending.values())) {
            try {
                Msg m = a.msg;
                if (m != null && !m.inLane && now >= m.nextTick) pump(a);
                if (a.phase == Phase.AWAITING_START && !a.abort && now - a.startRequestedTick > START_WAIT_TICKS)
                    abort(a, "START_ACK_LOST", "The start marker was not confirmed in time.");
            } catch (RuntimeException e) {
                abort(a, "ABORTED", "Internal error while waiting for the start marker.");
            }
        }
        previews.values().removeIf(p -> now > p.expires());
    }

    // ------------------------------------------------------------------------------------------------ START
    private void requestStart(Attempt a) {
        a.phase = Phase.AWAITING_START;
        a.startRequestedTick = env.tick();
        a.startRequestedNanos = System.nanoTime();
        JsonObject start = NativeEffectFlow.identity(a.reserve);
        a.startBody = start;
        send(a, START, start, START_TRIES, reply -> onStartReply(a, reply),
                text -> failBeforeEffect(a, "PRECONDITION_FAILED", "Backend refused the start: " + text),
                () -> failBeforeEffect(a, "START_ACK_LOST", "The start marker was not confirmed."));
    }

    /** The only factory of {@link Started}: the backend must echo the exact attempt identity in state EXECUTING. */
    static Started startAck(JsonObject reply, JsonObject sent, UUID task) {
        if (!NativeEffectFlow.echoes(reply, sent, "EXECUTING") || !reply.has("execution_started")
                || !reply.get("execution_started").isJsonPrimitive() || !reply.get("execution_started").getAsBoolean())
            return null;
        return new Started(task);
    }

    private void onStartReply(Attempt a, JsonObject reply) {
        if (a.phase != Phase.AWAITING_START) return;                    // a stale reply never dispatches
        Started marker = startAck(reply, a.startBody, a.task);
        if (marker == null) { failBeforeEffect(a, "START_ACK_LOST", "The start marker was not confirmed (reply did not match)."); return; }
        if (a.abort || env.tick() - a.startRequestedTick > START_WAIT_TICKS) {
            failBeforeEffect(a, a.abort ? a.abortReason : "START_ACK_LOST", a.abort ? a.abortText : "The start marker arrived too late ("
                    + (env.tick() - a.startRequestedTick) + " ticks, " + (System.nanoTime() - a.startRequestedNanos) / 1_000_000L + " ms).");
            return;
        }
        // ---- from here to the observation there is no asynchronous hop: this is one server-thread task ----
        a.phase = Phase.SETTLING;
        boolean entered = false;
        try {
            String why = finalPrecondition(a);
            if (why != null) { failBeforeEffect(a, reasonOf(why), "Final precondition failed: " + stripReason(why) + "; nothing was executed."); return; }
            entered = true;                                          // from here the native call may have happened
            runNative(a, marker);
        } catch (RuntimeException e) {
            if (a.phase == Phase.DONE || a.phase == Phase.UNCONFIRMED || a.msg != null) return;   // a FINALIZE/HOLD is already owed
            if (!entered) failBeforeEffect(a, "PRECONDITION_FAILED", "Internal error before the native call; nothing was executed.");
            else hold(a, "UNEXPECTED_STATE", "Internal error around the native call; the world may have changed.");
        }
    }

    private String finalPrecondition(Attempt a) {
        NativeEffectFlow.Actor now = env.resolve(a.actor.name());
        if (now == null) return "ACTOR_CHANGED:NPC is no longer live";
        if (!now.equals(a.actor)) return "ACTOR_CHANGED:actor, world, dimension, session or incarnation changed";
        if (!env.bound(now.session())) return "BINDING_LOST:the world binding is no longer verified";
        if (!env.online(a.requester)) return "REQUESTER_GONE:requester left";
        NativeActionPolicy.Decision d = decide(env.policy(true), a.action, a.requester, now, a.target);
        if (!d.allow()) return "PERMISSION_WITHDRAWN:" + d.reason();
        String block = env.ownershipBlock(now);
        if (block != null) return "PRECONDITION_FAILED:NPC busy: " + block;
        if (a.action == Action.PLACE) {
            String journal = env.journalPrecheck(now, a.attemptId, a.fingerprint);
            if (journal != null) return "JOURNAL_UNAVAILABLE:" + journal;
        }
        String state = env.state(a.target);
        if (state == null) return "CHUNK_UNLOADED:the chunk is not loaded";
        if (!a.target.stateSha().equals(state)) return "TARGET_CHANGED:the target changed";
        String why = env.check(now, a.action, a.target);
        if (why == null) return null;
        return (why.startsWith("out of reach") || why.startsWith("no line") ? "OUT_OF_REACH:" : "PRECONDITION_FAILED:") + why;
    }

    private static String reasonOf(String why) { int i = why.indexOf(':'); return i < 0 ? "PRECONDITION_FAILED" : why.substring(0, i); }

    private static String stripReason(String why) { int i = why.indexOf(':'); return i < 0 ? why : why.substring(i + 1); }

    // ------------------------------------------------------------------------------------------------ the native call
    private void runNative(Attempt a, Started marker) {
        Outcome o = env.execute(marker, a.actor, a.action, a.target, a.attemptId);
        long tick = env.tick();
        if (o.threw()) { hold(a, "EXECUTOR_EXCEPTION", "The native call threw; the world may have changed."); return; }
        boolean ok = o.interaction().equals("SUCCESS") || o.interaction().equals("CONSUME");
        JsonObject e = new JsonObject();
        e.addProperty("x", a.target.x()); e.addProperty("y", a.target.y()); e.addProperty("z", a.target.z());
        e.addProperty("interaction", o.interaction());
        if (a.action == Action.PLACE) {
            e.addProperty("before_block", o.before()); e.addProperty("after_block", o.after());
            e.addProperty("item_key", a.target.itemKey());
            e.addProperty("item_before", o.itemBefore()); e.addProperty("item_after", o.itemAfter());
            e.addProperty("selected_slot", o.slot());
            boolean placed = ok && o.after().equals(a.target.blockId()) && !o.before().equals(o.after()) && o.itemBefore() - o.itemAfter() == 1;
            if (placed) {
                if (o.journal() == NativeEffectFlow.Journal.FAILED) { hold(a, "INVENTORY_COMMIT_FAILED", "The block was placed but the inventory journal commit failed; the effect is neither rolled back nor repeated."); return; }
                if (o.journal() == NativeEffectFlow.Journal.COMMITTED) { e.addProperty("journal", "COMMITTED"); finalizeReceipt(a, "EFFECT_VERIFIED", "", e, tick); return; }
            }
            boolean quiet = !ok && o.after().equals(o.before()) && o.itemBefore() == o.itemAfter() && o.journal() == NativeEffectFlow.Journal.NOT_REQUIRED;
            if (quiet) { e.addProperty("journal", "NOT_REQUIRED"); finalizeReceipt(a, "NO_EFFECT", "PLACE_REFUSED", e, tick); return; }
            hold(a, "AMBIGUOUS_EVIDENCE", "The place result, the block and the item count disagree (" + o.interaction() + ", " + o.before() + "->" + o.after()
                    + ", items " + o.itemBefore() + "->" + o.itemAfter() + ", journal " + o.journal() + ").");
            return;
        }
        e.addProperty("block_id", a.target.blockId());
        e.addProperty("state_before_sha256", o.stateBefore()); e.addProperty("state_after_sha256", o.stateAfter());
        e.addProperty("journal", "NOT_REQUIRED");
        boolean changed = !o.stateBefore().equals(o.stateAfter());
        if (ok && changed && o.journal() == NativeEffectFlow.Journal.NOT_REQUIRED) { finalizeReceipt(a, "EFFECT_VERIFIED", "", e, tick); return; }
        if (!ok && !changed && o.journal() == NativeEffectFlow.Journal.NOT_REQUIRED) { finalizeReceipt(a, "NO_EFFECT", "USE_REFUSED", e, tick); return; }
        hold(a, "AMBIGUOUS_EVIDENCE", "The use result and the block state disagree (" + o.interaction() + ", state " + (changed ? "changed" : "unchanged")
                + ", journal " + o.journal() + ").");
    }

    // ------------------------------------------------------------------------------------------------ settle
    private JsonObject receipt(Attempt a, String result, String reason, boolean executed, JsonObject evidence, long tick) {
        JsonObject r = NativeEffectFlow.identity(a.reserve);
        r.remove("requester_id");
        r.addProperty("schema_version", 1);
        r.addProperty("kind", "player_action_receipt");
        r.addProperty("action_kind", a.action.wire);
        r.addProperty("result", result);
        r.addProperty("reason", reason);
        r.addProperty("server_tick", tick);
        r.addProperty("executed", executed);
        r.add("evidence", evidence);
        return r;
    }

    private void finalizeReceipt(Attempt a, String result, String reason, JsonObject evidence, long tick) {
        JsonObject receipt = receipt(a, result, reason, true, evidence, tick);
        a.receipt = receipt;
        String expected = result.equals("EFFECT_VERIFIED") ? result : "FAILED_NO_EFFECT";
        a.phase = Phase.SETTLING;
        send(a, FINALIZE, receipt, MAX_TRIES, ack -> {
            boolean ok = NativeEffectFlow.echoes(ack, receipt, expected) && NativeEffectFlow.bool(ack, "backend_commit_ack")
                    && NativeEffectFlow.bool(ack, "release_actor_fence") && NativeEffectFlow.str(ack, "receipt_hash").equals(NativeEffectFlow.digest(receipt));
            if (ok) finish(a, expected, summary(a, result, reason));
            else unconfirmed(a, "The terminal ACK did not match what was sent (" + expected + ").");
        }, text -> unconfirmed(a, "The backend refused the terminal: " + text),
                () -> unconfirmed(a, "The terminal is not confirmed by the backend yet."));
    }

    private static String summary(Attempt a, String result, String reason) {
        if (!result.equals("EFFECT_VERIFIED")) return "No effect: " + reason + ". Nothing was changed.";
        return a.action == Action.PLACE
                ? "Place complete: EFFECT_VERIFIED (" + a.target.blockId() + " at " + a.target.x() + "," + a.target.y() + "," + a.target.z()
                + "). Applied in the game; world save pending."
                : "Use complete: EFFECT_VERIFIED (" + a.target.blockId() + " state changed at " + a.target.x() + "," + a.target.y() + "," + a.target.z()
                + "). Applied in the game; world save pending.";
    }

    /** Before the native call: close the attempt as failed-without-effect (a FINALIZE with executed=false, from RESERVED or EXECUTING). */
    private void failBeforeEffect(Attempt a, String reason, String text) {
        if (a.phase == Phase.DONE || a.phase == Phase.UNCONFIRMED) return;
        a.phase = Phase.SETTLING;
        if (reason.equals("OPERATOR")) reason = "ABORTED";                // not a backend failure reason
        JsonObject receipt = receipt(a, "PRECONDITION_FAILED", reason, false, new JsonObject(), env.tick());
        a.receipt = receipt;
        send(a, FINALIZE, receipt, MAX_TRIES, ack -> {
            boolean ok = NativeEffectFlow.echoes(ack, receipt, "FAILED_NO_EFFECT") && NativeEffectFlow.bool(ack, "backend_commit_ack")
                    && NativeEffectFlow.str(ack, "receipt_hash").equals(NativeEffectFlow.digest(receipt));
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

    private void sendCancel(Attempt a, String reason, String text) {
        a.phase = Phase.CANCELLING;
        JsonObject body = NativeEffectFlow.identity(a.reserve);
        body.addProperty("reason", reason);
        send(a, CANCEL, body, MAX_TRIES, ack -> {
            if (NativeEffectFlow.echoes(ack, body, "CANCELLED_NO_EFFECT")) finish(a, "CANCELLED_NO_EFFECT", text);
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
        // RESERVING / AWAITING_START: the pending reply or START message decides what to send; nothing is dispatched.
    }

    private void hold(Attempt a, String reason, String text) {
        a.holdReason = reason;
        a.phase = Phase.SETTLING;
        JsonObject body = NativeEffectFlow.identity(a.reserve);
        body.addProperty("reason", reason);
        body.add("intended", new JsonObject());
        send(a, HOLD, body, MAX_TRIES, ack -> {
            if (NativeEffectFlow.echoes(ack, body, "HOLD_IN_DOUBT") && NativeEffectFlow.bool(ack, "durable_ack"))
                holdLocal(a, reason, text + " HOLD_IN_DOUBT is recorded; nothing is replayed or rolled back. Use Rec. after checking the game.");
            else unconfirmed(a, text + " The HOLD ACK did not match; the ledger holds the attempt after a restart.");
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
    private void send(Attempt a, String route, JsonObject body, int maxTries, Consumer<JsonObject> onAck, Consumer<String> onRefused, Runnable onGiveUp) {
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
            if (NativeEffectFlow.refused(error)) { a.msg = null; m.onRefused.accept(NativeEffectFlow.describe(error)); return; }
        } catch (RuntimeException e) {                      // never leave an attempt in a phase nothing will revive
            if (a.msg == null && a.phase != Phase.DONE && a.phase != Phase.UNCONFIRMED)
                unconfirmed(a, "Internal error while handling a backend reply.");
            return;
        }
        if (m.tries >= m.maxTries) { a.msg = null; m.onGiveUp.run(); return; }
        m.nextTick = env.tick() + RETRY_BACKOFF_TICKS;
    }

    // ------------------------------------------------------------------------------------------------ reconcile
    /** Operator action. Asks the backend which player action owns the actor, OBSERVES the target cell and records what is
     * observed. It never repeats an effect, never claims the original attempt happened or not, and never creates an inventory
     * receipt. An unresolved RESERVED/EXECUTING/HOLD row is released only from evidence the backend can check against the target. */
    CompletableFuture<NativeEffectFlow.Result> reconcile(UUID requester, String npcName) {
        CompletableFuture<NativeEffectFlow.Result> out = new CompletableFuture<>();
        try {
            reconcileNow(requester, npcName, out);
        } catch (RuntimeException e) {
            if (!out.isDone()) out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR, "Internal error; nothing was changed.", ""));
        }
        return out;
    }

    private void reconcileNow(UUID requester, String npcName, CompletableFuture<NativeEffectFlow.Result> out) {
        if (requester == null) { out.complete(r(NativeEffectFlow.Kind.DENIED, "Unknown requester.")); return; }
        NativeEffectFlow.Actor actor = env.resolve(npcName);
        if (actor == null) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "That NPC is not a live, alive registered actor.")); return; }
        if (!env.policy(true).allowsRequester(requester, actor.dimension(), actor.name())) {
            out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "Not an action requester for this NPC.", "NO_PLAYER_ACTION")); return;
        }
        if (pending.containsKey(actor.actorId())) { out.complete(r(NativeEffectFlow.Kind.BUSY, "A player action for this NPC is still running.")); return; }
        Attempt local = held.get(actor.actorId());
        if (local != null && !local.requester.equals(requester)) { out.complete(r(NativeEffectFlow.Kind.DENIED, "Only the requester of that attempt may reconcile it.")); return; }
        JsonObject find = new JsonObject();
        find.addProperty("world_id", actor.world().toString());
        find.addProperty("actor_id", actor.actorId().toString());
        find.addProperty("requester_id", requester.toString());
        env.post(ACTIVE, find, false).whenComplete((row, error) -> env.onServerThread(() -> {
            try {
                if (error != null) { out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.UNKNOWN, "The backend is unavailable (" + NativeEffectFlow.describe(error) + "); nothing was changed.", "")); return; }
                if (!"RESERVED".equals(NativeEffectFlow.str(row, "state")) && !"EXECUTING".equals(NativeEffectFlow.str(row, "state"))
                        && !"HOLD_IN_DOUBT".equals(NativeEffectFlow.str(row, "state"))) {
                    held.remove(actor.actorId());                    // the backend holds no unresolved player action: the local fence is obsolete
                    if (local != null) { local.phase = Phase.DONE; release(local); }
                    out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "No unresolved player action for this NPC.", "NO_PLAYER_ACTION"));
                    return;
                }
                reconcileRow(requester, actor, row, local, out);
            } catch (RuntimeException e) {
                if (!out.isDone()) out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR, "Internal error during reconcile; nothing was changed.", ""));
            }
        }));
    }

    private void reconcileRow(UUID requester, NativeEffectFlow.Actor actor, JsonObject row, Attempt local, CompletableFuture<NativeEffectFlow.Result> out) {
        String kind = NativeEffectFlow.str(row, "action_kind");
        Action action = kind.equals(Action.PLACE.wire) ? Action.PLACE : kind.equals(Action.USE.wire) ? Action.USE : null;
        JsonObject t = row.has("target") && row.get("target").isJsonObject() ? row.getAsJsonObject("target") : null;
        if (action == null || t == null) { out.complete(r(NativeEffectFlow.Kind.REJECTED, "The backend row is not a player action; nothing was changed.")); return; }
        Target target = new Target(NativeEffectFlow.str(row, "dimension_id"), t.get("x").getAsInt(), t.get("y").getAsInt(), t.get("z").getAsInt(),
                NativeEffectFlow.str(t, "block_id"), NativeEffectFlow.str(t, "state_sha256"), 0, 0, 0, "", "", 0, -1);
        Observed seen = env.observe(target);
        if (seen == null) { out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "Cannot observe the target cell (chunk not loaded); nothing was changed.", "HOLD_IN_DOUBT")); return; }
        boolean unchanged = seen.stateSha().equals(target.stateSha());
        JsonObject observed = new JsonObject();
        observed.addProperty("x", target.x()); observed.addProperty("y", target.y()); observed.addProperty("z", target.z());
        observed.addProperty("block_id", seen.blockId()); observed.addProperty("state_sha256", seen.stateSha());
        JsonObject body = new JsonObject();
        for (String key : new String[] {"task_id", "attempt_id", "world_id", "actor_id", "session_id", "requester_id"}) {
            if (key.equals("session_id")) body.addProperty(key, actor.session().toString());
            else body.add(key, row.get(key));
        }
        body.addProperty("evidence_class", unchanged ? "PERSISTED_STATE_UNCHANGED" : "EFFECT_OBSERVED");
        body.add("observed", observed);
        env.post(RECONCILE, body, true).whenComplete((ack, error) -> env.onServerThread(() -> {
            boolean ok = error == null && NativeEffectFlow.bool(ack, "backend_commit_ack") && NativeEffectFlow.bool(ack, "release_actor_fence");
            if (ok) {
                Attempt h = held.remove(actor.actorId());
                if (h != null) { h.phase = Phase.DONE; release(h); }
                out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "Reconciled from the observed state: " + NativeEffectFlow.str(ack, "state")
                        + ". Nothing was repeated and nothing was rolled back.", NativeEffectFlow.str(ack, "state")));
            } else {
                out.complete(new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED, "The backend did not accept the reconcile ("
                        + NativeEffectFlow.describe(error) + "); the attempt stays unresolved.", "HOLD_IN_DOUBT"));
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
}
