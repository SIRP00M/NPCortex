package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.sirp00m.npcortex.ManagerCore.Http;
import io.github.sirp00m.npcortex.ManagerCore.Npc;
import io.github.sirp00m.npcortex.ManagerProtocol.*;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** G10: the Manager state machine against a scripted fake server and backend (no Minecraft, no network). */
public final class ManagerCoreSelfTest {
    private static int n;

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError(what);
    }

    interface Backend { CompletableFuture<Http> call(String method, String path, JsonObject body); }

    static class FakeHost implements ManagerCore.Host {
        final UUID world = UUID.randomUUID();
        final List<Npc> npcs = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        final List<JsonObject> bodies = new ArrayList<>();
        final List<ManagerProtocol.ToClient> replies = new ArrayList<>();
        final ArrayDeque<Runnable> serverQueue = new ArrayDeque<>();
        final List<UUID> allowed = new ArrayList<>();
        boolean deferToServerThread;
        String spawnError;
        int spawns;
        Backend backend = (m, p, b) -> CompletableFuture.completedFuture(new Http(500, new JsonObject()));
        final List<Object[]> worldCalls = new ArrayList<>();
        java.util.function.Supplier<CompletableFuture<WorldEffectFlow.Result>> worldScript =
                () -> CompletableFuture.completedFuture(new WorldEffectFlow.Result(WorldEffectFlow.Kind.OK, "done", "NO_EFFECT_VERIFIED"));

        @Override public boolean authorized(UUID player) { return allowed.contains(player); }
        @Override public List<Npc> npcs() { return List.copyOf(npcs); }
        @Override public UUID worldId() { return world; }
        @Override public String createNpc(UUID player, String name) {
            spawns++;
            if (spawnError != null) return spawnError;
            npcs.add(new Npc(name, UUID.randomUUID(), "minecraft:overworld", true, C1IdentityData.MODE_CUSTOM, ""));
            return null;
        }
        @Override public void reply(UUID player, ManagerProtocol.ToClient message) { replies.add(message); }
        @Override public void onServerThread(Runnable task) {
            if (deferToServerThread) serverQueue.add(task); else task.run();
        }
        @Override public CompletableFuture<Http> http(String method, String path, JsonObject body) {
            requests.add(method + " " + path);
            bodies.add(body);
            return backend.call(method, path, body);
        }
        @Override public void log(String message) { }
        @Override public CompletableFuture<WorldEffectFlow.Result> worldAction(UUID requester, String npcName, boolean reconcile) {
            worldCalls.add(new Object[]{requester, npcName, reconcile});
            return worldScript.get();
        }
        void drain() { while (!serverQueue.isEmpty()) serverQueue.poll().run(); }
        ManagerProtocol.ToClient last() { return replies.get(replies.size() - 1); }
    }

    static Http ok(int status, String json) { return new Http(status, JsonParser.parseString(json).getAsJsonObject()); }

    static CompletableFuture<Http> done(int status, String json) { return CompletableFuture.completedFuture(ok(status, json)); }

    static CompletableFuture<Http> fails(Throwable t) { return CompletableFuture.failedFuture(new CompletionException(t)); }

    static String binding(int revision, String sha) {
        return "{\"profile\":{\"revision\":" + revision + ",\"display_name\":\"Reika\",\"composition\":\"structured\",\"source\":\"inline\",\"content_sha256\":\"" + sha + "\"}}";
    }

    /** A cooperative backend: world 201, actor 201 with binding, export 200 with the same revision and hash. */
    static Backend happy(int revision, String sha) {
        return (method, path, body) -> {
            if (path.equals("/v1/worlds")) return done(201, "{}");
            if (path.equals("/v1/actors")) return done(201, binding(revision, sha));
            if (path.endsWith("/profile") && method.equals("GET"))
                return done(200, "{\"revision\":" + revision + ",\"content_sha256\":\"" + sha + "\",\"source\":\"inline\",\"composition\":\"structured\"}");
            return done(500, "{}");
        };
    }

    static final ManagerProtocol.Draft DRAFT = ManagerProtocol.Draft.structured("Reika", "Calm and precise.", "Formal.", "Snowy village.");

    static Row byName(ListRep list, String name) {
        return list.rows().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    static OpRep op(FakeHost host) { return (OpRep) host.last(); }

    public static void main(String[] args) throws Exception {
        UUID op1 = UUID.randomUUID(), op2 = UUID.randomUUID(), stranger = UUID.randomUUID();

        // ---------------- permissions
        FakeHost host = new FakeHost();
        ManagerCore core = new ManagerCore(host);
        core.handle(stranger, new CreateReq(1, "Reika", DRAFT));
        check(host.last() instanceof Denied, "unauthorized player is denied");
        check(host.spawns == 0 && host.requests.isEmpty(), "denied request spawns nothing and calls no backend");
        core.handle(stranger, new ListReq(2));
        check(host.last() instanceof Denied && host.requests.isEmpty(), "denied list does not touch the backend");
        core.handle(stranger, new SaveReq(3, "Reika", 1, DRAFT));
        check(host.last() instanceof Denied, "denied save");

        // ---------------- list: statuses
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        Npc custom = new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", "");
        Npc yui = new Npc("Yui", UUID.randomUUID(), "minecraft:overworld", true, "", "");
        Npc fresh = new Npc("Sakura", UUID.randomUUID(), "minecraft:overworld", false, "default", "");
        host.npcs.addAll(List.of(custom, yui, fresh));
        String listJson = "{\"world_id\":\"w\",\"actors\":[" +
                "{\"actor_id\":\"" + custom.actorId() + "\",\"character_id\":\"@profile\",\"profile\":{\"revision\":3,\"display_name\":\"Reika\",\"composition\":\"structured\",\"source\":\"inline\"}}," +
                "{\"actor_id\":\"" + yui.actorId() + "\",\"character_id\":\"yui\",\"profile\":null}]}";
        host.backend = (m, p, b) -> done(200, listJson);
        core.handle(op1, new ListReq(10));
        ListRep list = (ListRep) host.last();
        check(host.requests.get(0).equals("GET /v1/worlds/" + host.world + "/profiles"), "list uses the server-derived world id");
        check(list.status() == Status.OK && list.rows().size() == 3, "three rows");
        Row rReika = byName(list, "Reika"), rSakura = byName(list, "Sakura"), rYui = byName(list, "Yui");
        check(rReika.state() == RowState.OK && rReika.revision() == 3, "native profile OK rev 3");
        check(rSakura.state() == RowState.NOT_REGISTERED, "default NPC not yet registered");
        check(rYui.state() == RowState.LEGACY && rYui.detail().contains("read-only"), "legacy Yui read-only");
        check(!rSakura.active(), "inactive flag carried");

        host.backend = (m, p, b) -> done(404, "{\"error\":\"World is not registered\",\"code\":\"registry_not_found\"}");
        core.handle(op1, new ListReq(11));
        list = (ListRep) host.last();
        check(list.status() == Status.OK && list.rows().stream().allMatch(r -> r.state() == RowState.NOT_REGISTERED), "unregistered world: nothing registered");
        host.backend = (m, p, b) -> fails(new ConnectException("refused"));
        core.handle(op1, new ListReq(12));
        list = (ListRep) host.last();
        check(list.status() == Status.OFFLINE && list.rows().stream().allMatch(r -> r.state() == RowState.OFFLINE), "offline: status unknown, NPCs still listed");
        host.backend = (m, p, b) -> done(403, "{\"error\":\"Local token required\",\"code\":\"forbidden\"}");
        core.handle(op1, new ListReq(13));
        check(((ListRep) host.last()).status() == Status.ERROR && ((ListRep) host.last()).message().contains("credential"), "403 reported as credential problem");
        host.backend = (m, p, b) -> CompletableFuture.failedFuture(new IllegalStateException("Local credential unavailable: start the backend once"));
        core.handle(op1, new ListReq(14));
        check(((ListRep) host.last()).message().startsWith("Local credential"), "missing local secret is explained");

        // ---------------- create: success
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = happy(1, "abc");
        core.handle(op1, new CreateReq(20, "Reika", DRAFT));
        OpRep rep = op(host);
        check(rep.status() == Status.OK && rep.revision() == 1 && rep.row() != null && rep.row().state() == RowState.OK, "create ok -> revision 1");
        check(host.spawns == 1, "spawned exactly once");
        check(host.requests.equals(List.of("POST /v1/worlds", "POST /v1/actors", "GET /v1/actors/" + host.npcs.get(0).actorId() + "/profile")), "world, actor+profile, read-back");
        JsonObject worldBody = host.bodies.get(0), actorBody = host.bodies.get(1);
        check(worldBody.get("name").getAsString().equals(C1IdentityData.backendWorldName(host.world)), "backend world name matches the C2 contract");
        check(actorBody.get("actor_id").getAsString().equals(host.npcs.get(0).actorId().toString()), "actor id comes from server state");
        check(actorBody.get("world_id").getAsString().equals(host.world.toString()), "world id comes from server state");
        check(!actorBody.has("character_id") && !actorBody.has("preset") && !actorBody.has("default_profile"), "only the inline profile selector is sent");
        JsonObject sent = actorBody.getAsJsonObject("profile");
        check(sent.get("composition").getAsString().equals("structured") && sent.getAsJsonObject("sections").get("personality").getAsString().equals("Calm and precise."), "profile json shape");
        check(!sent.has("language") && !sent.has("system_prompt"), "no language is sent (the backend keeps/defaults it), no raw prompt in structured mode");

        // ---------------- create: failure classes keep the NPC visible and never claim success
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> fails(new ConnectException("refused"));
        core.handle(op1, new CreateReq(21, "Reika", DRAFT));
        rep = op(host);
        check(rep.status() == Status.OFFLINE && rep.row() != null && rep.row().state() == RowState.NOT_REGISTERED && rep.message().contains("NOT registered"), "offline: NPC created, profile NOT registered");
        check(host.spawns == 1 && host.npcs.size() == 1, "local creation does not depend on the backend");
        host.requests.clear();
        host.backend = happy(1, "abc");
        core.handle(op1, new RegisterReq(22, "Reika", DRAFT));
        rep = op(host);
        check(rep.status() == Status.OK && rep.op() == Op.REGISTER && rep.row().state() == RowState.OK, "explicit retry registers the pending NPC");
        check(host.spawns == 1, "retry never spawns again");
        host.backend = (m, p, b) -> m.equals("POST") && p.equals("/v1/actors") ? done(201, binding(1, "abc")) : p.endsWith("/profile") ? done(200, "{\"revision\":1,\"content_sha256\":\"abc\"}") : done(201, "{}");
        core.handle(op1, new RegisterReq(23, "Reika", DRAFT));
        check(op(host).status() == Status.OK, "re-registering an identical profile is idempotent");

        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> p.equals("/v1/worlds") ? done(201, "{}") : done(400, "{\"error\":\"personality must contain 1-1500 characters\",\"code\":\"invalid_profile\"}");
        core.handle(op1, new CreateReq(24, "Reika", DRAFT));
        rep = op(host);
        check(rep.status() == Status.REJECTED && rep.message().contains("personality") && rep.row() != null, "backend validation error surfaced, NPC kept pending");

        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> p.equals("/v1/worlds") ? done(201, "{}") : done(409, "{\"error\":\"actor_id already registered with a different profile\",\"code\":\"identity_conflict\"}");
        core.handle(op1, new CreateReq(25, "Reika", DRAFT));
        check(op(host).status() == Status.CONFLICT && op(host).message().contains("different profile"), "registration conflict is not hidden");

        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> p.equals("/v1/worlds") ? done(201, "{}") : fails(new HttpTimeoutException("timed out"));
        core.handle(op1, new CreateReq(26, "Reika", DRAFT));
        check(op(host).status() == Status.UNKNOWN && op(host).message().contains("UNKNOWN"), "timeout after sending is UNKNOWN, not FAILED");

        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> fails(new HttpConnectTimeoutException("connect timed out"));
        core.handle(op1, new CreateReq(27, "Reika", DRAFT));
        check(op(host).status() == Status.OFFLINE, "connect timeout means the request was never delivered");

        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> p.endsWith("/profile") && m.equals("GET")
                ? done(200, "{\"revision\":1,\"content_sha256\":\"DIFFERENT\"}") : p.equals("/v1/worlds") ? done(201, "{}") : done(201, binding(1, "abc"));
        core.handle(op1, new CreateReq(28, "Reika", DRAFT));
        check(op(host).status() == Status.ERROR && op(host).message().contains("does not match"), "read-back mismatch is not reported as success");

        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.backend = (m, p, b) -> p.equals("/v1/worlds") ? done(201, "{}") : p.equals("/v1/actors") ? done(201, "{}") : done(500, "{}");
        core.handle(op1, new CreateReq(29, "Reika", DRAFT));
        check(op(host).status() == Status.ERROR, "missing binding in the reply is an error");

        // ---------------- create: refused before anything happens
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", ""));
        core.handle(op1, new CreateReq(30, "reika", DRAFT));
        check(op(host).status() == Status.REJECTED && op(host).message().contains("already exists") && host.spawns == 0 && host.requests.isEmpty(), "existing name (any case) is never re-bound");
        core.handle(op1, new CreateReq(31, "bad name", DRAFT));
        check(op(host).status() == Status.REJECTED && host.spawns == 0, "invalid name rejected");
        core.handle(op1, new CreateReq(32, "Mika", ManagerProtocol.Draft.structured("Mika", "   ", "", "")));
        check(op(host).status() == Status.REJECTED && host.spawns == 0 && host.requests.isEmpty(), "invalid draft rejected before spawning");
        host.spawnError = "Spawn FAILED: IllegalStateException";
        core.handle(op1, new CreateReq(33, "Mika", DRAFT));
        check(op(host).status() == Status.ERROR && op(host).message().contains("NOT fully created") && host.requests.isEmpty(), "spawn failure stops before the backend");

        // ---------------- register guards
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.npcs.add(new Npc("Sakura", UUID.randomUUID(), "minecraft:overworld", true, "default", ""));
        host.npcs.add(new Npc("Yui", UUID.randomUUID(), "minecraft:overworld", true, "", ""));
        core.handle(op1, new RegisterReq(40, "Sakura", DRAFT));
        check(op(host).status() == Status.REJECTED && host.requests.isEmpty(), "default-mode NPCs are not registered by hand (would conflict with C2)");
        core.handle(op1, new RegisterReq(41, "Yui", DRAFT));
        check(op(host).status() == Status.REJECTED && host.requests.isEmpty(), "legacy NPCs are read-only");
        core.handle(op1, new RegisterReq(42, "Nobody", DRAFT));
        check(op(host).status() == Status.REJECTED, "unknown NPC");

        // ---------------- load / save
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        Npc reika = new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", "");
        host.npcs.add(reika);
        host.backend = (m, p, b) -> done(200, "{\"revision\":2,\"source\":\"edit\",\"composition\":\"structured\",\"profile\":{\"display_name\":\"Reika\",\"composition\":\"structured\",\"sections\":{\"personality\":\"P\",\"speech_style\":\"S\",\"backstory\":\"B\"}}}");
        core.handle(op1, new LoadReq(50, "Reika"));
        rep = op(host);
        check(rep.status() == Status.OK && rep.revision() == 2 && rep.draft().structured() && rep.draft().personality().equals("P") && rep.draft().backstory().equals("B"), "load returns the structured draft");
        host.backend = (m, p, b) -> done(200, "{\"revision\":4,\"source\":\"inline\",\"composition\":\"raw\",\"profile\":{\"display_name\":\"Mika\",\"composition\":\"raw\",\"system_prompt\":\"You are Mika.\"}}");
        core.handle(op1, new LoadReq(51, "Reika"));
        check(!op(host).draft().structured() && op(host).draft().systemPrompt().equals("You are Mika.") && op(host).revision() == 4, "load returns a raw draft");
        host.backend = (m, p, b) -> done(404, "{\"error\":\"Actor has no Character Profile\",\"code\":\"profile_not_registered\"}");
        core.handle(op1, new LoadReq(52, "Reika"));
        check(op(host).status() == Status.NOT_REGISTERED && op(host).draft() == null, "load of an unregistered profile");

        host.backend = (m, p, b) -> done(201, "{\"applied\":true,\"profile\":{\"revision\":3,\"display_name\":\"Reika\",\"composition\":\"structured\",\"source\":\"edit\"}}");
        host.requests.clear(); host.bodies.clear();
        core.handle(op1, new SaveReq(53, "Reika", 2, DRAFT));
        rep = op(host);
        check(rep.status() == Status.OK && rep.revision() == 3 && rep.message().contains("revision 3"), "save creates the next revision");
        check(host.requests.get(0).equals("POST /v1/actors/" + reika.actorId() + "/profile/revisions") && host.bodies.get(0).get("expected_revision").getAsInt() == 2, "expected revision is sent");
        host.backend = (m, p, b) -> done(200, "{\"applied\":false,\"profile\":{\"revision\":3,\"display_name\":\"Reika\",\"composition\":\"structured\",\"source\":\"edit\"}}");
        core.handle(op1, new SaveReq(54, "Reika", 3, DRAFT));
        check(op(host).status() == Status.OK && op(host).message().contains("No changes"), "idempotent retry / no-op is not an error");
        host.backend = (m, p, b) -> done(409, "{\"error\":\"Profile is at revision 5; you edited revision 3\",\"code\":\"revision_conflict\",\"current_revision\":5}");
        core.handle(op1, new SaveReq(55, "Reika", 3, DRAFT));
        rep = op(host);
        check(rep.status() == Status.CONFLICT && rep.currentRevision() == 5 && rep.message().contains("Reload"), "revision conflict reports the current revision");
        host.backend = (m, p, b) -> fails(new HttpTimeoutException("slow"));
        core.handle(op1, new SaveReq(56, "Reika", 3, DRAFT));
        check(op(host).status() == Status.UNKNOWN, "save timeout is UNKNOWN");
        host.backend = (m, p, b) -> fails(new ConnectException("down"));
        core.handle(op1, new SaveReq(57, "Reika", 3, DRAFT));
        check(op(host).status() == Status.OFFLINE, "save while offline");
        host.backend = (m, p, b) -> fails(new IOException("reset"));
        core.handle(op1, new SaveReq(58, "Reika", 3, DRAFT));
        check(op(host).status() == Status.UNKNOWN, "broken connection mid-request is UNKNOWN");
        host.requests.clear();
        core.handle(op1, new SaveReq(59, "Reika", 0, DRAFT));
        check(op(host).status() == Status.REJECTED && host.requests.isEmpty(), "save without a loaded revision is refused locally");
        core.handle(op1, new SaveReq(60, "Reika", 1, ManagerProtocol.Draft.raw("", "x")));
        check(op(host).status() == Status.REJECTED && host.requests.isEmpty(), "invalid draft never reaches the backend");
        host.backend = (m, p, b) -> done(404, "{\"error\":\"Actor has no Character Profile (legacy Character Pack actor)\",\"code\":\"profile_not_registered\"}");
        core.handle(op1, new SaveReq(61, "Reika", 1, DRAFT));
        check(op(host).status() == Status.NOT_REGISTERED, "editing an unregistered/legacy profile is refused by the backend and reported");

        // ---------------- duplicate submissions and in-flight guards
        host = new FakeHost(); host.allowed.addAll(List.of(op1, op2)); core = new ManagerCore(host);
        host.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", ""));
        host.npcs.add(new Npc("Mika", UUID.randomUUID(), "minecraft:overworld", true, "custom", ""));
        CompletableFuture<Http> pending = new CompletableFuture<>();
        host.backend = (m, p, b) -> pending;
        core.handle(op1, new SaveReq(70, "Reika", 1, DRAFT));
        check(host.replies.isEmpty(), "nothing is replied while the backend call is in flight");
        core.handle(op1, new SaveReq(71, "Reika", 1, DRAFT));
        check(op(host).status() == Status.BUSY && host.requests.size() == 1, "same player double-submit is blocked");
        core.handle(op1, new ListReq(72));
        check(host.last() instanceof ListRep && ((ListRep) host.last()).status() == Status.BUSY, "list while busy is BUSY");
        core.handle(op2, new SaveReq(73, "reika", 1, DRAFT));
        check(op(host).status() == Status.BUSY && host.requests.size() == 1, "another operator cannot edit the same NPC at the same time");
        host.backend = (m, p, b) -> CompletableFuture.completedFuture(ok(200, "{\"applied\":true,\"profile\":{\"revision\":2}}"));
        core.handle(op2, new SaveReq(74, "Mika", 1, DRAFT));
        check(op(host).status() == Status.OK, "a different NPC by a different operator is independent");
        pending.complete(ok(200, "{\"applied\":true,\"profile\":{\"revision\":2}}"));
        check(op(host).opId() == 70, "the original request still gets its own reply");
        host.backend = (m, p, b) -> done(200, "{\"applied\":true,\"profile\":{\"revision\":3}}");
        core.handle(op1, new SaveReq(75, "Reika", 2, DRAFT));
        check(op(host).status() == Status.OK && op(host).opId() == 75, "busy flags are released after completion");

        // completion arriving on a foreign thread is re-marshalled to the server thread
        host = new FakeHost(); host.allowed.add(op1); host.deferToServerThread = true; core = new ManagerCore(host);
        host.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", ""));
        CompletableFuture<Http> late = new CompletableFuture<>();
        host.backend = (m, p, b) -> late;
        core.handle(op1, new SaveReq(80, "Reika", 1, DRAFT));
        late.complete(ok(200, "{\"applied\":true,\"profile\":{\"revision\":2}}"));
        check(host.replies.isEmpty() && host.serverQueue.size() == 1, "reply waits for the server thread");
        core.handle(op1, new SaveReq(81, "Reika", 1, DRAFT));
        check(op(host).status() == Status.BUSY, "still busy until the server thread processes the completion");
        host.drain();
        check(host.replies.size() == 2 && ((OpRep) host.replies.get(1)).status() == Status.OK, "reply sent from the server thread");

        // unexpected exception inside a request never leaks a busy flag
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", ""));
        host.backend = (m, p, b) -> { throw new IllegalStateException("boom"); };
        core.handle(op1, new SaveReq(90, "Reika", 1, DRAFT));
        check(op(host).status() == Status.ERROR, "internal error reported");
        host.backend = (m, p, b) -> done(200, "{\"applied\":true,\"profile\":{\"revision\":2}}");
        core.handle(op1, new SaveReq(91, "Reika", 1, DRAFT));
        check(op(host).status() == Status.OK, "player and NPC are usable again after an internal error");

        // an unexpected failure inside the async work still answers with the request's own id
        host = new FakeHost(); host.allowed.add(op1); core = new ManagerCore(host);
        host.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, "custom", ""));
        host.backend = (m, p, b) -> { CompletableFuture<Http> f = new CompletableFuture<>(); f.completeExceptionally(new RuntimeException("x")); return f; };
        core.handle(op1, new LoadReq(95, "Reika"));
        check(op(host).opId() == 95 && op(host).status() == Status.ERROR, "load failure keeps its operation id");
        host.backend = (m, p, b) -> done(200, "{\"revision\":1,\"source\":\"inline\",\"composition\":\"structured\",\"profile\":{\"display_name\":\"R\",\"composition\":\"structured\"}}");
        core.handle(op1, new LoadReq(96, "Reika"));
        check(op(host).status() == Status.ERROR && op(host).draft() == null, "structured profile without sections is an error, not an empty draft");

        // ---------------- helpers
        check(ManagerCore.classify(new CompletionException(new ConnectException("x"))).status() == Status.OFFLINE, "classify connect");
        check(ManagerCore.classify(new IOException("x")).status() == Status.UNKNOWN, "classify io");
        check(ManagerCore.classify(new IllegalArgumentException("Profile request is too large")).status() == Status.REJECTED, "classify local refusal");
        check(ManagerCore.classify(new RuntimeException("x")).status() == Status.ERROR, "classify other");
        check(ManagerCore.clip("a\nb\u0000c").equals("a b c"), "control characters stripped from messages");
        check(ManagerCore.clip("x".repeat(500)).length() <= ManagerProtocol.MESSAGE_MAX, "messages are clipped to the wire limit");
        JsonObject structuredJson = ManagerCore.profileJson(DRAFT);
        JsonObject rawJson = ManagerCore.profileJson(ManagerProtocol.Draft.raw("Mika", "You are Mika."));
        check(rawJson.get("composition").getAsString().equals("raw") && rawJson.get("system_prompt").getAsString().equals("You are Mika.") && !rawJson.has("sections"), "raw json shape");
        check(structuredJson.get("schema_version").getAsInt() == 1, "schema version sent");
        Row legacyRow = ManagerCore.rowFor(new Npc("Mika", UUID.randomUUID(), "minecraft:overworld", true, "", ""), null);
        check(legacyRow.state() == RowState.NOT_REGISTERED && legacyRow.detail().contains("'mika'"), "legacy NPC detail names the pack it would use");
        check(ManagerCore.rowFor(new Npc("X", UUID.randomUUID(), "d", true, "invalid", ""), null).detail().contains("unreadable"), "invalid saved mode is visible");
        // ---------------- Stage 4 NOOP world action: the requester is the packet sender, never message content
        FakeHost wa = new FakeHost();
        ManagerCore waCore = new ManagerCore(wa);
        UUID sender = UUID.randomUUID();
        wa.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, C1IdentityData.MODE_CUSTOM, ""));
        waCore.handle(stranger, new NoopReq(70, "Reika", false));
        check(wa.last() instanceof Denied && wa.worldCalls.isEmpty(), "unauthorized sender never reaches the world-action path");
        wa.allowed.add(sender);
        waCore.handle(sender, new NoopReq(71, "Nobody", false));
        check(op(wa).status() == Status.REJECTED && op(wa).op() == Op.NOOP && wa.worldCalls.isEmpty(), "unknown NPC is rejected before the flow");
        waCore.handle(sender, new NoopReq(72, "reika", false));
        check(wa.worldCalls.size() == 1 && wa.worldCalls.get(0)[0].equals(sender) && wa.worldCalls.get(0)[2].equals(false),
                "flow receives the authenticated sender UUID");
        check(op(wa).status() == Status.OK && op(wa).op() == Op.NOOP, "OK result maps to OK");
        waCore.handle(sender, new NoopReq(73, "Reika", true));
        check(wa.worldCalls.get(1)[2].equals(true), "reconcile flag is passed through");
        for (WorldEffectFlow.Kind kind : WorldEffectFlow.Kind.values()) {
            wa.worldScript = () -> CompletableFuture.completedFuture(new WorldEffectFlow.Result(kind, "m", ""));
            waCore.handle(sender, new NoopReq(80, "Reika", false));
            Status expected = Status.valueOf(kind.name());
            check(op(wa).status() == expected, "kind " + kind + " maps to status " + expected);
        }
        wa.worldScript = () -> CompletableFuture.failedFuture(new IllegalStateException("boom"));
        waCore.handle(sender, new NoopReq(81, "Reika", false));
        check(op(wa).status() == Status.UNKNOWN && op(wa).message().contains("Reconcile"), "a crashed flow is UNKNOWN, never success");
        CompletableFuture<WorldEffectFlow.Result> gate = new CompletableFuture<>();
        wa.worldScript = () -> gate;
        waCore.handle(sender, new NoopReq(82, "Reika", false));
        int before = wa.worldCalls.size();
        waCore.handle(sender, new NoopReq(83, "Reika", false));
        check(wa.worldCalls.size() == before && op(wa).status() == Status.BUSY, "a second request while one runs is BUSY, flow not entered");
        gate.complete(new WorldEffectFlow.Result(WorldEffectFlow.Kind.OK, "ok", ""));
        waCore.handle(sender, new NoopReq(84, "Reika", false));
        check(wa.worldCalls.size() == before + 1, "busy flag released after completion");

        // ---------------- Stage 4 native break/pickup: the requester is the packet sender; the client sends a name, an action, a nonce
        List<Object[]> nativeCalls = new ArrayList<>();
        FakeHost nh = new FakeHost() {
            @Override public CompletableFuture<NativeEffectFlow.Result> nativeAction(UUID requester, String npcName, int action, String nonce) {
                nativeCalls.add(new Object[]{requester, npcName, action, nonce});
                return CompletableFuture.completedFuture(new NativeEffectFlow.Result(NativeEffectFlow.Kind.OK, "Preview: x", "PREVIEW", action == 0 ? "0123456789abcdef0123456789abcdef" : ""));
            }
        };
        ManagerCore nCore = new ManagerCore(nh);
        nh.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, C1IdentityData.MODE_CUSTOM, ""));
        nCore.handle(stranger, new ManagerProtocol.NativeReq(90, "Reika", 0, ""));
        check(nh.last() instanceof Denied && nativeCalls.isEmpty(), "an unauthorized sender never reaches the native path");
        nh.allowed.add(sender);
        nCore.handle(sender, new ManagerProtocol.NativeReq(91, "Nobody", 0, ""));
        check(op(nh).status() == Status.REJECTED && op(nh).op() == Op.NATIVE && nativeCalls.isEmpty(), "unknown NPC rejected before the flow");
        nCore.handle(sender, new ManagerProtocol.NativeReq(92, "Reika", 0, ""));
        check(nativeCalls.size() == 1 && nativeCalls.get(0)[0].equals(sender) && nativeCalls.get(0)[2].equals(0), "the flow receives the authenticated sender UUID");
        check(nh.last() instanceof ManagerProtocol.NativeRep nr && nr.status() == Status.OK && nr.nonce().length() == 32, "the preview nonce goes back to the sender");
        nCore.handle(sender, new ManagerProtocol.NativeReq(93, "Reika", ManagerProtocol.NATIVE_CONFIRM, "ffffffffffffffffffffffffffffffff"));
        check(nativeCalls.size() == 2 && nativeCalls.get(1)[3].equals("ffffffffffffffffffffffffffffffff"), "confirm passes the nonce through unchanged");
        nCore.handle(sender, new ManagerProtocol.NativeReq(94, "Reika", ManagerProtocol.NATIVE_CONFIRM, "not-a-nonce"));
        check(nativeCalls.size() == 2 && op(nh).status() == Status.REJECTED, "a malformed confirm never reaches the flow");
        for (NativeEffectFlow.Kind kind : NativeEffectFlow.Kind.values()) {
            FakeHost kh = new FakeHost() {
                @Override public CompletableFuture<NativeEffectFlow.Result> nativeAction(UUID requester, String npcName, int action, String nonce) {
                    return CompletableFuture.completedFuture(new NativeEffectFlow.Result(kind, "m", ""));
                }
            };
            kh.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, C1IdentityData.MODE_CUSTOM, ""));
            kh.allowed.add(sender);
            new ManagerCore(kh).handle(sender, new ManagerProtocol.NativeReq(95, "Reika", 3, ""));
            check(kh.last() instanceof ManagerProtocol.NativeRep r2 && r2.status() == Status.valueOf(kind.name()), "native kind " + kind + " maps to its status");
        }
        FakeHost ch = new FakeHost() {
            @Override public CompletableFuture<NativeEffectFlow.Result> nativeAction(UUID requester, String npcName, int action, String nonce) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
        };
        ch.npcs.add(new Npc("Reika", UUID.randomUUID(), "minecraft:overworld", true, C1IdentityData.MODE_CUSTOM, ""));
        ch.allowed.add(sender);
        new ManagerCore(ch).handle(sender, new ManagerProtocol.NativeReq(96, "Reika", 2, "0123456789abcdef0123456789abcdef"));
        check(op(ch).status() == Status.UNKNOWN, "a crashed native flow is UNKNOWN, never success");

        System.out.println("PASS ManagerCoreSelfTest (" + n + " assertions)");
    }
}
