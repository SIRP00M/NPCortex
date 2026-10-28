package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.WorldEffectFlow.Actor;
import io.github.sirp00m.npcortex.WorldEffectFlow.Kind;
import io.github.sirp00m.npcortex.WorldEffectFlow.Result;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** G1/G3/G4 (offline): the no-op pipeline against a scripted server and a scripted backend with fault injection.
 * Fixture evidence only: it proves ordering and fail-closed handling, NOT chunk-save crash safety or live behaviour. */
public final class WorldEffectFlowSelfTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    enum Fault { NONE, TRANSPORT, REFUSE, LOST_REPLY, BAD_REPLY, DENY_ACK }

    /** Scripted server thread, backend and world. Every observable step is appended to {@code log}. */
    static final class Sim implements WorldEffectFlow.Env {
        final List<String> log = new ArrayList<>();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final Map<String, Fault> faults = new HashMap<>();
        final Map<String, JsonObject> sent = new HashMap<>();
        final Map<String, Integer> sentCount = new HashMap<>();
        final Actor actor = new Actor("Reika", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:overworld", UUID.randomUUID());
        final UUID owner = UUID.randomUUID();
        Actor current = actor;
        boolean online = true;
        String ownership;
        WorldActionPolicy policy;
        List<String> hashes = new ArrayList<>(List.of(H("A"), H("A")));
        int hashCalls;
        String backendState = "NONE";
        boolean active;
        Function<String, Runnable> onPost = route -> null;       // side effect while a route is being served
        long tick = 1000;

        Sim() { policy = allow(owner, "Reika"); }

        static WorldActionPolicy allow(UUID who, String actorName) {
            return WorldActionPolicy.parse(("{\"schema_version\":1,\"entries\":[{\"id\":\"t\",\"requester\":\"" + who
                    + "\",\"actions\":[\"noop\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"" + actorName + "\"]}]}")
                    .getBytes(StandardCharsets.UTF_8));
        }

        void drain() { while (!queue.isEmpty()) queue.poll().run(); }

        @Override public void onServerThread(Runnable task) { log.add("ENQ"); queue.add(() -> { log.add("RUN"); task.run(); }); }

        @Override public CompletableFuture<JsonObject> post(String route, JsonObject body) {
            String name = route.substring(route.lastIndexOf('/') + 1);
            log.add("POST " + name);
            sent.put(name, body.deepCopy());
            sentCount.merge(name, 1, Integer::sum);
            Runnable effect = onPost.apply(name);
            if (effect != null) effect.run();
            Fault fault = faults.getOrDefault(name, Fault.NONE);
            if (fault == Fault.TRANSPORT) return CompletableFuture.failedFuture(new IllegalStateException("down"));
            if (fault == Fault.REFUSE) return CompletableFuture.failedFuture(new WorldEffectFlow.BackendRefusal("HTTP 409 busy"));
            JsonObject reply = serve(name, body);              // the backend applied the call...
            if (fault == Fault.LOST_REPLY) return CompletableFuture.failedFuture(new IllegalStateException("timeout"));
            if (fault == Fault.BAD_REPLY) reply.addProperty("task_id", UUID.randomUUID().toString());
            if (fault == Fault.DENY_ACK) reply.addProperty("durable_ack", false);
            return CompletableFuture.completedFuture(reply);
        }

        JsonObject serve(String route, JsonObject body) {
            JsonObject r = body.deepCopy();
            r.remove("authorization");
            switch (route) {
                case "reserve" -> { backendState = "RESERVED"; active = true; }
                case "start" -> { backendState = "EXECUTING"; r.addProperty("execution_started", true); }
                case "cancel" -> { backendState = "CANCELLED_NO_EFFECT"; active = false; r.addProperty("durable_ack", true); r.addProperty("release_actor_fence", true); }
                case "hold" -> { backendState = "HOLD_IN_DOUBT"; r.addProperty("durable_ack", true); r.addProperty("release_actor_fence", false); }
                case "finalize" -> {
                    backendState = body.get("result").getAsString().equals("NO_EFFECT") ? "NO_EFFECT_VERIFIED" : "FAILED_NO_EFFECT";
                    active = false; r.addProperty("durable_ack", true); r.addProperty("release_actor_fence", true);
                }
                case "reconcile" -> { backendState = "RECONCILED_NO_EFFECT"; active = false; r.addProperty("durable_ack", true); r.addProperty("release_actor_fence", true); }
                case "status" -> { r.addProperty("active", active); r.addProperty("state", backendState);
                    if (active) { r.addProperty("task_id", UUID.randomUUID().toString()); r.addProperty("attempt_id", UUID.randomUUID().toString()); } }
                default -> throw new IllegalStateException(route);
            }
            if (!route.equals("status")) r.addProperty("state", backendState);
            r.addProperty("world_effects", false);
            return r;
        }

        @Override public Actor resolve(String npcName) { log.add("RESOLVE"); return current; }
        @Override public String ownershipBlock(Actor a) { return ownership; }
        @Override public WorldActionPolicy policy() { return policy; }
        @Override public boolean online(UUID player) { return online; }
        @Override public String fixtureHash(Actor a) {
            log.add("HASH");
            return hashCalls < hashes.size() ? hashes.get(hashCalls++) : (hashes.isEmpty() ? null : hashes.get(hashes.size() - 1));
        }
        @Override public long tick() { return tick; }
        @Override public UUID newId() { return UUID.randomUUID(); }

        int idx(String entry) { return log.indexOf(entry); }
        boolean posted(String name) { return sentCount.containsKey(name); }
    }

    // ---- wire contract: the exact bodies the Java flow sends, replayed by the Python ledger tests (see
    // backend/tests/test_cplus_we_java_wire.py). A shape change here fails until the fixture is regenerated on purpose.
    private static final JsonObject WIRE = new JsonObject();
    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    static void capture(String chain, Sim sim, String... routes) {
        JsonObject c = new JsonObject();
        for (String route : routes) c.add(route, sim.sent.get(route).deepCopy());
        WIRE.add(chain, c);
    }

    static String shape(com.google.gson.JsonElement e) {
        if (e.isJsonObject()) {
            StringBuilder b = new StringBuilder("{");
            new java.util.TreeSet<>(e.getAsJsonObject().keySet()).forEach(k -> b.append(k).append(':').append(shape(e.getAsJsonObject().get(k))).append(','));
            return b.append('}').toString();
        }
        if (e.isJsonNull()) return "null";
        com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "bool:" + p.getAsBoolean();
        if (p.isNumber()) return "number";
        String v = p.getAsString();
        return UUID_RE.matcher(v).matches() ? "uuid" : v.matches("[0-9a-f]{64}") ? "hex64" : "'" + v + "'";
    }

    private static Sim s;   // static so that scripted fault hooks may capture it

    static String H(String label) { return WorldEffectFlow.hashParts(List.of(label)); }

    static Result run(Sim s) {
        WorldEffectFlow flow = new WorldEffectFlow(s);
        CompletableFuture<Result> f = flow.run(s.owner, "Reika");
        s.drain();
        check(f.isDone(), "flow must complete once the queue is drained");
        check(!flow.pending(s.actor.actorId()), "actor pending flag is always released");
        return f.join();
    }

    public static void main(String[] args) throws Exception {
        // ============ happy path: order, receipt shape, one terminal
        s = new Sim();
        Result ok = run(s);
        check(ok.kind() == Kind.OK && ok.state().equals("NO_EFFECT_VERIFIED"), "happy path result: " + ok);
        List<String> posts = s.log.stream().filter(l -> l.startsWith("POST")).toList();
        check(posts.equals(List.of("POST reserve", "POST start", "POST finalize")), "exactly reserve, start, finalize: " + posts);
        int start = s.idx("POST start"), finalize = s.idx("POST finalize");
        int firstHash = s.idx("HASH"), lastHash = s.log.lastIndexOf("HASH");
        check(start < firstHash && lastHash < finalize, "fixture hashing happens strictly after the start marker and before the terminal");
        int resolveAt = start;
        while (!s.log.get(resolveAt).equals("RESOLVE")) resolveAt++;
        List<String> window = s.log.subList(resolveAt, lastHash + 1);
        check(window.stream().noneMatch(l -> l.equals("ENQ") || l.startsWith("POST")),
                "no asynchronous hop or backend call between the final precondition checks and the last fixture hash: " + window);
        JsonObject reserve = s.sent.get("reserve"), receipt = s.sent.get("finalize");
        check(reserve.get("requester_id").getAsString().equals(s.owner.toString()), "requester is the authenticated caller argument");
        check(reserve.get("origin").getAsString().equals("MANAGER_PACKET") && reserve.get("action_kind").getAsString().equals("noop"), "origin and action");
        check(reserve.getAsJsonObject("authorization").get("decision").getAsString().equals("ALLOW")
                && reserve.getAsJsonObject("authorization").get("policy_sha256").getAsString().equals(s.policy.sha256()), "policy decision recorded");
        for (String key : new String[]{"world_id", "actor_id", "profile_id", "session_id", "dimension_id"})
            check(reserve.has(key), "reserve binds " + key);
        check(reserve.get("world_id").getAsString().equals(s.actor.world().toString())
                && reserve.get("session_id").getAsString().equals(s.actor.session().toString()), "identities come from the server-resolved actor");
        check(receipt.get("result").getAsString().equals("NO_EFFECT") && receipt.get("executed").getAsBoolean()
                && !receipt.get("world_effects").getAsBoolean() && receipt.get("pre_hash").equals(receipt.get("post_hash"))
                && receipt.get("server_tick").getAsLong() == 1000 && !receipt.has("requester_id"), "typed NO_EFFECT receipt");
        check(receipt.get("task_id").equals(reserve.get("task_id")) && receipt.get("attempt_id").equals(reserve.get("attempt_id")), "receipt bound to the attempt");
        check(!s.posted("cancel") && !s.posted("hold"), "a successful no-op is never CANCELLED or HOLD");
        check(!ok.message().contains("CANCELLED"), "success text does not say cancelled");
        capture("happy", s, "reserve", "start", "finalize");

        // two requests = two independent attempts
        Sim two = new Sim();
        WorldEffectFlow f2 = new WorldEffectFlow(two);
        f2.run(two.owner, "Reika"); two.drain();
        String firstTask = two.sent.get("reserve").get("task_id").getAsString();
        f2.run(two.owner, "Reika"); two.drain();
        check(!firstTask.equals(two.sent.get("reserve").get("task_id").getAsString()), "a new request is a new attempt, never a replay");

        // ============ authority: nothing is written when denied
        Sim d = new Sim();
        d.policy = WorldActionPolicy.denyAll("missing");
        Result r = run(d);
        check(r.kind() == Kind.DENIED && d.sentCount.isEmpty(), "missing allow-list: DENIED, zero backend calls");
        d = new Sim(); d.policy = Sim.allow(UUID.randomUUID(), "Reika");
        r = run(d);
        check(r.kind() == Kind.DENIED && d.sentCount.isEmpty(), "requester not on the allow-list: DENIED, zero backend calls");
        d = new Sim(); d.policy = Sim.allow(d.owner, "Sakura");
        r = run(d);
        check(r.kind() == Kind.DENIED && d.sentCount.isEmpty(), "actor not covered: DENIED");
        d = new Sim();
        WorldEffectFlow nullFlow = new WorldEffectFlow(d);
        check(nullFlow.run(null, "Reika").join().kind() == Kind.DENIED && d.sentCount.isEmpty(), "unknown (null) requester: DENIED");
        d = new Sim(); d.current = null;
        check(run(d).kind() == Kind.REJECTED && d.sentCount.isEmpty(), "NPC not live: REJECTED, zero backend calls");
        d = new Sim(); d.ownership = "Follow active";
        r = run(d);
        check(r.kind() == Kind.REJECTED && r.message().contains("Follow") && d.sentCount.isEmpty(), "motion ownership held elsewhere: REJECTED");

        // ============ busy: a second request while one is in flight
        Sim b = new Sim();
        WorldEffectFlow bf = new WorldEffectFlow(b);
        CompletableFuture<Result> first = bf.run(b.owner, "Reika");
        check(bf.pending(b.actor.actorId()), "pending while the first is queued");
        Result second = bf.run(b.owner, "Reika").join();
        check(second.kind() == Kind.BUSY && b.sentCount.get("reserve") == 1, "second request is BUSY and writes nothing");
        b.drain();
        check(first.join().kind() == Kind.OK, "first completes");

        // ============ reserve faults
        s = new Sim(); s.faults.put("reserve", Fault.REFUSE);
        r = run(s);
        check(r.kind() == Kind.REJECTED && !s.posted("start") && !s.posted("cancel") && !s.posted("finalize"), "backend refusal: nothing else is sent");
        s = new Sim(); s.faults.put("reserve", Fault.TRANSPORT);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && s.posted("cancel") && !s.posted("start") && s.idx("HASH") < 0, "reserve transport failure: best-effort cancel, never executes");
        s = new Sim(); s.faults.put("reserve", Fault.LOST_REPLY);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && s.posted("cancel") && !s.posted("start"), "lost reserve reply: cancel, never start");
        check(s.backendState.equals("CANCELLED_NO_EFFECT"), "the possibly-stored RESERVED row ends as CANCELLED_NO_EFFECT");
        s = new Sim(); s.faults.put("reserve", Fault.BAD_REPLY);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && !s.posted("start") && s.posted("cancel"), "reply for a different task: never started");
        s = new Sim(); s.faults.put("reserve", Fault.LOST_REPLY); s.faults.put("cancel", Fault.TRANSPORT);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && r.message().contains("Reconcile") && !s.posted("start"), "reserve and cancel both lost: UNKNOWN, points at Reconcile");

        // ============ requester leaves
        s = new Sim(); s.onPost = route -> route.equals("reserve") ? () -> s.online = false : null;
        r = run(s);
        check(r.state().equals("CANCELLED_NO_EFFECT") && s.posted("cancel") && !s.posted("start") && s.sent.get("cancel").get("reason").getAsString().equals("REQUESTER_GONE"),
                "requester left before start: CANCELLED_NO_EFFECT, no marker, no executor");
        capture("cancelled", s, "reserve", "cancel");

        // ============ start faults: the executor must never run without a confirmed marker
        for (Fault fault : new Fault[]{Fault.TRANSPORT, Fault.REFUSE, Fault.LOST_REPLY, Fault.BAD_REPLY}) {
            s = new Sim(); s.faults.put("start", fault);
            r = run(s);
            check(s.idx("HASH") < 0, "start fault " + fault + ": executor/fixture hashing never reached");
            check(s.posted("finalize") && s.sent.get("finalize").get("result").getAsString().equals("PRECONDITION_FAILED")
                    && s.sent.get("finalize").get("reason").getAsString().equals("START_ACK_LOST")
                    && !s.sent.get("finalize").get("executed").getAsBoolean(), "start fault " + fault + ": closed as FAILED_NO_EFFECT (executed=false)");
            check(r.kind() == Kind.REJECTED && r.state().equals("FAILED_NO_EFFECT"), "start fault " + fault + " result " + r);
        }
        s = new Sim(); s.faults.put("start", Fault.LOST_REPLY); s.faults.put("finalize", Fault.TRANSPORT);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && s.idx("HASH") < 0, "start and failure terminal both lost: UNKNOWN, still no executor");
        JsonObject notStarted = new JsonObject();
        for (String key : new String[]{"task_id", "attempt_id", "world_id", "actor_id", "session_id", "requester_id"}) notStarted.addProperty(key, "x");
        JsonObject reply = notStarted.deepCopy(); reply.addProperty("state", "RESERVED");
        check(WorldEffectFlow.startAck(reply, notStarted, UUID.randomUUID()) == null, "RESERVED reply is not a start marker");
        reply.addProperty("state", "EXECUTING");
        check(WorldEffectFlow.startAck(reply, notStarted, UUID.randomUUID()) == null, "EXECUTING without execution_started is not a marker");
        reply.addProperty("execution_started", true);
        check(WorldEffectFlow.startAck(reply, notStarted, UUID.randomUUID()) != null, "complete echo mints the marker");
        reply.addProperty("world_effects", true);
        check(WorldEffectFlow.startAck(reply, notStarted, UUID.randomUUID()) == null, "a reply claiming a world effect is refused");
        boolean threw = false;
        try { WorldEffectFlow.executeNoop(null); } catch (IllegalStateException expected) { threw = true; }
        check(threw, "executor refuses a missing marker");
        check(Modifier.isPrivate(WorldEffectFlow.Started.class.getDeclaredConstructors()[0].getModifiers()), "Started cannot be constructed outside the flow");

        // ============ final preconditions run after the marker and block the executor
        s = new Sim(); s.onPost = route -> route.equals("start") ? () -> s.ownership = "C2 goal active" : null;
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && s.idx("HASH") < 0 && s.sent.get("finalize").get("reason").getAsString().equals("PRECONDITION_FAILED"),
                "ownership taken after RESERVE: marker durable, executor skipped, FAILED_NO_EFFECT");
        capture("failed", s, "reserve", "start", "finalize");
        s = new Sim(); s.onPost = route -> route.equals("start") ? () -> s.policy = WorldActionPolicy.denyAll("revoked") : null;
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && s.idx("HASH") < 0 && r.message().contains("permission withdrawn"), "allow-list revoked mid-flight");
        s = new Sim(); s.onPost = route -> route.equals("start") ? () -> s.online = false : null;
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && s.idx("HASH") < 0, "requester disconnected after the marker");
        s = new Sim(); s.onPost = route -> route.equals("start")
                ? () -> s.current = new Actor("Reika", s.actor.world(), s.actor.actorId(), s.actor.profileId(), "minecraft:overworld", UUID.randomUUID()) : null;
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && s.idx("HASH") < 0 && r.message().contains("identity"), "session identity changed after the marker");
        s = new Sim(); s.onPost = route -> route.equals("start")
                ? () -> s.current = new Actor("Reika", s.actor.world(), s.actor.actorId(), s.actor.profileId(), "minecraft:the_nether", s.actor.session()) : null;
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && s.idx("HASH") < 0, "dimension changed after the marker");
        s = new Sim(); s.onPost = route -> route.equals("start") ? () -> s.current = null : null;
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && s.idx("HASH") < 0, "NPC vanished after the marker");
        s = new Sim(); s.hashes = new ArrayList<>(); // unobservable world fixtures
        r = run(s);
        check(r.state().equals("FAILED_NO_EFFECT") && r.message().contains("not observable"), "unobservable fixtures: unknown is never 'unchanged'");

        // ============ fixture changed during the no-op: HOLD_IN_DOUBT, never success
        s = new Sim(); s.hashes = new ArrayList<>(List.of(H("A"), H("B")));
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && r.state().equals("HOLD_IN_DOUBT") && s.posted("hold") && !s.posted("finalize"), "changed fixture: HOLD_IN_DOUBT, no terminal");
        capture("hold", s, "reserve", "start", "hold");
        s = new Sim(); s.hashes = new ArrayList<>(List.of(H("A"))); // the second call repeats the last value
        check(run(s).kind() == Kind.OK, "identical repeated hash is accepted");
        Sim h = new Sim(); h.hashes = new ArrayList<>(List.of(H("A"), H("A"))); h.hashes.set(1, null);
        r = run(h);
        check(r.state().equals("HOLD_IN_DOUBT") && !h.posted("finalize"), "post-hash unobservable: HOLD_IN_DOUBT");

        // ============ terminal / ACK faults
        s = new Sim(); s.faults.put("finalize", Fault.TRANSPORT);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && !r.message().contains("complete:"), "terminal not delivered: UNKNOWN, never OK");
        s = new Sim(); s.faults.put("finalize", Fault.LOST_REPLY);
        r = run(s);
        check(r.kind() == Kind.UNKNOWN && s.backendState.equals("NO_EFFECT_VERIFIED"), "lost ACK: the backend holds the terminal, the GUI says UNKNOWN");
        s = new Sim(); s.faults.put("finalize", Fault.DENY_ACK);
        check(run(s).kind() == Kind.UNKNOWN, "ACK without durable_ack is not success");
        s = new Sim(); s.faults.put("finalize", Fault.BAD_REPLY);
        check(run(s).kind() == Kind.UNKNOWN, "ACK for another task is not success");
        s = new Sim(); s.faults.put("finalize", Fault.REFUSE);
        check(run(s).kind() == Kind.UNKNOWN, "terminal refused by the backend: UNKNOWN");
        check(s.sentCount.get("finalize") == 1 && s.sentCount.get("start") == 1, "no automatic re-execution or second marker");

        // ============ reconcile
        s = new Sim();
        WorldEffectFlow rf = new WorldEffectFlow(s);
        CompletableFuture<Result> rec = rf.reconcile(s.owner, "Reika"); s.drain();
        check(rec.join().kind() == Kind.OK && rec.join().message().contains("Nothing to reconcile") && !s.posted("reconcile"), "nothing active: no reconcile call");
        s = new Sim(); s.active = true; s.backendState = "HOLD_IN_DOUBT";
        rf = new WorldEffectFlow(s);
        rec = rf.reconcile(s.owner, "Reika"); s.drain();
        check(rec.join().kind() == Kind.OK && rec.join().state().equals("RECONCILED_NO_EFFECT") && s.posted("reconcile"), "active hold is released by reconcile");
        JsonObject rb = s.sent.get("reconcile");
        check(rb.get("session_id").getAsString().equals(s.actor.session().toString()) && rb.get("requester_id").getAsString().equals(s.owner.toString())
                && rb.get("fresh_hash").getAsString().equals(H("A")) && rec.join().message().contains("NOT claimed verified"), "reconcile binds the current session, requester and a fresh observation");
        WIRE.add("reconcile", rb.deepCopy());
        WIRE.add("status", s.sent.get("status").deepCopy());
        s = new Sim(); s.active = true; s.policy = WorldActionPolicy.denyAll("x");
        check(new WorldEffectFlow(s).reconcile(s.owner, "Reika").join().kind() == Kind.DENIED && s.sentCount.isEmpty(), "reconcile needs the same permission");
        s = new Sim(); s.active = true; s.hashes = new ArrayList<>();
        rec = new WorldEffectFlow(s).reconcile(s.owner, "Reika"); s.drain();
        check(rec.join().kind() == Kind.REJECTED && !s.posted("reconcile"), "reconcile needs a fresh observation");
        s = new Sim(); s.active = true; s.faults.put("reconcile", Fault.TRANSPORT);
        rec = new WorldEffectFlow(s).reconcile(s.owner, "Reika"); s.drain();
        check(rec.join().kind() == Kind.UNKNOWN, "reconcile transport failure is UNKNOWN");
        s = new Sim(); s.active = true; s.faults.put("status", Fault.TRANSPORT);
        rec = new WorldEffectFlow(s).reconcile(s.owner, "Reika"); s.drain();
        check(rec.join().kind() == Kind.UNKNOWN && !s.posted("reconcile"), "status failure changes nothing");
        s = new Sim(); s.active = true;
        WorldEffectFlow mixed = new WorldEffectFlow(s);
        mixed.reconcile(s.owner, "Reika");
        check(mixed.run(s.owner, "Reika").join().kind() == Kind.BUSY, "run is serialized behind an in-flight reconcile");
        s.drain();

        // ============ hashing helper
        check(WorldEffectFlow.hashParts(List.of("ab", "c")).equals(WorldEffectFlow.hashParts(List.of("ab", "c"))), "hash is stable");
        check(!WorldEffectFlow.hashParts(List.of("ab", "c")).equals(WorldEffectFlow.hashParts(List.of("a", "bc"))), "hash is length-prefixed");
        check(WorldEffectFlow.hashParts(List.of("a")).matches("[0-9a-f]{64}"), "lowercase sha256");
        // ============ wire contract against the committed fixture (the Python tests replay the same file)
        java.nio.file.Path fixture = java.nio.file.Path.of("..", "backend", "tests", "fixtures", "world_effect_wire_v1.json");
        if ("1".equals(System.getenv("WE_FIXTURE_WRITE"))) {
            java.nio.file.Files.createDirectories(fixture.getParent());
            java.nio.file.Files.writeString(fixture, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(WIRE) + "\n");
        }
        check(java.nio.file.Files.isRegularFile(fixture), "wire fixture exists: " + fixture.toAbsolutePath());
        JsonObject committed = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(fixture)).getAsJsonObject();
        check(shape(committed).equals(shape(WIRE)), "the bodies this flow sends still match the committed wire fixture");
        System.out.println("PASS WorldEffectFlowSelfTest (" + n + " assertions)");
    }
}
