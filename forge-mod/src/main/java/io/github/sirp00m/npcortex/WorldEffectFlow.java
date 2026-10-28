package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Stage 4 NO-OP world action pipeline, pure Java (no Minecraft types) so every ordering rule is unit-testable.
 *
 * Request -> policy -> actor/ownership -> backend RESERVE (durable intent + fence) -> backend START (durable
 * EXECUTION_STARTED marker) -> [ONE server-thread task: final identity/policy/ownership checks, fixture hash, no-op, fixture
 * hash] -> typed receipt -> backend FINALIZE (durable terminal + ACK, fence released by the ACK).
 *
 * Invariants (pinned by WorldEffectFlowSelfTest):
 *  - the executor can only be invoked with a {@link Started} token, which only {@link #startAck} can mint from a validated
 *    backend reply: no marker, no executor;
 *  - nothing asynchronous sits between the final precondition checks and the executor, nor between executor and receipt;
 *  - the requester is a parameter taken from the authenticated packet sender by the caller, never read from a message body;
 *  - every uncertain end state is reported as UNKNOWN / HOLD and never as success; nothing is retried by re-executing.
 * It performs NO world effect and claims no physical exactly-once guarantee.
 */
final class WorldEffectFlow {
    static final String ACTION = "noop";
    static final String ORIGIN = "MANAGER_PACKET";
    static final String RESERVE = "/v1/cplus/we/reserve", START = "/v1/cplus/we/start", FINALIZE = "/v1/cplus/we/finalize",
            CANCEL = "/v1/cplus/we/cancel", HOLD = "/v1/cplus/we/hold", RECONCILE = "/v1/cplus/we/reconcile",
            STATUS = "/v1/cplus/we/status";

    enum Kind { OK, DENIED, REJECTED, BUSY, ERROR, UNKNOWN }

    record Result(Kind kind, String message, String state) {}

    /** Server-resolved identities. Every field comes from saved server state, never from a client. */
    record Actor(String name, UUID world, UUID actorId, UUID profileId, String dimension, UUID session) {}

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
        /** Run on the Minecraft server thread (immediately when already there is NOT allowed: always a fresh task). */
        void onServerThread(Runnable task);
        /** Durable-outbox POST on a worker thread. Completes exceptionally on any transport failure. */
        CompletableFuture<JsonObject> post(String route, JsonObject body);
        /** Server thread. Null when the NPC is not a live, alive, registered actor. */
        Actor resolve(String npcName);
        /** Server thread. Null when no other subsystem owns this actor's motion/action state, else the reason. */
        String ownershipBlock(Actor actor);
        /** Server thread. Re-read on every request so a revoked entry stops working immediately. */
        WorldActionPolicy policy();
        boolean online(UUID player);
        /** Server thread. Hash of the observable fixtures a no-op must leave untouched; null when unobservable. */
        String fixtureHash(Actor actor);
        long tick();
        UUID newId();
    }

    private final Env env;
    private final Map<UUID, UUID> pending = new HashMap<>();   // actor -> task; server thread only

    WorldEffectFlow(Env env) { this.env = env; }

    boolean pending(UUID actor) { return pending.containsKey(actor); }

    // ------------------------------------------------------------------------------------------------ run
    /** Server thread. {@code requester} is the authenticated packet sender. */
    CompletableFuture<Result> run(UUID requester, String npcName) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        try {
            start(requester, npcName, out);
        } catch (RuntimeException e) {
            out.complete(new Result(Kind.ERROR, "Internal error before the reservation reply; ledger state UNKNOWN. Use Reconcile.", ""));
        }
        return out;
    }

    private void start(UUID requester, String npcName, CompletableFuture<Result> out) {
        if (requester == null) { out.complete(new Result(Kind.DENIED, "Unknown requester.", "")); return; }
        Actor actor = env.resolve(npcName);
        if (actor == null) { out.complete(new Result(Kind.REJECTED, "That NPC is not a live, alive registered actor.", "")); return; }
        WorldActionPolicy.Decision decision = env.policy().decide(requester, ACTION, actor.dimension(), actor.name());
        if (!decision.allow()) { out.complete(new Result(Kind.DENIED, "Denied: " + decision.reason(), "")); return; }
        if (pending.containsKey(actor.actorId())) { out.complete(new Result(Kind.BUSY, "A world action for this NPC is still running.", "")); return; }
        String block = env.ownershipBlock(actor);
        if (block != null) { out.complete(new Result(Kind.REJECTED, "NPC busy: " + block, "")); return; }
        UUID task = env.newId();
        pending.put(actor.actorId(), task);
        JsonObject reserve = new JsonObject();
        reserve.addProperty("schema_version", 1);
        reserve.addProperty("task_id", task.toString());
        reserve.addProperty("request_id", env.newId().toString());
        reserve.addProperty("attempt_id", env.newId().toString());
        reserve.addProperty("world_id", actor.world().toString());
        reserve.addProperty("actor_id", actor.actorId().toString());
        reserve.addProperty("profile_id", actor.profileId().toString());
        reserve.addProperty("session_id", actor.session().toString());
        reserve.addProperty("requester_id", requester.toString());
        reserve.addProperty("dimension_id", actor.dimension());
        reserve.addProperty("action_kind", ACTION);
        reserve.addProperty("origin", ORIGIN);
        JsonObject auth = new JsonObject();
        auth.addProperty("decision", "ALLOW");
        auth.addProperty("entry_id", decision.entryId());
        auth.addProperty("policy_sha256", decision.policySha256());
        reserve.add("authorization", auth);
        CompletableFuture<JsonObject> reserved;
        try {
            reserved = env.post(RESERVE, reserve);
        } catch (RuntimeException e) {
            pending.remove(actor.actorId(), task);       // nothing was dispatched
            throw e;
        }
        reserved.whenComplete((reply, error) -> env.onServerThread(() -> {
            try {
                afterReserve(requester, npcName, actor, task, reserve, reply, error, out);
            } catch (RuntimeException e) {
                pending.remove(actor.actorId(), task);
                out.complete(new Result(Kind.UNKNOWN, "Internal error after reservation; ledger state UNKNOWN. Use Reconcile.", ""));
            }
        }));
    }

    private void afterReserve(UUID requester, String npcName, Actor actor, UUID task, JsonObject reserve,
                              JsonObject reply, Throwable error, CompletableFuture<Result> out) {
        if (error != null || !echoes(reply, reserve, "RESERVED")) {
            if (refused(error)) {                                  // the backend answered NO: nothing was written
                pending.remove(actor.actorId(), task);
                out.complete(new Result(Kind.REJECTED, "Backend refused the reservation: " + describe(error), ""));
                return;
            }
            // Unknown whether a RESERVED row exists. Best-effort cancel (RESERVED -> CANCELLED_NO_EFFECT); never execute.
            env.post(CANCEL, identity(reserve, "CANCEL", "OPERATOR")).whenComplete((r, e) -> env.onServerThread(() -> {
                pending.remove(actor.actorId(), task);
                out.complete(new Result(Kind.UNKNOWN, "Reservation reply unusable (" + describe(error) + "); "
                        + (e == null ? "cancel requested, nothing executed." : "ledger state UNKNOWN, nothing executed. Use Reconcile."), ""));
            }));
            return;
        }
        if (!env.online(requester)) {                              // requester left before anything started
            cancelThenFinish(reserve, actor, task, out, "REQUESTER_GONE", "Requester left before execution; CANCELLED_NO_EFFECT.");
            return;
        }
        JsonObject start = identity(reserve, null, null);
        env.post(START, start).whenComplete((startReply, startError) -> env.onServerThread(() -> {
            try {
                afterStart(requester, npcName, actor, task, reserve, start, startReply, startError, out);
            } catch (RuntimeException e) {
                pending.remove(actor.actorId(), task);
                out.complete(new Result(Kind.UNKNOWN, "Internal error after the start marker; state UNKNOWN. Use Reconcile.", "EXECUTING"));
            }
        }));
    }

    private void cancelThenFinish(JsonObject reserve, Actor actor, UUID task, CompletableFuture<Result> out,
                                  String reason, String text) {
        env.post(CANCEL, identity(reserve, "CANCEL", reason)).whenComplete((r, e) -> env.onServerThread(() -> {
            pending.remove(actor.actorId(), task);
            out.complete(e == null && "CANCELLED_NO_EFFECT".equals(str(r, "state"))
                    ? new Result(Kind.REJECTED, text, "CANCELLED_NO_EFFECT")
                    : new Result(Kind.UNKNOWN, "Cancel unconfirmed; ledger state UNKNOWN. Use Reconcile.", ""));
        }));
    }

    /** The only factory of {@link Started}: the backend must echo the exact attempt identity in state EXECUTING. */
    static Started startAck(JsonObject reply, JsonObject sent, UUID task) {
        if (!echoes(reply, sent, "EXECUTING") || !reply.has("execution_started")
                || !reply.get("execution_started").isJsonPrimitive() || !reply.get("execution_started").getAsBoolean())
            return null;
        return new Started(task);
    }

    private void afterStart(UUID requester, String npcName, Actor actor, UUID task, JsonObject reserve, JsonObject start,
                            JsonObject reply, Throwable error, CompletableFuture<Result> out) {
        Started marker = error == null ? startAck(reply, start, task) : null;
        if (marker == null) {
            // The marker may or may not be durable. The executor is NOT dispatched; close the attempt as failed-without-effect.
            failed(reserve, actor, task, out, "START_ACK_LOST",
                    "Start marker not confirmed (" + describe(error) + "); nothing was executed.");
            return;
        }
        // ---- from here to the receipt there is no asynchronous hop: this is one server-thread task ----
        String why = finalPrecondition(requester, npcName, actor);
        String pre = why == null ? env.fixtureHash(actor) : null;
        if (why == null && pre == null) why = "world fixtures not observable (chunk not loaded)";
        if (why != null) {
            failed(reserve, actor, task, out, "PRECONDITION_FAILED", "Final precondition failed: " + why + "; nothing was executed.");
            return;
        }
        executeNoop(marker);
        String post = env.fixtureHash(actor);
        long tick = env.tick();
        // ---------------------------------------------------------------------------------------------------------
        if (post == null || !pre.equals(post)) {
            JsonObject hold = identity(reserve, "HOLD", "FIXTURE_CHANGED");
            env.post(HOLD, hold).whenComplete((r, e) -> env.onServerThread(() -> {
                pending.remove(actor.actorId(), task);
                out.complete(new Result(Kind.UNKNOWN, "Fixtures differ after a no-op: HOLD_IN_DOUBT"
                        + (e == null ? "." : " (hold not confirmed)") + " Nothing is replayed; use Reconcile.", "HOLD_IN_DOUBT"));
            }));
            return;
        }
        JsonObject receipt = identity(reserve, null, null);
        receipt.remove("requester_id");
        receipt.addProperty("schema_version", 1);
        receipt.addProperty("kind", "world_effect_receipt");
        receipt.addProperty("result", "NO_EFFECT");
        receipt.addProperty("reason", "");
        receipt.addProperty("server_tick", tick);
        receipt.addProperty("executed", true);
        receipt.addProperty("pre_hash", pre);
        receipt.addProperty("post_hash", post);
        receipt.addProperty("world_effects", false);
        env.post(FINALIZE, receipt).whenComplete((ack, ackError) -> env.onServerThread(() -> {
            pending.remove(actor.actorId(), task);
            if (ackError == null && echoes(ack, receipt, "NO_EFFECT_VERIFIED") && bool(ack, "durable_ack")
                    && bool(ack, "release_actor_fence") && !bool(ack, "world_effects"))
                out.complete(new Result(Kind.OK, "No-op complete: NO_EFFECT_VERIFIED, durable ACK received, actor fence released. "
                        + "No block, inventory or entity was changed.", "NO_EFFECT_VERIFIED"));
            else
                out.complete(new Result(Kind.UNKNOWN, "No-op ran but the terminal ACK is unconfirmed (" + describe(ackError)
                        + "). The backend may already hold the terminal; use Reconcile. Nothing is replayed.", "EXECUTING"));
        }));
    }

    /** The no-op executor. It is intentionally empty; the {@link Started} parameter is the ordering guarantee. */
    static void executeNoop(Started marker) {
        if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker");
    }

    private String finalPrecondition(UUID requester, String npcName, Actor actor) {
        Actor now = env.resolve(npcName);
        if (now == null) return "NPC is no longer live";
        if (!now.equals(actor)) return "actor, world, dimension or session identity changed";
        if (!env.online(requester)) return "requester left";
        WorldActionPolicy.Decision again = env.policy().decide(requester, ACTION, now.dimension(), now.name());
        if (!again.allow()) return "permission withdrawn";
        String block = env.ownershipBlock(now);
        return block == null ? null : "NPC busy: " + block;
    }

    private void failed(JsonObject reserve, Actor actor, UUID task, CompletableFuture<Result> out, String reason, String text) {
        JsonObject receipt = identity(reserve, null, null);
        receipt.remove("requester_id");
        receipt.addProperty("schema_version", 1);
        receipt.addProperty("kind", "world_effect_receipt");
        receipt.addProperty("result", "PRECONDITION_FAILED");
        receipt.addProperty("reason", reason);
        receipt.addProperty("server_tick", env.tick());
        receipt.addProperty("executed", false);
        receipt.addProperty("pre_hash", "");
        receipt.addProperty("post_hash", "");
        receipt.addProperty("world_effects", false);
        env.post(FINALIZE, receipt).whenComplete((ack, e) -> env.onServerThread(() -> {
            pending.remove(actor.actorId(), task);
            out.complete(e == null && echoes(ack, receipt, "FAILED_NO_EFFECT") && bool(ack, "durable_ack")
                    ? new Result(Kind.REJECTED, text + " (FAILED_NO_EFFECT)", "FAILED_NO_EFFECT")
                    : new Result(Kind.UNKNOWN, text + " The failure terminal is unconfirmed; use Reconcile.", ""));
        }));
    }

    // ------------------------------------------------------------------------------------------------ reconcile
    /** Operator-decided release of an unresolved ledger row. It never claims the earlier attempt was verified. */
    CompletableFuture<Result> reconcile(UUID requester, String npcName) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        try {
            Actor actor = requester == null ? null : env.resolve(npcName);
            if (requester == null) { out.complete(new Result(Kind.DENIED, "Unknown requester.", "")); return out; }
            if (actor == null) { out.complete(new Result(Kind.REJECTED, "That NPC is not a live, alive registered actor.", "")); return out; }
            WorldActionPolicy.Decision decision = env.policy().decide(requester, ACTION, actor.dimension(), actor.name());
            if (!decision.allow()) { out.complete(new Result(Kind.DENIED, "Denied: " + decision.reason(), "")); return out; }
            if (pending.containsKey(actor.actorId())) { out.complete(new Result(Kind.BUSY, "A world action for this NPC is still running.", "")); return out; }
            pending.put(actor.actorId(), new UUID(0, 0));        // serialize with run() on this actor
            JsonObject lookup = new JsonObject();
            lookup.addProperty("world_id", actor.world().toString());
            lookup.addProperty("actor_id", actor.actorId().toString());
            CompletableFuture<JsonObject> looked;
            try {
                looked = env.post(STATUS, lookup);
            } catch (RuntimeException e) {
                pending.remove(actor.actorId());
                throw e;
            }
            looked.whenComplete((status, error) -> env.onServerThread(() -> {
                try {
                    afterStatus(requester, npcName, actor, lookup, status, error, out);
                } catch (RuntimeException e) {
                    pending.remove(actor.actorId());
                    out.complete(new Result(Kind.ERROR, "Internal error during reconcile; nothing was changed.", ""));
                }
            }));
        } catch (RuntimeException e) {
            out.complete(new Result(Kind.ERROR, "Internal error; ledger state UNKNOWN. Use Reconcile.", ""));
        }
        return out;
    }

    private void afterStatus(UUID requester, String npcName, Actor actor, JsonObject lookup, JsonObject status,
                             Throwable error, CompletableFuture<Result> out) {
        if (error != null || status == null || !lookup.get("world_id").equals(status.get("world_id"))
                || !lookup.get("actor_id").equals(status.get("actor_id")) || !status.has("state")) {
            pending.remove(actor.actorId());
            out.complete(new Result(Kind.UNKNOWN, "Ledger status unavailable (" + describe(error) + "); nothing was changed.", ""));
            return;
        }
        if (!bool(status, "active")) {
            pending.remove(actor.actorId());
            out.complete(new Result(Kind.OK, "Nothing to reconcile: last world action is " + str(status, "state") + ".", str(status, "state")));
            return;
        }
        Actor now = env.resolve(npcName);
        String fresh = now == null || !now.equals(actor) ? null : env.fixtureHash(actor);
        if (fresh == null) {
            pending.remove(actor.actorId());
            out.complete(new Result(Kind.REJECTED, "Cannot take a fresh fixture observation; nothing was changed.", ""));
            return;
        }
        JsonObject body = new JsonObject();
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id"}) body.add(key, status.get(key));
        body.addProperty("session_id", actor.session().toString());
        body.addProperty("requester_id", requester.toString());
        body.addProperty("fresh_hash", fresh);
        env.post(RECONCILE, body).whenComplete((ack, e) -> env.onServerThread(() -> {
            pending.remove(actor.actorId());
            String state = str(ack, "state");
            out.complete(e == null && bool(ack, "durable_ack") && bool(ack, "release_actor_fence")
                    && (state.equals("RECONCILED_NO_EFFECT") || state.equals("CANCELLED_NO_EFFECT"))
                    && body.get("task_id").equals(ack.get("task_id"))
                    ? new Result(Kind.OK, "Released as " + state + " (operator reconcile; the earlier attempt is NOT claimed verified).", state)
                    : new Result(Kind.UNKNOWN, "Reconcile unconfirmed (" + describe(e) + "); the ledger may already have released the row. Check the status.", ""));
        }));
    }

    // ------------------------------------------------------------------------------------------------ helpers
    /** Identity fields of this attempt; {@code kindOverride} marks cancel/hold bodies which also carry a reason. */
    private static JsonObject identity(JsonObject reserve, String kind, String reason) {
        JsonObject o = new JsonObject();
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id", "session_id", "requester_id"})
            o.add(key, reserve.get(key));
        if (kind != null) o.addProperty("reason", reason);
        return o;
    }

    private static boolean echoes(JsonObject reply, JsonObject sent, String state) {
        if (reply == null || !state.equals(str(reply, "state"))) return false;
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id", "session_id"})
            if (!sent.has(key) || !sent.get(key).equals(reply.get(key))) return false;
        if (sent.has("requester_id") && reply.has("requester_id") && !sent.get("requester_id").equals(reply.get("requester_id")))
            return false;
        return !bool(reply, "world_effects") && !bool(reply, "native_mutation");
    }

    private static boolean refused(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof BackendRefusal) return true;
        return false;
    }

    private static String describe(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause())
            if (error instanceof BackendRefusal r) return clip(r.getMessage());
        return error == null ? "unexpected reply" : "transport failure";
    }

    private static String clip(String text) {
        String flat = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ");
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean()
                && o.get(key).getAsBoolean();
    }

    /** Length-prefixed SHA-256 of the observed fixture parts (order matters). */
    static String hashParts(List<String> parts) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (String part : parts) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
            bytes.writeBytes(b);
        }
        return WorldActionPolicy.sha(bytes.toByteArray());
    }
}
