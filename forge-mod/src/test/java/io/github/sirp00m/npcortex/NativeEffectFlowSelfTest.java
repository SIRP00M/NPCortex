package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.NativeEffectFlow.Action;
import io.github.sirp00m.npcortex.NativeEffectFlow.Actor;
import io.github.sirp00m.npcortex.NativeEffectFlow.BlockRef;
import io.github.sirp00m.npcortex.NativeEffectFlow.BreakOutcome;
import io.github.sirp00m.npcortex.NativeEffectFlow.Drop;
import io.github.sirp00m.npcortex.NativeEffectFlow.ItemRef;
import io.github.sirp00m.npcortex.NativeEffectFlow.Journal;
import io.github.sirp00m.npcortex.NativeEffectFlow.Kind;
import io.github.sirp00m.npcortex.NativeEffectFlow.MineStep;
import io.github.sirp00m.npcortex.NativeEffectFlow.Pick;
import io.github.sirp00m.npcortex.NativeEffectFlow.PickupObservation;
import io.github.sirp00m.npcortex.NativeEffectFlow.PickupOutcome;
import io.github.sirp00m.npcortex.NativeEffectFlow.Repair;
import io.github.sirp00m.npcortex.NativeEffectFlow.Result;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** G1-G5 (offline): the native break/pickup pipeline against a scripted server, a scripted ledger with the real contract's
 * state rules, and fault injection (lost replies, refusals, a full lane, late replies, executor faults, inventory-commit faults).
 * Fixture evidence only: it proves ordering and fail-closed handling, NOT the real Minecraft calls (see NativeGameTests) and NOT
 * chunk-save crash safety. */
public final class NativeEffectFlowSelfTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    enum Fault { NONE, TRANSPORT, REFUSE, LOST_REPLY, BAD_REPLY, DENY_ACK, LANE_FULL, BAD_HASH }

    static String H(String label) { return WorldActionPolicy.sha(label); }

    /** One scripted server + ledger. Several NPCs share it so contention can be exercised. */
    static final class Sim implements NativeEffectFlow.Env {
        final List<String> log = new ArrayList<>();
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final Map<String, Fault> faults = new HashMap<>();
        final Map<String, Integer> faultBudget = new HashMap<>();
        final Map<String, List<String>> sentBytes = new HashMap<>();
        final Map<String, JsonObject> sent = new HashMap<>();
        final UUID world = UUID.randomUUID(), session = UUID.randomUUID();
        final Map<String, Actor> actors = new HashMap<>();
        final UUID owner = UUID.randomUUID();
        boolean online = true;
        String ownership;
        NativeActionPolicy policy;
        long tick = 1000;
        int criticalPosts;
        final Map<String, String> blocks = new HashMap<>();         // "x,y,z" -> block id
        final Map<String, MineStep> mine = new HashMap<>();
        final Map<String, String> reach = new HashMap<>();
        Supplier<BreakOutcome> breakScript;
        Supplier<PickupOutcome> pickupScript;
        Pick<BlockRef> nextBlock;
        Pick<ItemRef> nextItem;
        String journalProblem;
        Repair repairResult = Repair.DONE;
        PickupObservation observation = new PickupObservation("ABSENT", "PRESENT_UNCHANGED");
        String observedBlock;
        int breakCalls, pickupCalls;
        Runnable onStart;                       // a side effect while the START is being served (a last-moment change)
        final List<String> notes = new ArrayList<>();
        /** Every call in order: {route, body, replyState} - the wire contract replayed by the Python ledger tests. */
        final List<String[]> trace = new ArrayList<>();
        // ---- the ledger ----
        final Map<String, Run> runs = new HashMap<>();
        final Set<String> blockClaims = new HashSet<>(), itemClaims = new HashSet<>();
        long epoch;
        int bindCalls;
        boolean bindOk = true;

        static final class Run {
            String state = "RESERVED", action, actor, target, receiptHash = "", holdIntended = "";
            JsonObject reserve;
            Set<String> claims = new HashSet<>();
            boolean held;
        }

        Sim() { policy = allow(owner, "[\"minecraft:oak_log\",\"minecraft:stone\"]", "[\"minecraft:oak_log\"]"); }

        static NativeActionPolicy allow(UUID who, String blocks, String items) {
            return NativeActionPolicy.parse(("{\"schema_version\":1,\"entries\":[{\"id\":\"t\",\"requester\":\"" + who
                    + "\",\"actions\":[\"break_block\",\"pickup_item\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"*\"],"
                    + "\"blocks\":" + blocks + ",\"items\":" + items + ",\"regions\":[{\"min\":[-100,-64,-100],\"max\":[100,100,100]}]}]}")
                    .getBytes(StandardCharsets.UTF_8));
        }

        Actor actor(String name) {
            return actors.computeIfAbsent(name, k -> new Actor(k, world, UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", session, 100 + actors.size()));
        }

        void respawn(String name) {
            Actor a = actor(name);
            actors.put(name, new Actor(a.name(), a.world(), a.actorId(), a.profileId(), a.dimension(), a.session(), a.incarnation() + 1000));
        }

        void drain() { while (!queue.isEmpty()) queue.poll().run(); }

        void advance(NativeEffectFlow flow, int ticks) {
            for (int i = 0; i < ticks; i++) { tick++; flow.tick(); drain(); }
        }

        @Override public void onServerThread(Runnable task) { log.add("ENQ"); queue.add(() -> { log.add("RUN"); task.run(); }); }

        @Override public CompletableFuture<JsonObject> post(String route, JsonObject body, boolean critical) {
            String name = route.substring(route.lastIndexOf('/') + 1);
            log.add("POST " + name);
            sent.put(name, body.deepCopy());
            sentBytes.computeIfAbsent(name, k -> new ArrayList<>()).add(body.toString());
            if (critical) criticalPosts++;
            if (name.equals("start") && onStart != null) { Runnable hook = onStart; onStart = null; hook.run(); }
            Fault fault = faults.getOrDefault(name, Fault.NONE);
            Integer budget = faultBudget.get(name);
            if (budget != null) {
                if (budget <= 0) fault = Fault.NONE; else faultBudget.put(name, budget - 1);
            }
            if (fault == Fault.LANE_FULL) return CompletableFuture.failedFuture(new IllegalStateException("lane full"));
            if (fault == Fault.TRANSPORT) return CompletableFuture.failedFuture(new IllegalStateException("down"));
            if (fault == Fault.REFUSE) return CompletableFuture.failedFuture(new NativeEffectFlow.BackendRefusal("HTTP 409 refused"));
            JsonObject reply;
            try {
                reply = serve(name, body);
            } catch (NativeEffectFlow.BackendRefusal refusal) {
                trace.add(new String[]{name, body.toString(), "REFUSED"});
                return CompletableFuture.failedFuture(refusal);
            }
            trace.add(new String[]{name, body.toString(), reply.has("state") ? reply.get("state").getAsString() : ""});
            if (fault == Fault.LOST_REPLY) return CompletableFuture.failedFuture(new IllegalStateException("timeout"));
            if (fault == Fault.BAD_REPLY) reply.addProperty("task_id", UUID.randomUUID().toString());
            if (fault == Fault.DENY_ACK) { reply.addProperty("backend_commit_ack", false); reply.addProperty("durable_ack", false); }
            if (fault == Fault.BAD_HASH) reply.addProperty("receipt_hash", "0".repeat(64));         // the backend recorded different evidence
            return CompletableFuture.completedFuture(reply);
        }

        private static NativeEffectFlow.BackendRefusal no(String why) { return new NativeEffectFlow.BackendRefusal("HTTP 409 " + why); }

        JsonObject echo(JsonObject body, String state) {
            JsonObject r = body.deepCopy();
            r.remove("authorization");
            r.remove("target");
            r.remove("evidence");
            r.addProperty("state", state);
            r.addProperty("native_mutation", true);
            return r;
        }

        JsonObject serve(String route, JsonObject body) {
            switch (route) {
                case "bind" -> {
                    bindCalls++;
                    if (!bindOk) throw no("FOLDER_MISMATCH");
                    JsonObject r = new JsonObject();
                    r.addProperty("state", "BOUND");
                    r.add("world_id", body.get("world_id"));
                    r.add("session_id", body.get("session_id"));
                    r.addProperty("epoch", ++epoch);
                    JsonArray caps = new JsonArray();
                    caps.add(NativeEffectFlow.CONTRACT);
                    r.add("capabilities", caps);
                    return r;
                }
                case "saved" -> { return echo(body, "SAVED"); }
                case "reserve" -> {
                    String task = body.get("task_id").getAsString();
                    Run old = runs.get(task);
                    if (old != null) return echo(old.reserve, old.state);
                    String actorId = body.get("actor_id").getAsString();
                    for (Run r : runs.values())
                        if (r.actor.equals(actorId) && (r.state.equals("RESERVED") || r.state.equals("EXECUTING") || r.state.equals("HOLD_IN_DOUBT")))
                            throw no("actor already has an unresolved native action");
                    JsonObject t = body.getAsJsonObject("target");
                    String claim = t.get("kind").getAsString().equals("block")
                            ? "b:" + t.get("x") + "," + t.get("y") + "," + t.get("z") : "i:" + t.get("entity_uuid").getAsString();
                    if (blockClaims.contains(claim) || itemClaims.contains(claim)) throw no("CLAIM_CONFLICT");
                    (claim.startsWith("b:") ? blockClaims : itemClaims).add(claim);
                    Run run = new Run();
                    run.action = body.get("action_kind").getAsString();
                    run.actor = actorId;
                    run.reserve = body.deepCopy();
                    run.claims.add(claim);
                    runs.put(task, run);
                    return echo(body, "RESERVED");
                }
                case "start" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    if (run == null || !(run.state.equals("RESERVED") || run.state.equals("EXECUTING"))) throw no("start refused");
                    run.state = "EXECUTING";
                    JsonObject r = echo(body, "EXECUTING");
                    r.addProperty("execution_started", true);
                    return r;
                }
                case "cancel" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    if (run == null || !run.state.equals("RESERVED")) throw no("cancel refused");
                    finish(run, "CANCELLED_NO_EFFECT", "");
                    JsonObject r = echo(body, run.state);
                    r.addProperty("backend_commit_ack", true);
                    r.addProperty("release_actor_fence", true);
                    return r;
                }
                case "finalize" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    if (run == null) throw no("unknown");
                    String hash = NativeEffectFlow.digest(body);
                    String result = body.get("result").getAsString();
                    String outcome = result.equals("EFFECT_VERIFIED") || result.equals("EFFECT_PARTIAL") ? result : "FAILED_NO_EFFECT";
                    if (!run.receiptHash.isEmpty()) {
                        if (run.receiptHash.equals(hash)) return terminalReply(body, run);
                        throw no("terminal already recorded with different evidence");
                    }
                    boolean needsMarker = !result.equals("PRECONDITION_FAILED");
                    if (!(run.state.equals("EXECUTING") || run.state.equals("RESERVED") && !needsMarker)) throw no("finalize refused in " + run.state);
                    finish(run, outcome, hash);
                    return terminalReply(body, run);
                }
                case "hold" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    if (run == null || !(run.state.equals("EXECUTING") || run.state.equals("HOLD_IN_DOUBT"))) throw no("hold refused");
                    run.state = "HOLD_IN_DOUBT";
                    run.held = true;
                    run.holdIntended = body.getAsJsonObject("intended").size() == 0 ? "" : NativeEffectFlow.digest(body.getAsJsonObject("intended"));
                    JsonObject r = echo(body, "HOLD_IN_DOUBT");
                    r.addProperty("durable_ack", true);
                    r.addProperty("release_actor_fence", false);
                    return r;
                }
                case "repair" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    if (run == null || !run.state.equals("HOLD_IN_DOUBT") || run.holdIntended.isEmpty()
                            || !run.holdIntended.equals(body.get("intended_sha256").getAsString())) throw no("repair refused");
                    finish(run, "EFFECT_VERIFIED", run.holdIntended);
                    return terminalReply(body, run);
                }
                case "status" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    JsonObject r = echo(body, run == null ? "NONE" : run.state);
                    if (run != null) r.addProperty("receipt_hash", run.receiptHash);
                    return r;
                }
                case "active" -> {
                    for (Map.Entry<String, Run> e : runs.entrySet()) {
                        Run run = e.getValue();
                        if (run.actor.equals(body.get("actor_id").getAsString()) && (run.state.equals("RESERVED") || run.state.equals("EXECUTING") || run.state.equals("HOLD_IN_DOUBT"))) {
                            JsonObject r = run.reserve.deepCopy();
                            r.remove("authorization");
                            r.addProperty("state", run.state);
                            r.addProperty("active", true);
                            return r;
                        }
                    }
                    JsonObject r = new JsonObject();
                    r.add("world_id", body.get("world_id"));
                    r.add("actor_id", body.get("actor_id"));
                    r.addProperty("state", "NONE");
                    r.addProperty("active", false);
                    return r;
                }
                case "reconcile" -> {
                    Run run = runs.get(body.get("task_id").getAsString());
                    if (run == null) throw no("unknown");
                    String klass = body.get("evidence_class").getAsString();
                    finish(run, "RECONCILED_" + klass, NativeEffectFlow.digest(body));
                    JsonObject r = echo(body, run.state);
                    r.addProperty("backend_commit_ack", true);
                    r.addProperty("release_actor_fence", true);
                    return r;
                }
                default -> throw new IllegalStateException(route);
            }
        }

        private void finish(Run run, String state, String hash) {
            run.state = state;
            run.receiptHash = hash;
            run.held = false;
            blockClaims.removeAll(run.claims);
            itemClaims.removeAll(run.claims);
        }

        private JsonObject terminalReply(JsonObject body, Run run) {
            JsonObject r = echo(body, run.state);
            r.addProperty("backend_commit_ack", true);
            r.addProperty("release_actor_fence", true);
            r.addProperty("receipt_hash", run.receiptHash);
            return r;
        }

        // ---- the scripted world ----
        @Override public Actor resolve(String npcName) { log.add("RESOLVE"); return npcName.equals("Ghost") ? null : actor(npcName); }
        Runnable atOwnership;                   // throws from inside the final precondition (an unexpected runtime error)

        @Override public String ownershipBlock(Actor a) { if (atOwnership != null) atOwnership.run(); return ownership; }
        @Override public NativeActionPolicy policy(boolean fresh) { return policy; }
        @Override public boolean online(UUID player) { return online; }
        @Override public long tick() { return tick; }
        @Override public UUID newId() { return UUID.randomUUID(); }
        @Override public void notifyRequester(UUID requester, String text) { notes.add(text); }
        @Override public JsonObject bindingRequest(Actor a) {
            JsonObject b = new JsonObject();
            b.add("world_id", new com.google.gson.JsonPrimitive(a.world().toString()));
            b.add("session_id", new com.google.gson.JsonPrimitive(a.session().toString()));
            b.addProperty("schema_version", 1); b.addProperty("folder_key", "W"); b.addProperty("epoch", epoch);
            b.addProperty("contract", NativeEffectFlow.CONTRACT);
            return b;
        }
        @Override public void bindingAdvanced(long e) { log.add("EPOCH " + e); }
        @Override public Pick<BlockRef> pickBlock(UUID requester, Actor actor) { return nextBlock; }
        @Override public String breakCheck(Actor actor, BlockRef ref) { return reach.get(actor.name()); }
        @Override public String blockState(BlockRef ref) { return blocks.getOrDefault(ref.x() + "," + ref.y() + "," + ref.z(), H("state-" + ref.blockId())); }
        @Override public MineStep mineStep(Actor actor, BlockRef ref) {
            return mine.getOrDefault(actor.name(), new MineStep(0.25f, "0|minecraft:air|", null));
        }
        @Override public void crack(Actor actor, BlockRef ref, int stage) { log.add("CRACK " + stage); }
        @Override public BreakOutcome executeBreak(NativeEffectFlow.Started marker, Actor actor, BlockRef ref, UUID attempt) {
            if (marker == null) throw new IllegalStateException("no marker");
            breakCalls++;
            log.add("EXEC_BREAK");
            return breakScript.get();
        }
        @Override public Pick<ItemRef> pickItem(UUID requester, Actor actor) { return nextItem; }
        @Override public String pickupCheck(Actor actor, ItemRef ref) { return reach.get(actor.name()); }
        @Override public PickupOutcome executePickup(NativeEffectFlow.Started marker, Actor actor, ItemRef ref, UUID attempt) {
            if (marker == null) throw new IllegalStateException("no marker");
            pickupCalls++;
            log.add("EXEC_PICKUP");
            return pickupScript.get();
        }
        @Override public String journalPrecheck(Actor actor, UUID attempt, String fingerprint) { return journalProblem; }
        @Override public Repair repair(Actor actor, UUID attempt, String fingerprint, Object evidence) { return repairResult; }
        @Override public String observeBlock(String dimension, int x, int y, int z) { return observedBlock; }
        @Override public PickupObservation observePickup(String dimension, UUID entity, String itemKey, int count, double x, double y, double z,
                                                         UUID profile, UUID attempt, String fingerprint) { return observation; }

        int idx(String entry) { return log.indexOf(entry); }
        int count(String entry) { int c = 0; for (String s : log) if (s.equals(entry)) c++; return c; }
        boolean posted(String name) { return sentBytes.containsKey(name); }
        int posts(String name) { List<String> l = sentBytes.get(name); return l == null ? 0 : l.size(); }
        String lastState() { String s = ""; for (Run r : runs.values()) s = r.state; return s; }
    }

    // ---------------------------------------------------------------------------------------------------- fixtures
    static BlockRef log(int x) { return new BlockRef("minecraft:overworld", x, 64, 5, "minecraft:oak_log", H("state-minecraft:oak_log")); }

    static ItemRef item(UUID e, int count) { return new ItemRef("minecraft:overworld", e, "minecraft:oak_log", H("nbt"), count, 5.5, 64.0, 5.5, 2); }

    static BreakOutcome brokeOk() {
        return new BreakOutcome(false, true, "minecraft:oak_log", "minecraft:air", "minecraft:air", "minecraft:air", false,
                List.of(new Drop(UUID.randomUUID(), "minecraft:oak_log", 1)), false, Journal.NOT_REQUIRED, "token");
    }

    static PickupOutcome pickedOk(int count) {
        return new PickupOutcome(false, count, 0, count, count, 2, 2 + count, Journal.COMMITTED, true, "token");
    }

    static Sim newSim() {
        Sim s = new Sim();
        s.nextBlock = new Pick<>(log(10), "", "break minecraft:oak_log at 10 64 5 (about 4.0 s of mining).");
        s.breakScript = NativeEffectFlowSelfTest::brokeOk;
        s.pickupScript = () -> pickedOk(4);
        return s;
    }

    /** preview -> confirm, binding done. */
    static Result confirmBreak(Sim s, NativeEffectFlow flow, String npc) {
        Result p = previewBound(s, flow, npc, Action.BREAK);
        check(p.kind() == Kind.OK && !p.nonce().isEmpty(), "preview accepted: " + p);
        CompletableFuture<Result> c = flow.confirm(s.owner, npc, p.nonce());
        s.drain();
        check(c.isDone(), "confirm completes once the queue is drained");
        return c.join();
    }

    static Result previewBound(Sim s, NativeEffectFlow flow, String npc, Action action) {
        Result first = flow.preview(s.owner, npc, action).join();
        s.drain();
        if (first.kind() == Kind.BUSY) first = flow.preview(s.owner, npc, action).join();       // the binding handshake finished
        return first;
    }

    static NativeEffectFlow flowOf(Sim s) { return new NativeEffectFlow(s); }

    // ---------------------------------------------------------------------------------------------------- tests
    /** The binding folder key must differ for different world roots that share a basename (negative test), and stay backend-valid. */
    private static void folderKeys() {
        try {
            java.nio.file.Path base = java.nio.file.Files.createTempDirectory("npcortex-fk");
            java.nio.file.Path a = java.nio.file.Files.createDirectories(base.resolve("saves-one").resolve("New World"));
            java.nio.file.Path b = java.nio.file.Files.createDirectories(base.resolve("saves-two").resolve("New World"));
            String ka = NativeWorldKey.folderKey(a), kb = NativeWorldKey.folderKey(b);
            check(!ka.equals(kb), "same basename, different parents -> different keys: " + ka + " / " + kb);
            check(ka.startsWith("New World-") && kb.startsWith("New World-"), "the key stays readable");
            check(ka.equals(NativeWorldKey.folderKey(a)) && ka.equals(NativeWorldKey.folderKey(a.resolve(".."). resolve("New World"))), "the key is stable and path-normalized");
            java.util.regex.Pattern backend = java.util.regex.Pattern.compile("^[\\w .()\\[\\]+,=@#!-]{1,128}$");   // cplus_wn_store._FOLDER
            java.nio.file.Path odd = java.nio.file.Files.createDirectories(base.resolve("x").resolve("W\u00e9ld \u2603 " + "n".repeat(200)));
            String ko = NativeWorldKey.folderKey(odd);
            check(backend.matcher(ko).matches() && ko.length() <= 128, "a long, non-ASCII basename still yields a backend-valid key: " + ko.length());
            check(backend.matcher(NativeWorldKey.folderKey(java.nio.file.Path.of("/"))).matches(), "a root path yields a valid key");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    public static void main(String[] args) {
        folderKeys();
        recoveryDeadEnds();
        previewRules();
        happyBreak();
        miningRules();
        startFaults();
        executorOutcomes();
        criticalDelivery();
        pickupOutcomes();
        capacityAndMultiNpc();
        reconcileAndRepair();
        canonicalJson();
        wireFixture();
        System.out.println("NativeEffectFlowSelfTest: " + n + " checks OK");
    }

    static void previewRules() {
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        check(flow.preview(null, "Reika", Action.BREAK).join().kind() == Kind.DENIED, "unknown requester denied");
        Result unbound = flow.preview(s.owner, "Reika", Action.BREAK).join();
        check(unbound.kind() == Kind.BUSY && s.bindCalls == 1, "the first request starts the binding handshake and is told to retry: " + unbound);
        check(s.posts("reserve") == 0, "no reservation before the binding is verified");
        s.drain();
        Result ok = flow.preview(s.owner, "Reika", Action.BREAK).join();
        check(ok.kind() == Kind.OK && ok.nonce().length() == 32 && ok.state().equals("PREVIEW"), "preview after binding: " + ok);
        check(s.posts("reserve") == 0 && s.runs.isEmpty(), "a preview writes nothing and takes no claim");
        check(flow.preview(s.owner, "Ghost", Action.BREAK).join().kind() == Kind.REJECTED, "unknown NPC rejected");

        // a requester that is not on the allow-list never gets a preview
        UUID stranger = UUID.randomUUID();
        check(flow.preview(stranger, "Reika", Action.BREAK).join().kind() == Kind.DENIED, "stranger denied");
        s.policy = NativeActionPolicy.denyAll("file missing");
        check(flow.preview(s.owner, "Reika", Action.BREAK).join().kind() == Kind.DENIED, "deny-all policy denies the preview");
        s.policy = Sim.allow(s.owner, "[\"minecraft:stone\"]", "[\"minecraft:oak_log\"]");
        Result blockDenied = flow.preview(s.owner, "Reika", Action.BREAK).join();
        check(blockDenied.kind() == Kind.DENIED && blockDenied.message().contains("not on the allow-list"), "a block that is not listed is denied: " + blockDenied);
        s.policy = Sim.allow(s.owner, "[\"minecraft:oak_log\"]", "[\"minecraft:oak_log\"]");

        // the nonce is one-use, bound to requester and NPC, and expires
        Result p1 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        check(flow.confirm(s.owner, "Reika", "0".repeat(32)).join().kind() == Kind.REJECTED, "a wrong nonce is refused");
        check(flow.confirm(s.owner, "Reika", p1.nonce()).join().kind() == Kind.REJECTED, "the failed attempt consumed the preview");
        Result p2 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        check(flow.confirm(UUID.randomUUID(), "Reika", p2.nonce()).join().kind() == Kind.REJECTED, "another requester cannot use the nonce");
        Result p3 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        s.tick += NativeEffectFlow.PREVIEW_TTL_TICKS + 1;
        check(flow.confirm(s.owner, "Reika", p3.nonce()).join().message().contains("expired"), "an expired preview is refused");
        check(s.posts("reserve") == 0, "no refused confirm reached the backend");

        // the world changes between preview and confirm
        Result p4 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        s.blocks.put("10,64,5", H("something-else"));
        check(flow.confirm(s.owner, "Reika", p4.nonce()).join().message().contains("changed"), "a changed block is refused at confirm");
        s.blocks.clear();
        Result p5 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        s.respawn("Reika");
        check(flow.confirm(s.owner, "Reika", p5.nonce()).join().message().contains("changed"), "a respawned NPC (new incarnation) is refused");
        check(s.posts("reserve") == 0, "still nothing reserved");
        Result p6 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        s.ownership = "Follow active";
        check(flow.confirm(s.owner, "Reika", p6.nonce()).join().kind() == Kind.REJECTED, "another subsystem owning the NPC blocks confirm");
        s.ownership = null;
        s.journalProblem = "the journal holds 512 receipts";
        Result p7 = flow.preview(s.owner, "Reika", Action.BREAK).join();
        check(flow.confirm(s.owner, "Reika", p7.nonce()).join().message().contains("journal"), "a full journal fails before anything is reserved");
        check(s.posts("reserve") == 0, "journal precheck precedes the reservation");
        s.journalProblem = null;
        s.reach.put("Reika", "out of reach: stand next to the block");
        check(flow.preview(s.owner, "Reika", Action.BREAK).join().message().contains("out of reach"), "reach is checked at preview");
        s.reach.clear();
        s.nextBlock = Pick.no("Look at a block (within 8 blocks), then press Break.");
        check(flow.preview(s.owner, "Reika", Action.BREAK).join().kind() == Kind.REJECTED, "no block in sight is a refusal");
    }

    static void happyBreak() {
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        Result accepted = confirmBreak(s, flow, "Reika");
        check(accepted.kind() == Kind.OK && accepted.state().equals("RESERVED"), "accepted: " + accepted);
        check(s.posts("reserve") == 1 && !s.posted("start") && s.breakCalls == 0, "mining has not asked for the marker yet");
        check(flow.pending(s.actor("Reika").actorId()) && flow.pendingProfile(s.actor("Reika").profileId()), "the NPC is fenced locally while mining");
        s.advance(flow, 3);
        check(!s.posted("start"), "progress 0.75 after 3 ticks: not complete, no marker");
        s.advance(flow, 1);
        check(s.posted("start") && s.breakCalls == 1, "completion requests the marker and the confirmed marker dispatches the break");
        int start = s.idx("POST start"), exec = s.idx("EXEC_BREAK"), fin = s.idx("POST finalize");
        check(start >= 0 && start < exec && exec < fin, "order: START before the native call before FINALIZE: " + s.log);
        // nothing asynchronous between the final server-thread task start and the native call
        int run = s.log.subList(0, exec).lastIndexOf("RUN");
        check(!s.log.subList(run, exec).contains("ENQ") && !s.log.subList(run, exec).contains("POST finalize"), "no async hop before the native call");
        check(s.runs.values().iterator().next().state.equals("EFFECT_VERIFIED"), "ledger terminal EFFECT_VERIFIED");
        check(s.blockClaims.isEmpty(), "the claim is released with the terminal");
        check(!flow.pending(s.actor("Reika").actorId()) && flow.slotsUsed() == 0, "fence and slot released after the confirmed terminal");
        check(s.notes.size() == 1 && s.notes.get(0).contains("EFFECT_VERIFIED"), "the outcome is reported to the requester: " + s.notes);
        check(s.log.contains("CRACK -1"), "the crack animation is cleared");
        JsonObject receipt = s.sent.get("finalize");
        check(receipt.get("result").getAsString().equals("EFFECT_VERIFIED") && receipt.get("executed").getAsBoolean(), "typed receipt");
        check(receipt.getAsJsonObject("evidence").get("before_block").getAsString().equals("minecraft:oak_log"), "evidence names the block");
        check(!receipt.has("requester_id"), "the receipt carries no requester field");
        check(s.sent.get("reserve").getAsJsonObject("target").get("x").getAsInt() == 10, "the target came from the server");
    }

    static void miningRules() {
        // a tool change resets the progress
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 3);
        s.mine.put("Reika", new MineStep(0.25f, "1|minecraft:iron_axe|", null));
        s.advance(flow, 3);
        check(!s.posted("start"), "a tool change restarts the progress: 3 ticks of the new tool are not enough");
        s.advance(flow, 1);
        check(s.posted("start"), "the new tool completes after its own 4 ticks");

        // permission withdrawn mid-mining: FINALIZE failure, executor never called
        s = newSim();
        flow = flowOf(s);
        s.mine.put("Reika", new MineStep(0.01f, "0|minecraft:air|", null));
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 2);
        s.policy = NativeActionPolicy.denyAll("file removed");
        s.advance(flow, NativeEffectFlow.POLICY_RECHECK_TICKS);
        check(s.breakCalls == 0 && !s.posted("start"), "withdrawn permission: no marker, no native call");
        check(s.sent.get("finalize").get("reason").getAsString().equals("PERMISSION_WITHDRAWN") && !s.sent.get("finalize").get("executed").getAsBoolean(), "typed failure receipt");
        check(s.runs.values().iterator().next().state.equals("FAILED_NO_EFFECT") && s.blockClaims.isEmpty(), "terminal FAILED_NO_EFFECT, claim released");

        // each revalidation: actor respawned, block changed, out of reach, requester left, game mode
        String[][] cases = {{"respawn", "ACTOR_CHANGED"}, {"block", "TARGET_CHANGED"}, {"reach", "OUT_OF_REACH"}, {"offline", "REQUESTER_GONE"}, {"mode", "TOOL_CHANGED"}};
        for (String[] c : cases) {
            s = newSim();
            flow = flowOf(s);
            confirmBreak(s, flow, "Reika");
            s.advance(flow, 1);
            switch (c[0]) {
                case "respawn" -> s.respawn("Reika");
                case "block" -> s.blocks.put("10,64,5", H("changed"));
                case "reach" -> s.reach.put("Reika", "out of reach: stand next to the block");
                case "offline" -> s.online = false;
                default -> s.mine.put("Reika", new MineStep(0f, "", "the game mode changed"));
            }
            s.advance(flow, 2);
            check(s.breakCalls == 0, c[0] + ": the native call is never made");
            check(s.runs.values().iterator().next().state.equals(c[0].equals("offline") ? "CANCELLED_NO_EFFECT" : "FAILED_NO_EFFECT"), c[0] + ": settled without effect: " + s.lastState());
            if (!c[0].equals("offline")) check(s.sent.get("finalize").get("reason").getAsString().equals(c[1]), c[0] + ": reason " + s.sent.get("finalize").get("reason"));
            check(!flow.pending(s.actor("Reika").actorId()) && flow.slotsUsed() == 0, c[0] + ": fence released");
        }

        // a stalled rate cannot hold the actor forever
        s = newSim();
        flow = flowOf(s);
        s.mine.put("Reika", new MineStep(0f, "0|minecraft:air|", null));
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 260);
        check(s.breakCalls == 0 && s.sent.get("finalize").get("reason").getAsString().equals("STALLED"), "a zero rate ends as STALLED");

        // operator cancel while mining
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 1);
        Result cancel = flow.cancel(s.owner, "Reika");
        s.drain();
        check(cancel.kind() == Kind.OK && s.posted("cancel") && s.breakCalls == 0, "cancel before the marker sends CANCEL: " + cancel);
        check(s.runs.values().iterator().next().state.equals("CANCELLED_NO_EFFECT"), "CANCELLED_NO_EFFECT");
        check(flow.cancel(s.owner, "Reika").kind() == Kind.REJECTED, "nothing left to cancel");

        // the progress counter is idempotent within a tick (two hooks must not double the rate)
        MiningProgress mp = new MiningProgress();
        check(mp.advance(5, "t", 0.25f) && !mp.advance(5, "t", 0.25f) && mp.progress() == 0.25f, "one step per game tick");
        check(!mp.advance(6, "t", Float.NaN) && mp.progress() == 0.25f, "a non-finite rate adds nothing");
        mp.advance(7, "other", 0.5f);
        check(mp.progress() == 0.5f && mp.steps() == 1, "a tool change resets");
    }

    static void startFaults() {
        // START refused / lost: the native call is never made and the attempt is closed without effect
        for (Fault fault : new Fault[]{Fault.TRANSPORT, Fault.LOST_REPLY, Fault.BAD_REPLY, Fault.REFUSE}) {
            Sim s = newSim();
            NativeEffectFlow flow = flowOf(s);
            confirmBreak(s, flow, "Reika");
            s.faults.put("start", fault);
            s.advance(flow, 4);
            s.faults.remove("finalize");
            s.advance(flow, NativeEffectFlow.RETRY_BACKOFF_TICKS * (NativeEffectFlow.START_TRIES + 2));
            check(s.breakCalls == 0, fault + ": no marker, no native call");
            check(s.posted("finalize") && !s.sent.get("finalize").get("executed").getAsBoolean(), fault + ": a failure terminal (executed=false) was sent");
            check(s.sent.get("finalize").get("reason").getAsString().matches("START_ACK_LOST|PRECONDITION_FAILED"), fault + ": reason " + s.sent.get("finalize").get("reason"));
            check(flow.slotsUsed() == 0 && !flow.pending(s.actor("Reika").actorId()), fault + ": closed and released: " + s.notes);
        }

        // a START reply that arrives after the operator cancelled never dispatches
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("start", Fault.TRANSPORT);
        s.advance(flow, 4);                       // START is requested and failing; retries are waiting
        Result cancel = flow.cancel(s.owner, "Reika");
        s.faults.remove("start");
        s.advance(flow, NativeEffectFlow.RETRY_BACKOFF_TICKS * 2);
        check(cancel.kind() == Kind.OK && s.breakCalls == 0, "an aborted attempt never dispatches even when START later succeeds");
        check(s.runs.values().iterator().next().state.equals("FAILED_NO_EFFECT"), "settled as FAILED_NO_EFFECT (the marker had become durable): " + s.lastState());

        // a START reply that is slower than the bound is not used
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("start", Fault.TRANSPORT);
        s.advance(flow, 4);
        s.advance(flow, NativeEffectFlow.START_WAIT_TICKS + 5);
        s.faults.remove("start");
        s.advance(flow, NativeEffectFlow.RETRY_BACKOFF_TICKS * 3);
        check(s.breakCalls == 0, "a late marker never dispatches");

        // a stale reply for a different attempt never mints the token
        JsonObject sent = new JsonObject();
        UUID task = UUID.randomUUID();
        sent.addProperty("task_id", task.toString()); sent.addProperty("attempt_id", "a"); sent.addProperty("world_id", "w");
        sent.addProperty("actor_id", "x"); sent.addProperty("session_id", "s");
        JsonObject reply = sent.deepCopy();
        reply.addProperty("state", "EXECUTING"); reply.addProperty("execution_started", true);
        check(NativeEffectFlow.startAck(reply, sent, task) != null, "an exact EXECUTING echo mints the token");
        JsonObject wrong = reply.deepCopy();
        wrong.addProperty("task_id", UUID.randomUUID().toString());
        check(NativeEffectFlow.startAck(wrong, sent, task) == null, "another task's reply does not");
        JsonObject notStarted = reply.deepCopy();
        notStarted.addProperty("execution_started", false);
        check(NativeEffectFlow.startAck(notStarted, sent, task) == null, "execution_started=false does not");
        JsonObject reserved = reply.deepCopy();
        reserved.addProperty("state", "RESERVED");
        check(NativeEffectFlow.startAck(reserved, sent, task) == null, "state RESERVED does not");

        // reserve refusals and unknown outcomes
        s = newSim();
        flow = flowOf(s);
        s.faults.put("reserve", Fault.REFUSE);
        Result refused = confirmBreak(s, flow, "Reika");
        check(refused.kind() == Kind.REJECTED && flow.slotsUsed() == 0 && !flow.pending(s.actor("Reika").actorId()), "a refused reserve releases everything: " + refused);
        s = newSim();
        flow = flowOf(s);
        s.faults.put("reserve", Fault.LOST_REPLY);
        Result lost = confirmBreak(s, flow, "Reika");
        check(lost.kind() == Kind.UNKNOWN, "a lost reserve reply is UNKNOWN, never OK: " + lost);
        s.advance(flow, 2);
        check(s.posted("cancel") && s.breakCalls == 0, "a compensating cancel is sent and nothing is executed");
        check(s.runs.values().iterator().next().state.equals("CANCELLED_NO_EFFECT") && flow.slotsUsed() == 0, "the orphan reservation is cancelled");
        s = newSim();
        flow = flowOf(s);
        s.online = true;
        Result gone = previewBound(s, flow, "Reika", Action.BREAK);
        s.online = false;
        CompletableFuture<Result> c = flow.confirm(s.owner, "Reika", gone.nonce());
        s.drain();
        check(c.join().message().contains("left") || c.join().kind() != Kind.OK, "a requester that left before the reserve reply is cancelled: " + c.join());
        s.drain();
        check(s.breakCalls == 0, "nothing executed for an absent requester");
    }

    static void executorOutcomes() {
        // exception after dispatch: HOLD, never FAILED/NO_EFFECT, never a retry
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(true, false, "minecraft:oak_log", "minecraft:air", "minecraft:air", "minecraft:air", false, List.of(), false, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        check(s.breakCalls == 1 && s.posted("hold") && !s.posted("finalize"), "an executor exception becomes HOLD, not a terminal");
        check(s.sent.get("hold").get("reason").getAsString().equals("EXECUTOR_EXCEPTION"), "reason EXECUTOR_EXCEPTION");
        check(s.runs.values().iterator().next().state.equals("HOLD_IN_DOUBT") && !s.blockClaims.isEmpty(), "the claim stays while the row is held");
        check(flow.pending(s.actor("Reika").actorId()) && flow.pendingProfile(s.actor("Reika").profileId()), "a held NPC stays fenced locally");
        check(flow.slotsUsed() == 0, "the slot is released by a confirmed HOLD (the actor stays fenced)");
        s.advance(flow, 50);
        check(s.breakCalls == 1, "nothing is retried");

        // refused break with nothing changed: a no-effect terminal; with anything changed: HOLD
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(false, false, "minecraft:oak_log", "minecraft:oak_log", "minecraft:air", "minecraft:air", false, List.of(), false, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        check(s.sent.get("finalize").get("result").getAsString().equals("NO_EFFECT") && s.sent.get("finalize").get("executed").getAsBoolean(), "BREAK_REFUSED is NO_EFFECT with executed=true");
        check(s.runs.values().iterator().next().state.equals("FAILED_NO_EFFECT"), "terminal FAILED_NO_EFFECT");
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(false, false, "minecraft:oak_log", "minecraft:oak_log", "minecraft:iron_axe#1", "minecraft:iron_axe#2", false, List.of(), false, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        check(s.posted("hold") && s.sent.get("hold").get("reason").getAsString().equals("AMBIGUOUS_EVIDENCE"), "a refused break that changed the tool is ambiguous");

        // success claimed but the block is unchanged
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(false, true, "minecraft:oak_log", "minecraft:oak_log", "minecraft:air", "minecraft:air", false, List.of(), false, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        check(s.posted("hold"), "destroy=true with the same block still there is HOLD");

        // inventory journal fails AFTER the effect: HOLD with the intended receipt, no second break
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(false, true, "minecraft:oak_log", "minecraft:air", "minecraft:iron_axe#1", "minecraft:iron_axe#2", false, List.of(), false, Journal.FAILED, "token");
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        JsonObject hold = s.sent.get("hold");
        check(hold.get("reason").getAsString().equals("INVENTORY_COMMIT_FAILED") && hold.getAsJsonObject("intended").get("result").getAsString().equals("EFFECT_VERIFIED"), "journal failure after the effect is HOLD with the intended receipt");
        check(hold.getAsJsonObject("intended").getAsJsonObject("evidence").get("journal").getAsString().equals("COMMITTED"), "the intended receipt is the committed one");
        check(s.breakCalls == 1, "the break is never repeated to repair bookkeeping");

        // collateral is typed
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(false, true, "minecraft:oak_log", "minecraft:air", "minecraft:air", "minecraft:air", false, List.of(), true, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        check(s.sent.get("finalize").getAsJsonObject("evidence").get("collateral").getAsString().equals("COLLATERAL"), "collateral changes are reported, not hidden");

        // final precondition fails: permission withdrawn exactly at the START ACK, ownership, journal, tool
        for (String what : new String[]{"policy", "ownership", "journal", "tool"}) {
            s = newSim();
            flow = flowOf(s);
            confirmBreak(s, flow, "Reika");
            s.advance(flow, 3);
            Sim hook = s;
            s.onStart = () -> {
                switch (what) {
                    case "policy" -> hook.policy = NativeActionPolicy.denyAll("revoked at the last moment");
                    case "ownership" -> hook.ownership = "Follow active";
                    case "journal" -> hook.journalProblem = "journal full";
                    case "tool" -> hook.mine.put("Reika", new MineStep(0.25f, "2|minecraft:diamond_axe|", null));
                    default -> { }
                }
            };
            s.advance(flow, 1);
            check(s.breakCalls == 0, what + ": the last-moment check stops the native call");
            check(s.posted("finalize") && !s.sent.get("finalize").get("executed").getAsBoolean(), what + ": executed=false after a marker");
        }
    }

    static void criticalDelivery() {
        // FINALIZE with a lost ACK: identical bytes are retried, UNKNOWN is never OK, and the backend's stored result is adopted by hash
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("finalize", Fault.LOST_REPLY);
        s.advance(flow, 6);
        check(s.notes.isEmpty() || !s.notes.get(0).contains("complete"), "a lost terminal ACK is not reported as success");
        s.advance(flow, NativeEffectFlow.RETRY_BACKOFF_TICKS * (NativeEffectFlow.MAX_TRIES + 1));
        List<String> bytes = s.sentBytes.get("finalize");
        check(bytes.size() == NativeEffectFlow.MAX_TRIES, "the terminal is retried a bounded number of times: " + bytes.size());
        for (String b : bytes) check(b.equals(bytes.get(0)), "every retry re-sends IDENTICAL bytes");
        check(s.notes.get(s.notes.size() - 1).contains("UNKNOWN") && s.breakCalls == 1, "after the bound the result is UNKNOWN and nothing is re-executed");
        check(flow.pending(s.actor("Reika").actorId()) && flow.slotsUsed() == 1, "the unconfirmed attempt keeps its slot and its fence");
        s.faults.remove("finalize");
        CompletableFuture<Result> adopt = flow.reconcile(s.owner, "Reika");
        s.drain();
        check(adopt.isDone() && adopt.join().kind() == Kind.OK && s.posted("status"), "the exact receipt hash is adopted after a status check: " + adopt.join());
        check(s.runs.values().iterator().next().state.equals("EFFECT_VERIFIED") && !flow.pending(s.actor("Reika").actorId()) && flow.slotsUsed() == 0,
                "adopted: fence and slot released");

        // a mismatching hash is never adopted
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("finalize", Fault.LOST_REPLY);
        s.advance(flow, 6 + NativeEffectFlow.RETRY_BACKOFF_TICKS * (NativeEffectFlow.MAX_TRIES + 1));
        s.runs.values().iterator().next().receiptHash = H("something else was recorded");
        CompletableFuture<Result> r = flow.reconcile(s.owner, "Reika");
        s.drain();
        s.drain();
        s.drain();
        // Never adopted: the ledger's own terminal stays untouched. It holds nothing unresolved for the actor, so the stale local
        // fence and slot are dropped instead of dead-ending (reviewer finding 1).
        check(r.isDone() && r.join().kind() == Kind.OK && r.join().state().equals("NO_NATIVE_ATTEMPT")
                && s.runs.values().iterator().next().receiptHash.equals(H("something else was recorded"))
                && !flow.pending(s.actor("Reika").actorId()) && flow.slotsUsed() == 0, "a different recorded receipt is not adopted, the local fence is dropped: " + r.join());

        // the ACK arrives and matches: confirmed
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("finalize", Fault.LOST_REPLY);
        s.faultBudget.put("finalize", 2);
        s.advance(flow, 6 + NativeEffectFlow.RETRY_BACKOFF_TICKS * 4);
        check(s.sentBytes.get("finalize").size() == 3 && s.notes.get(0).contains("EFFECT_VERIFIED"), "a retry that is acknowledged completes: " + s.notes);

        // a wrong or denied ACK is not success
        for (Fault bad : new Fault[]{Fault.BAD_REPLY, Fault.DENY_ACK, Fault.BAD_HASH}) {
            s = newSim();
            flow = flowOf(s);
            confirmBreak(s, flow, "Reika");
            s.faults.put("finalize", bad);
            s.advance(flow, 6);
            check(s.notes.size() == 1 && s.notes.get(0).contains("UNKNOWN"), bad + ": UNKNOWN, not success: " + s.notes);
        }
        // a full critical lane only delays: START and FINALIZE are retried until accepted
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("start", Fault.LANE_FULL);
        s.faultBudget.put("start", 2);
        s.faults.put("finalize", Fault.LANE_FULL);
        s.faultBudget.put("finalize", 2);
        s.advance(flow, 4 + NativeEffectFlow.RETRY_BACKOFF_TICKS * 8);
        check(s.breakCalls == 1 && s.runs.values().iterator().next().state.equals("EFFECT_VERIFIED"), "a full lane delays the critical messages, it does not lose them: " + s.lastState());

        // shutdown: nothing is sent after stopping
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        int before = s.criticalPosts;
        flow.shutdown();
        s.advance(flow, 50);
        check(s.criticalPosts == before && s.breakCalls == 0 && !flow.pending(s.actor("Reika").actorId()), "shutdown dispatches and sends nothing");
    }

    static void pickupOutcomes() {
        UUID entity = UUID.randomUUID();
        // verified
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4 x minecraft:oak_log.");
        Result p = previewBound(s, flow, "Reika", Action.PICKUP);
        check(p.kind() == Kind.OK, "pickup preview: " + p);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        check(s.pickupCalls == 1 && s.idx("POST start") < s.idx("EXEC_PICKUP") && s.idx("EXEC_PICKUP") < s.idx("POST finalize"), "pickup: START, then the native call, then FINALIZE");
        JsonObject e = s.sent.get("finalize").getAsJsonObject("evidence");
        check(s.sent.get("finalize").get("result").getAsString().equals("EFFECT_VERIFIED") && e.get("delta").getAsInt() == 4 && e.get("event_count").getAsInt() == 4, "verified transfer evidence");
        check(s.itemClaims.isEmpty() && s.runs.values().iterator().next().state.equals("EFFECT_VERIFIED"), "claim released at the terminal");

        // partial: full inventory. Vanilla fires no pickup event for a partial add (found by the real GameTest), so 0 is the usual
        // event count; an event equal to the delta is tolerated; any other count disagrees.
        for (int events : new int[]{0, 2}) {
            final int ev = events;
            s = newSim();
            flow = flowOf(s);
            s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
            s.pickupScript = () -> new PickupOutcome(false, 4, 2, 2, ev, 2, 4, Journal.COMMITTED, true, "token");
            p = previewBound(s, flow, "Reika", Action.PICKUP);
            flow.confirm(s.owner, "Reika", p.nonce());
            s.drain();
            check(s.sent.get("finalize").get("result").getAsString().equals("EFFECT_PARTIAL"), "a partial transfer is typed EFFECT_PARTIAL (event " + ev + ")");
        }
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
        s.pickupScript = () -> new PickupOutcome(false, 4, 2, 2, 1, 2, 4, Journal.COMMITTED, true, "token");
        p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        check(s.posted("hold") && !s.posted("finalize"), "a partial transfer with an event that matches nothing is HOLD");

        // nothing happened, every witness quiet: a no-effect terminal with executed=true
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
        s.pickupScript = () -> new PickupOutcome(false, 4, 4, 0, 0, 2, 2, Journal.NOT_REQUIRED, true, "token");
        p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        check(s.sent.get("finalize").get("result").getAsString().equals("NO_EFFECT") && s.sent.get("finalize").get("reason").getAsString().equals("NO_TRANSFER"), "quiet witnesses: NO_TRANSFER");

        // witnesses that disagree are HOLD, never success and never no-effect
        PickupOutcome[] ambiguous = {
                new PickupOutcome(false, 4, 0, 4, 3, 2, 6, Journal.COMMITTED, true, "t"),     // the event disagrees
                new PickupOutcome(false, 4, 0, 3, 4, 2, 5, Journal.COMMITTED, true, "t"),     // the inventory disagrees
                new PickupOutcome(false, 4, 1, 4, 4, 2, 6, Journal.COMMITTED, true, "t"),     // the entity disagrees
                new PickupOutcome(false, 4, 0, 0, 4, 2, 2, Journal.NOT_REQUIRED, true, "t"),  // event but no delta (a mod removed it)
                new PickupOutcome(false, 4, 0, 0, 0, 2, 2, Journal.NOT_REQUIRED, true, "t"),  // the entity vanished, nothing arrived
                new PickupOutcome(false, 4, 0, 4, 4, 2, 6, Journal.COMMITTED, false, null),   // no post-effect snapshot
                new PickupOutcome(true, 4, 0, 0, 0, 2, 2, Journal.NOT_REQUIRED, false, null), // exception
        };
        for (PickupOutcome o : ambiguous) {
            s = newSim();
            flow = flowOf(s);
            s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
            s.pickupScript = () -> o;
            p = previewBound(s, flow, "Reika", Action.PICKUP);
            flow.confirm(s.owner, "Reika", p.nonce());
            s.drain();
            check(s.posted("hold") && !s.posted("finalize"), "disagreeing witnesses are HOLD: " + o);
            check(s.pickupCalls == 1, "never repeated");
        }

        // journal failure after the transfer: HOLD with the intended receipt; repair is persist-only
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
        s.pickupScript = () -> new PickupOutcome(false, 4, 0, 4, 4, 2, 6, Journal.FAILED, true, "token");
        p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        check(s.sent.get("hold").get("reason").getAsString().equals("INVENTORY_COMMIT_FAILED"), "journal failure after a pickup is HOLD");
        s.repairResult = Repair.REFUSED;
        CompletableFuture<Result> refusedRepair = flow.reconcile(s.owner, "Reika");
        s.drain();
        s.drain();
        check(refusedRepair.join().kind() == Kind.REJECTED && s.pickupCalls == 1 && !s.posted("repair"), "a refused repair changes nothing and repeats nothing: " + refusedRepair.join());
        s.repairResult = Repair.DONE;
        CompletableFuture<Result> repaired = flow.reconcile(s.owner, "Reika");
        s.drain();
        s.drain();
        check(repaired.join().kind() == Kind.OK && s.posted("repair") && s.pickupCalls == 1, "persist-only repair: " + repaired.join());
        check(s.runs.values().iterator().next().state.equals("EFFECT_VERIFIED") && s.itemClaims.isEmpty(), "terminal recorded from the stored intended receipt");
        check(!flow.pending(s.actor("Reika").actorId()), "the fence is released after the repair");

        // the item is gone at the last moment
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
        p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        check(s.pickupCalls == 1, "baseline pickup runs");
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
        p = previewBound(s, flow, "Reika", Action.PICKUP);
        s.reach.put("Reika", "gone: the item no longer exists");
        CompletableFuture<Result> c = flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        check(c.join().kind() == Kind.REJECTED && s.pickupCalls == 0 && s.runs.isEmpty(), "an item that vanished before confirm is refused without a reservation");
    }

    static void capacityAndMultiNpc() {
        // same block, adjacent blocks, distant independent work, 3/5/10 NPCs in parallel
        for (int npcs : new int[]{3, 5, 10}) {
            Sim s = newSim();
            NativeEffectFlow flow = flowOf(s);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < npcs; i++) names.add("N" + i);
            Result any = flow.preview(s.owner, "N0", Action.BREAK).join();
            s.drain();
            for (int i = 0; i < npcs; i++) {
                String name = names.get(i);
                s.nextBlock = new Pick<>(log(10 + i), "", "break at " + i);                  // adjacent / distinct cells
                Result p = flow.preview(s.owner, name, Action.BREAK).join();
                check(p.kind() == Kind.OK, npcs + " NPCs: preview " + i + " " + p);
                CompletableFuture<Result> c = flow.confirm(s.owner, name, p.nonce());
                s.drain();
                check(c.join().kind() == Kind.OK, npcs + " NPCs: accepted " + i + ": " + c.join());
            }
            check(flow.slotsUsed() == npcs && s.runs.size() == npcs, npcs + " NPCs hold " + npcs + " slots and " + npcs + " distinct reservations");
            int queued = 0;
            s.advance(flow, 6);
            check(s.breakCalls == npcs, npcs + " NPCs each break their own block: " + s.breakCalls);
            check(flow.slotsUsed() == 0 && s.blockClaims.isEmpty() && s.notes.size() == npcs, npcs + " NPCs: all slots and claims released: " + flow.slotsUsed());
        }
        // same block: the second claimant is refused by the ledger and holds nothing
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        s.nextBlock = new Pick<>(log(10), "", "break");
        Result a = confirmBreak(s, flow, "Reika");
        Result p = flow.preview(s.owner, "Mika", Action.BREAK).join();
        CompletableFuture<Result> b = flow.confirm(s.owner, "Mika", p.nonce());
        s.drain();
        check(a.kind() == Kind.OK && b.join().kind() == Kind.REJECTED && b.join().message().contains("refused"), "same block: exactly one wins: " + b.join());
        check(!flow.pending(s.actor("Mika").actorId()) && flow.slotsUsed() == 1, "the loser holds no fence and no slot");
        s.advance(flow, 6);
        check(s.breakCalls == 1, "one break");
        // item contention
        s = newSim();
        flow = flowOf(s);
        UUID entity = UUID.randomUUID();
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up");
        Result p1 = previewBound(s, flow, "Reika", Action.PICKUP);
        CompletableFuture<Result> c1 = flow.confirm(s.owner, "Reika", p1.nonce());
        Result p2 = flow.preview(s.owner, "Mika", Action.PICKUP).join();
        CompletableFuture<Result> c2 = flow.confirm(s.owner, "Mika", p2.nonce());
        s.drain();
        check(c1.join().kind() == Kind.OK && c2.join().kind() == Kind.REJECTED, "one item entity, one owner: " + c1.join() + " / " + c2.join());

        // slots: the 17th concurrent attempt is refused with BUSY and sends nothing
        s = newSim();
        flow = flowOf(s);
        Result first = flow.preview(s.owner, "S0", Action.BREAK).join();
        s.drain();
        for (int i = 0; i < NativeEffectFlow.SLOTS; i++) {
            s.nextBlock = new Pick<>(log(-50 + i), "", "x");
            Result pp = flow.preview(s.owner, "S" + i, Action.BREAK).join();
            CompletableFuture<Result> cc = flow.confirm(s.owner, "S" + i, pp.nonce());
            s.drain();
            check(cc.join().kind() == Kind.OK, "slot " + i);
        }
        s.nextBlock = new Pick<>(log(70), "", "x");
        Result overflow = flow.preview(s.owner, "Extra", Action.BREAK).join();
        check(overflow.kind() == Kind.BUSY && flow.slotsUsed() == NativeEffectFlow.SLOTS, "slot exhaustion is BUSY at admission: " + overflow);
        int reserves = s.posts("reserve");
        s.advance(flow, 6);
        check(s.breakCalls == NativeEffectFlow.SLOTS && flow.slotsUsed() == 0, "all admitted attempts complete and release their slots");
        check(s.posts("reserve") == reserves, "the refused attempt sent nothing");

        // slots are re-checked at confirm: previews taken while room existed cannot overrun the slots
        s = newSim();
        flow = flowOf(s);
        previewBound(s, flow, "Extra", Action.BREAK);
        s.nextBlock = new Pick<>(log(90), "", "x");
        Result late = flow.preview(s.owner, "Extra", Action.BREAK).join();
        check(late.kind() == Kind.OK, "room existed at preview time");
        for (int i = 0; i < NativeEffectFlow.SLOTS; i++) {
            s.nextBlock = new Pick<>(log(-50 + i), "", "x");
            Result pp = flow.preview(s.owner, "F" + i, Action.BREAK).join();
            flow.confirm(s.owner, "F" + i, pp.nonce());
            s.drain();
        }
        CompletableFuture<Result> lateConfirm = flow.confirm(s.owner, "Extra", late.nonce());
        s.drain();
        check(lateConfirm.join().kind() == Kind.BUSY && s.posts("reserve") == NativeEffectFlow.SLOTS && flow.slotsUsed() == NativeEffectFlow.SLOTS,
                "a confirm after the slots filled is BUSY and sends nothing: " + lateConfirm.join());

        // lane invariant under load: every attempt has at most one critical message outstanding
        s = newSim();
        flow = flowOf(s);
        s.faults.put("finalize", Fault.LOST_REPLY);
        Result warm = flow.preview(s.owner, "L0", Action.BREAK).join();
        s.drain();
        for (int i = 0; i < 10; i++) {
            s.nextBlock = new Pick<>(log(i), "", "x");
            Result pp = flow.preview(s.owner, "L" + i, Action.BREAK).join();
            flow.confirm(s.owner, "L" + i, pp.nonce());
            s.drain();
        }
        for (int t = 0; t < 400; t++) {
            s.advance(flow, 1);
            check(flow.outstanding() <= NativeEffectFlow.SLOTS, "outstanding critical messages stay bounded by the slots");
        }
        check(flow.slotsUsed() == 10, "unconfirmed attempts keep their slots (back-pressure, not unbounded growth)");
    }

    static void reconcileAndRepair() {
        // no native attempt: the accepted no-op reconcile path is used
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        Result warm = flow.preview(s.owner, "Reika", Action.BREAK).join();
        s.drain();
        CompletableFuture<Result> none = flow.reconcile(s.owner, "Reika");
        s.drain();
        s.drain();
        check(none.join().state().equals("NO_NATIVE_ATTEMPT"), "no native row: delegated: " + none.join());
        check(flow.reconcile(UUID.randomUUID(), "Reika").join().state().equals("NO_NATIVE_ATTEMPT"), "a stranger is not a native requester");

        // restart: local evidence is gone; judge by observation. Break: unchanged / changed
        for (String sha : new String[]{"same", "other"}) {
            s = newSim();
            flow = flowOf(s);
            s.breakScript = () -> new BreakOutcome(true, false, "minecraft:oak_log", "minecraft:air", "x", "x", false, List.of(), false, Journal.NOT_REQUIRED, null);
            confirmBreak(s, flow, "Reika");
            s.advance(flow, 6);
            flow.shutdown();                                         // the process is gone; the ledger keeps the HOLD
            NativeEffectFlow fresh = flowOf(s);
            Result pre = previewBound(s, fresh, "Reika", Action.BREAK);
            s.observedBlock = sha.equals("same") ? H("state-minecraft:oak_log") : H("changed");
            CompletableFuture<Result> rec = fresh.reconcile(s.owner, "Reika");
            s.drain();
            s.drain();
            check(rec.join().kind() == Kind.OK, "break reconcile " + sha + ": " + rec.join());
            check(s.runs.values().iterator().next().state.equals(sha.equals("same") ? "RECONCILED_PERSISTED_STATE_UNCHANGED" : "RECONCILED_EFFECT_OBSERVED"),
                    "break reconcile " + sha + " ledger: " + s.lastState());
            check(!s.sent.get("reconcile").get("evidence_class").getAsString().equals("NO_EFFECT"), "never claims NO_EFFECT");
        }
        // pickup after a restart: the 2x2 matrix
        UUID entity = UUID.randomUUID();
        String[][] matrix = {{"PRESENT", "ABSENT", "RECONCILED_PERSISTED_TRANSFER"}, {"ABSENT", "PRESENT_UNCHANGED", "RECONCILED_PERSISTED_STATE_UNCHANGED"},
                {"PRESENT", "PRESENT_UNCHANGED", ""}, {"ABSENT", "ABSENT", ""}, {"ABSENT", "PRESENT_CHANGED", ""}};
        for (String[] row : matrix) {
            s = newSim();
            flow = flowOf(s);
            s.nextItem = new Pick<>(item(entity, 4), "", "pick up");
            s.pickupScript = () -> new PickupOutcome(true, 4, 0, 0, 0, 2, 2, Journal.NOT_REQUIRED, false, null);
            Result pv = previewBound(s, flow, "Reika", Action.PICKUP);
            flow.confirm(s.owner, "Reika", pv.nonce());
            s.drain();
            flow.shutdown();
            NativeEffectFlow fresh = flowOf(s);
            previewBound(s, fresh, "Reika", Action.PICKUP);
            s.observation = new PickupObservation(row[0], row[1]);
            CompletableFuture<Result> rec = fresh.reconcile(s.owner, "Reika");
            s.drain();
            s.drain();
            if (row[2].isEmpty()) {
                check(rec.join().kind() == Kind.REJECTED && !s.posted("reconcile") && s.runs.values().iterator().next().state.equals("HOLD_IN_DOUBT"),
                        "journal " + row[0] + " / item " + row[1] + " stays HOLD: " + rec.join());
            } else {
                check(rec.join().kind() == Kind.OK && s.runs.values().iterator().next().state.equals(row[2]), "journal " + row[0] + " / item " + row[1] + " -> " + row[2] + ": " + rec.join() + " " + s.lastState());
            }
        }
        // a HOLD from another requester cannot be reconciled by this one
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(true, false, "minecraft:oak_log", "minecraft:air", "x", "x", false, List.of(), false, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        UUID second = UUID.randomUUID();
        s.policy = Sim.allow(second, "[\"minecraft:oak_log\"]", "[\"minecraft:oak_log\"]");
        check(flow.reconcile(second, "Reika").join().kind() == Kind.DENIED, "only the requester of the held attempt may reconcile it");
    }

    /** Reviewer findings 1, 2 and 5: no dead ends after an unexpected error, a settled-by-sweep ledger, or an unacknowledged HOLD. */
    static void recoveryDeadEnds() {
        // 2a: a runtime error inside the final precondition (after a good START ACK) must not strand the attempt
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.atOwnership = () -> { throw new IllegalStateException("boom before the native call"); };
        s.advance(flow, 400);
        check(s.breakCalls == 0, "nothing was executed");
        check(s.lastState().equals("FAILED_NO_EFFECT") && flow.slotsUsed() == 0 && !flow.pending(s.actor("Reika").actorId()),
                "an error before the native call ends as FAILED_NO_EFFECT with the slot and the fence released: " + s.lastState() + " slots " + flow.slotsUsed());

        // 2b: a runtime error from the executor itself after the marker is a HOLD, never NO_EFFECT
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> { throw new IllegalStateException("boom inside the native call"); };
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 400);
        check(s.breakCalls == 1 && s.lastState().equals("HOLD_IN_DOUBT") && flow.slotsUsed() == 0, "an error around the native call is HOLD_IN_DOUBT: " + s.lastState());

        // 1b: a restart sweep settled the row before START; the FINALIZE is refused with different evidence. Rec. must not dead-end.
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        Sim.Run swept = s.runs.values().iterator().next();
        s.finish(swept, "FAILED_NO_EFFECT", "swept-by-the-ledger");
        s.advance(flow, 400);
        UUID actor = s.actor("Reika").actorId();
        check(flow.pending(actor) && flow.slotsUsed() == 1, "the attempt is unconfirmed: fenced, slot kept");
        CompletableFuture<Result> rec = flow.reconcile(s.owner, "Reika");
        s.drain();
        s.drain();
        s.drain();
        check(rec.join().kind() == Kind.OK && rec.join().state().equals("NO_NATIVE_ATTEMPT"), "reconcile falls through to the ledger's own view: " + rec.join());
        check(!flow.pending(actor) && flow.slotsUsed() == 0, "the fence and the completion slot are released");
        check(previewBound(s, flow, "Reika", Action.BREAK).kind() == Kind.OK, "the NPC accepts a new action again");

        // 5: the HOLD was never acknowledged and the journal commit failed: Rec. resends the identical HOLD before it repairs
        UUID entity = UUID.randomUUID();
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up 4.");
        s.pickupScript = () -> new PickupOutcome(false, 4, 0, 4, 4, 2, 6, Journal.FAILED, true, "token");
        s.faults.put("hold", Fault.LOST_REPLY);
        s.faultBudget.put("hold", NativeEffectFlow.MAX_TRIES);
        Result p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        s.advance(flow, NativeEffectFlow.RETRY_BACKOFF_TICKS * (NativeEffectFlow.MAX_TRIES + 1));
        int holds = s.sentBytes.get("hold").size();
        check(flow.pending(s.actor("Reika").actorId()) && !s.posted("repair"), "the HOLD is unacknowledged and the attempt is fenced");
        CompletableFuture<Result> repaired = flow.reconcile(s.owner, "Reika");
        s.drain();
        s.drain();
        s.drain();
        check(s.sentBytes.get("hold").size() > holds, "the HOLD was resent before the repair");
        check(repaired.join().kind() == Kind.OK && s.posted("repair") && s.pickupCalls == 1, "then the persist-only repair ran: " + repaired.join());
        check(s.runs.values().iterator().next().state.equals("EFFECT_VERIFIED") && !flow.pending(s.actor("Reika").actorId()) && flow.slotsUsed() == 0, "terminal recorded, fence and slot released");
    }

    // ---------------------------------------------------------------------------------------------------- wire contract
    private static final java.util.regex.Pattern UUID_RE = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    static String shape(com.google.gson.JsonElement e) {
        if (e.isJsonObject()) {
            StringBuilder b = new StringBuilder("{");
            new java.util.TreeSet<>(e.getAsJsonObject().keySet()).forEach(k -> b.append(k).append(':').append(shape(e.getAsJsonObject().get(k))).append(','));
            return b.append('}').toString();
        }
        if (e.isJsonArray()) {
            StringBuilder b = new StringBuilder("[");
            for (com.google.gson.JsonElement i : e.getAsJsonArray()) b.append(shape(i)).append(',');
            return b.append(']').toString();
        }
        if (e.isJsonNull()) return "null";
        com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "bool:" + p.getAsBoolean();
        if (p.isNumber()) return "number";
        String v = p.getAsString();
        return UUID_RE.matcher(v).matches() ? "uuid" : v.matches("[0-9a-f]{64}") ? "hex64" : "'" + v + "'";
    }

    /** One scenario: its full ordered trace, with the Java canonical digest of every body that the ledger hashes. */
    static JsonObject chainOf(Sim s) {
        JsonObject chain = new JsonObject();
        JsonArray steps = new JsonArray();
        for (String[] t : s.trace) {
            JsonObject step = new JsonObject();
            JsonObject body = com.google.gson.JsonParser.parseString(t[1]).getAsJsonObject();
            step.addProperty("route", t[0]);
            step.add("body", body);
            step.addProperty("reply_state", t[2]);
            if (t[0].equals("finalize") || t[0].equals("reconcile") || t[0].equals("cancel") || t[0].equals("hold")) step.addProperty("digest", NativeEffectFlow.digest(body));
            if (t[0].equals("hold") && body.getAsJsonObject("intended").size() > 0) step.addProperty("intended_digest", NativeEffectFlow.digest(body.getAsJsonObject("intended")));
            steps.add(step);
        }
        chain.add("steps", steps);
        return chain;
    }

    static void wireFixture() {
        JsonObject all = new JsonObject();
        all.addProperty("contract", "native_effect_wire_v1");
        // 1. a verified break
        Sim s = newSim();
        NativeEffectFlow flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        all.add("break_verified", chainOf(s));
        // 2. a break whose executor threw: HOLD, then released from observed state after a restart
        s = newSim();
        flow = flowOf(s);
        s.breakScript = () -> new BreakOutcome(true, false, "minecraft:oak_log", "minecraft:air", "x", "x", false, List.of(), false, Journal.NOT_REQUIRED, null);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 6);
        flow.shutdown();
        NativeEffectFlow fresh = flowOf(s);
        previewBound(s, fresh, "Reika", Action.BREAK);
        s.observedBlock = H("changed");
        fresh.reconcile(s.owner, "Reika");
        s.drain();
        all.add("break_hold_reconciled", chainOf(s));
        // 3. a partial pickup (no pickup event: vanilla fires none for a partial add)
        s = newSim();
        flow = flowOf(s);
        UUID entity = UUID.randomUUID();
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up");
        s.pickupScript = () -> new PickupOutcome(false, 4, 2, 2, 0, 2, 4, Journal.COMMITTED, true, "token");
        Result p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        all.add("pickup_partial", chainOf(s));
        // 4. a verified pickup whose journal commit failed after the transfer, repaired persist-only
        s = newSim();
        flow = flowOf(s);
        s.nextItem = new Pick<>(item(entity, 4), "", "pick up");
        s.pickupScript = () -> new PickupOutcome(false, 4, 0, 4, 4, 2, 6, Journal.FAILED, true, "token");
        p = previewBound(s, flow, "Reika", Action.PICKUP);
        flow.confirm(s.owner, "Reika", p.nonce());
        s.drain();
        flow.reconcile(s.owner, "Reika");
        s.drain();
        all.add("pickup_journal_failed_repaired", chainOf(s));
        // 5. cancelled while mining; 6. a failure before the effect after the marker; 7. a lost terminal ACK adopted by hash
        s = newSim();
        flow = flowOf(s);
        s.mine.put("Reika", new MineStep(0.01f, "0|minecraft:air|", null));
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 2);
        flow.cancel(s.owner, "Reika");
        s.drain();
        all.add("break_cancelled", chainOf(s));
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.advance(flow, 3);
        final Sim sf = s;
        s.onStart = () -> sf.policy = NativeActionPolicy.denyAll("revoked at the last moment");
        s.advance(flow, 3);
        all.add("break_failed_before_effect", chainOf(s));
        s = newSim();
        flow = flowOf(s);
        confirmBreak(s, flow, "Reika");
        s.faults.put("finalize", Fault.LOST_REPLY);
        s.advance(flow, 6 + NativeEffectFlow.RETRY_BACKOFF_TICKS * (NativeEffectFlow.MAX_TRIES + 1));
        s.faults.remove("finalize");
        flow.reconcile(s.owner, "Reika");
        s.drain();
        all.add("break_terminal_adopted_by_hash", chainOf(s));

        java.nio.file.Path path = java.nio.file.Path.of("..", "backend", "tests", "fixtures", "native_effect_wire_v1.json");
        try {
            if ("1".equals(System.getenv("NATIVE_FIXTURE_WRITE"))) {
                java.nio.file.Files.writeString(path, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(all) + "\n");
                System.out.println("wrote " + path.toAbsolutePath());
                return;
            }
            check(java.nio.file.Files.exists(path), "the committed wire fixture exists: " + path.toAbsolutePath());
            JsonObject committed = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            for (String name : all.keySet()) {
                if (name.equals("contract")) continue;
                check(committed.has(name), "fixture has chain " + name);
                JsonArray now = all.getAsJsonObject(name).getAsJsonArray("steps"), then = committed.getAsJsonObject(name).getAsJsonArray("steps");
                check(now.size() == then.size(), name + ": same number of calls (" + now.size() + " vs " + then.size() + ")");
                for (int i = 0; i < now.size(); i++) {
                    JsonObject a = now.get(i).getAsJsonObject(), b = then.get(i).getAsJsonObject();
                    check(a.get("route").equals(b.get("route")) && a.get("reply_state").equals(b.get("reply_state")), name + "[" + i + "] route/state " + a.get("route") + " " + a.get("reply_state"));
                    check(shape(a.get("body")).equals(shape(b.get("body"))), name + "[" + i + "] body shape " + a.get("route") + "\n" + shape(a.get("body")) + "\n" + shape(b.get("body")));
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static void canonicalJson() {
        JsonObject o = new JsonObject();
        o.addProperty("b", 1);
        o.addProperty("a", "x\u00e9\"\\\n\u0001~\u007f");
        o.addProperty("flag", true);
        JsonArray arr = new JsonArray();
        arr.add(3); arr.add("z");
        o.add("list", arr);
        String expected = "{\"a\":\"x\\u00e9\\\"\\\\\\n\\u0001~\\u007f\",\"b\":1,\"flag\":true,\"list\":[3,\"z\"]}";
        check(NativeEffectFlow.canonical(o).equals(expected), "canonical JSON matches json.dumps(sort_keys, separators, ensure_ascii): " + NativeEffectFlow.canonical(o));
        JsonObject big = new JsonObject();
        big.addProperty("n", 12345678901234L);
        check(NativeEffectFlow.canonical(big).equals("{\"n\":12345678901234}"), "large integers stay integers");
    }
}
