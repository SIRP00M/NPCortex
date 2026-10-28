package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.ManagerProtocol.Draft;
import io.github.sirp00m.npcortex.ManagerProtocol.Op;
import io.github.sirp00m.npcortex.ManagerProtocol.OpRep;
import io.github.sirp00m.npcortex.ManagerProtocol.Row;
import io.github.sirp00m.npcortex.ManagerProtocol.RowState;
import io.github.sirp00m.npcortex.ManagerProtocol.Status;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Authoritative NPCortex Manager logic. It has no Minecraft dependency: the Forge adapter
 * (CharacterManagerService) supplies a {@link Host}, which keeps every state-machine decision testable.
 *
 * Threading: {@link #handle} and every {@link Host#onServerThread} task run on the server thread; HTTP
 * completions are re-marshalled before they touch any state here, so plain sets are safe.
 */
final class ManagerCore {
    /** Backend answer; {@code body} is empty (never null) when the response was not a JSON object. */
    record Http(int status, JsonObject body) {}

    /** A snapshot of one saved C1 NPC. {@code actorId} comes from server state, never from a client. */
    record Npc(String name, UUID actorId, String dimension, boolean active, String mode, String preset) {}

    interface Host {
        boolean authorized(UUID player);
        List<Npc> npcs();
        UUID worldId();
        /** Local C1 spawn in custom mode (works with the backend offline). @return null on success, else why not. */
        String createNpc(UUID player, String name);
        void reply(UUID player, ManagerProtocol.ToClient message);
        void onServerThread(Runnable task);
        CompletableFuture<Http> http(String method, String path, JsonObject body);
        void log(String message);
        /** Stage 4 no-op world action. {@code requester} is the authenticated packet sender (never client text). */
        CompletableFuture<WorldEffectFlow.Result> worldAction(UUID requester, String npcName, boolean reconcile);
        /** Stage 4 native break/pickup: preview, confirm or cancel. {@code requester} is the authenticated packet sender. */
        default CompletableFuture<NativeEffectFlow.Result> nativeAction(UUID requester, String npcName, int action, String nonce) {
            return CompletableFuture.completedFuture(new NativeEffectFlow.Result(NativeEffectFlow.Kind.REJECTED,
                    "Native actions are not available on this host.", ""));
        }
    }

    private final Host host;
    private final Set<UUID> busyPlayers = new HashSet<>();
    private final Set<String> busyNpcs = new HashSet<>();

    ManagerCore(Host host) { this.host = host; }

    // ---------------------------------------------------------------- entry point

    void handle(UUID player, ManagerProtocol.ToServer message) {
        if (!host.authorized(player)) {
            host.reply(player, new ManagerProtocol.Denied(message.opId(),
                    "NPCortex Manager needs operator permission level 2, or the single-player owner."));
            return;
        }
        if (!busyPlayers.add(player)) {
            host.reply(player, busy(message));
            return;
        }
        String key = null;
        try {
            if (message instanceof ManagerProtocol.ListReq m) {
                run(player, null, m, list(m));
                return;
            }
            String name = nameOf(message);
            String error = ManagerProtocol.validateName(name);
            if (error != null) { fail(player, null, op(message), message.opId(), Status.REJECTED, error); return; }
            key = name.toLowerCase(Locale.ROOT);
            if (!busyNpcs.add(key)) {
                busyPlayers.remove(player);
                host.reply(player, busy(message));
                return;
            }
            if (message instanceof ManagerProtocol.CreateReq m) run(player, key, m, create(player, m));
            else if (message instanceof ManagerProtocol.RegisterReq m) run(player, key, m, register(m));
            else if (message instanceof ManagerProtocol.LoadReq m) run(player, key, m, load(m));
            else if (message instanceof ManagerProtocol.SaveReq m) run(player, key, m, save(m));
            else if (message instanceof ManagerProtocol.NoopReq m) run(player, key, m, noop(player, m));
            else if (message instanceof ManagerProtocol.NativeReq m) run(player, key, m, nativeAction(player, m));
        } catch (RuntimeException ex) {
            host.log("Manager internal error: " + ex);
            fail(player, key, op(message), message.opId(), Status.ERROR, "Internal error; see the server log.");
        }
    }

    private static String nameOf(ManagerProtocol.ToServer message) {
        if (message instanceof ManagerProtocol.CreateReq m) return m.name();
        if (message instanceof ManagerProtocol.RegisterReq m) return m.name();
        if (message instanceof ManagerProtocol.LoadReq m) return m.name();
        if (message instanceof ManagerProtocol.SaveReq m) return m.name();
        if (message instanceof ManagerProtocol.NoopReq m) return m.name();
        if (message instanceof ManagerProtocol.NativeReq m) return m.name();
        return null;
    }

    private static Op op(ManagerProtocol.ToServer message) {
        if (message instanceof ManagerProtocol.CreateReq) return Op.CREATE;
        if (message instanceof ManagerProtocol.RegisterReq) return Op.REGISTER;
        if (message instanceof ManagerProtocol.LoadReq) return Op.LOAD;
        if (message instanceof ManagerProtocol.SaveReq) return Op.SAVE;
        if (message instanceof ManagerProtocol.NoopReq) return Op.NOOP;
        if (message instanceof ManagerProtocol.NativeReq) return Op.NATIVE;
        return Op.LIST;
    }

    private ManagerProtocol.ToClient busy(ManagerProtocol.ToServer message) {
        String text = "Another Manager operation is still running; wait for it to finish.";
        if (message instanceof ManagerProtocol.ListReq)
            return new ManagerProtocol.ListRep(message.opId(), Status.BUSY, text, false, List.of());
        return new OpRep(message.opId(), op(message), Status.BUSY, text, 0, 0, null, null);
    }

    private void fail(UUID player, String key, Op op, long opId, Status status, String message) {
        busyPlayers.remove(player);
        if (key != null) busyNpcs.remove(key);
        host.reply(player, new OpRep(opId, op, status, clip(message), 0, 0, null, null));
    }

    /** Runs the async work; the reply and the busy-flag release always happen back on the server thread. */
    private void run(UUID player, String key, ManagerProtocol.ToServer request, CompletableFuture<ManagerProtocol.ToClient> work) {
        work.whenComplete((reply, error) -> host.onServerThread(() -> {
            busyPlayers.remove(player);
            if (key != null) busyNpcs.remove(key);
            if (error != null || reply == null) {
                host.log("Manager operation failed unexpectedly: " + error);
                host.reply(player, request instanceof ManagerProtocol.ListReq
                        ? new ManagerProtocol.ListRep(request.opId(), Status.ERROR, "Internal error; see the server log.", false, List.of())
                        : new OpRep(request.opId(), op(request), Status.ERROR, "Internal error; see the server log.", 0, 0, null, null));
            } else {
                host.reply(player, reply);
            }
        }));
    }

    // ---------------------------------------------------------------- LIST

    private CompletableFuture<ManagerProtocol.ToClient> list(ManagerProtocol.ListReq request) {
        List<Npc> npcs = host.npcs();
        UUID world = host.worldId();
        return host.http("GET", "/v1/worlds/" + world + "/profiles", null)
                .thenApply(response -> listFrom(request.opId(), npcs, response))
                .exceptionally(error -> {
                    Failure failure = classify(error);
                    return listReply(request.opId(), failure.status, failure.message, npcs, null, RowState.OFFLINE,
                            failure.status == Status.OFFLINE ? "Backend offline: profile status unknown" : "Profile status unknown");
                });
    }

    private ManagerProtocol.ToClient listFrom(long opId, List<Npc> npcs, Http response) {
        if (response.status() == 200) {
            JsonObject byActor = new JsonObject();
            JsonElement actors = response.body().get("actors");
            if (actors != null && actors.isJsonArray()) {
                for (JsonElement item : actors.getAsJsonArray()) {
                    if (item.isJsonObject() && item.getAsJsonObject().has("actor_id")
                            && item.getAsJsonObject().get("actor_id").isJsonPrimitive())
                        byActor.add(item.getAsJsonObject().get("actor_id").getAsString(), item);
                }
            }
            return listReply(opId, Status.OK, "", npcs, byActor, RowState.NOT_REGISTERED, "");
        }
        if (response.status() == 404) // the world itself is not registered yet: nothing is registered
            return listReply(opId, Status.OK, "", npcs, new JsonObject(), RowState.NOT_REGISTERED, "");
        Failure failure = fromHttp(response);
        return listReply(opId, failure.status, failure.message, npcs, null, RowState.OFFLINE, "Profile status unknown");
    }

    private ManagerProtocol.ToClient listReply(long opId, Status status, String message, List<Npc> npcs,
                                               JsonObject byActor, RowState unknown, String unknownDetail) {
        List<Row> rows = new ArrayList<>();
        for (Npc npc : npcs) {
            if (rows.size() >= ManagerProtocol.MAX_ROWS) break;
            JsonObject backend = byActor == null ? null : byActor.has(npc.actorId().toString())
                    ? byActor.getAsJsonObject(npc.actorId().toString()) : null;
            rows.add(byActor == null ? unknownRow(npc, unknown, unknownDetail) : rowFor(npc, backend));
        }
        return new ManagerProtocol.ListRep(opId, status, clip(message), npcs.size() > rows.size(), List.copyOf(rows));
    }

    static String modeLabel(Npc npc) {
        return switch (npc.mode()) {
            case "" -> "legacy";
            case C1IdentityData.MODE_PRESET -> "preset:" + npc.preset();
            default -> npc.mode();
        };
    }

    private static Row unknownRow(Npc npc, RowState state, String detail) {
        return new Row(npc.name(), npc.dimension(), npc.active(), modeLabel(npc), state, detail, 0, "", "", "");
    }

    /** {@code backend} is the world-listing entry for this actor, or null when the backend has no such actor. */
    static Row rowFor(Npc npc, JsonObject backend) {
        if (backend == null) {
            String detail = switch (npc.mode()) {
                case C1IdentityData.MODE_CUSTOM -> "Profile NOT registered. Use Register to create it.";
                case C1IdentityData.MODE_DEFAULT -> "Not registered yet; the default profile is created at the first C2 use.";
                case C1IdentityData.MODE_PRESET -> "Not registered yet; copied from pack '" + npc.preset() + "' at the first C2 use.";
                case C1IdentityData.MODE_INVALID -> "Saved profile mode is unreadable; recreate this NPC.";
                default -> "Legacy NPC (pack '" + npc.name().toLowerCase(Locale.ROOT) + "'): not registered with the backend.";
            };
            return new Row(npc.name(), npc.dimension(), npc.active(), modeLabel(npc), RowState.NOT_REGISTERED, detail, 0, "", "", "");
        }
        JsonElement profile = backend.get("profile");
        if (profile == null || !profile.isJsonObject()) {
            String pack = str(backend, "character_id");
            return new Row(npc.name(), npc.dimension(), npc.active(), modeLabel(npc), RowState.LEGACY,
                    "Legacy Character Pack '" + pack + "' (read-only).", 0, "pack:" + pack, "", "");
        }
        JsonObject p = profile.getAsJsonObject();
        int revision = p.has("revision") && p.get("revision").isJsonPrimitive() ? p.get("revision").getAsInt() : 0;
        return new Row(npc.name(), npc.dimension(), npc.active(), modeLabel(npc), RowState.OK,
                "Profile revision " + revision + ".", revision, str(p, "source"), str(p, "display_name"), str(p, "composition"));
    }

    // ---------------------------------------------------------------- CREATE / REGISTER

    private CompletableFuture<ManagerProtocol.ToClient> create(UUID player, ManagerProtocol.CreateReq request) {
        String error = ManagerProtocol.validateDraft(request.draft());
        if (error != null) return done(request.opId(), Op.CREATE, Status.REJECTED, error, null);
        if (find(request.name()) != null)
            return done(request.opId(), Op.CREATE, Status.REJECTED,
                    "An NPC named '" + request.name() + "' already exists; names cannot be reused.", null);
        String spawn = host.createNpc(player, request.name());
        if (spawn != null) {
            Npc leftover = find(request.name());
            // A failed spawn can leave a saved (inactive) identity; say so instead of pretending nothing happened.
            return done(request.opId(), Op.CREATE, Status.ERROR, "NPC was NOT fully created: " + spawn
                    + (leftover != null ? " A saved identity exists; use Register once it spawns." : ""),
                    leftover == null ? null : rowFor(leftover, null));
        }
        Npc npc = find(request.name());
        if (npc == null) return done(request.opId(), Op.CREATE, Status.ERROR, "NPC identity missing after spawn.", null);
        return registerChain(npc, ManagerProtocol.normalised(request.draft()))
                .thenApply(result -> registrationReply(request.opId(), Op.CREATE, npc, result,
                        "NPC created and Character Profile registered", "NPC created locally, but its Character Profile is NOT registered"));
    }

    private CompletableFuture<ManagerProtocol.ToClient> register(ManagerProtocol.RegisterReq request) {
        Npc npc = find(request.name());
        if (npc == null) return done(request.opId(), Op.REGISTER, Status.REJECTED, "Unknown NPC '" + request.name() + "'.", null);
        if (!C1IdentityData.MODE_CUSTOM.equals(npc.mode()))
            return done(request.opId(), Op.REGISTER, Status.REJECTED,
                    "Only NPCs created with a custom profile can be registered here.", rowFor(npc, null));
        String error = ManagerProtocol.validateDraft(request.draft());
        if (error != null) return done(request.opId(), Op.REGISTER, Status.REJECTED, error, rowFor(npc, null));
        return registerChain(npc, ManagerProtocol.normalised(request.draft()))
                .thenApply(result -> registrationReply(request.opId(), Op.REGISTER, npc, result,
                        "Character Profile registered", "Character Profile is NOT registered"));
    }

    private record Registration(Status status, String message, JsonObject binding, int revision) {}

    private OpRep registrationReply(long opId, Op op, Npc npc, Registration result, String okText, String failText) {
        if (result.status != Status.OK)
            return new OpRep(opId, op, result.status, clip(failText + ": " + result.message), 0, 0, rowFor(npc, null), null);
        JsonObject actor = new JsonObject();
        actor.add("profile", result.binding);
        actor.addProperty("character_id", "@profile");
        return new OpRep(opId, op, Status.OK, okText + " (revision " + result.revision + ").", result.revision, 0,
                rowFor(npc, actor), null);
    }

    /** world -> actor+profile (one backend transaction) -> read back and compare. Nothing here changes C1/C2 state. */
    private CompletableFuture<Registration> registerChain(Npc npc, Draft draft) {
        UUID world = host.worldId();
        JsonObject worldBody = new JsonObject();
        worldBody.addProperty("world_id", world.toString());
        worldBody.addProperty("name", C1IdentityData.backendWorldName(world));
        JsonObject actorBody = new JsonObject();
        actorBody.addProperty("world_id", world.toString());
        actorBody.addProperty("dimension_id", npc.dimension());
        actorBody.addProperty("actor_id", npc.actorId().toString());
        actorBody.add("profile", profileJson(draft));
        return host.http("POST", "/v1/worlds", worldBody).thenCompose(w -> {
            if (w.status() != 200 && w.status() != 201) return CompletableFuture.completedFuture(failed(fromHttp(w)));
            return host.http("POST", "/v1/actors", actorBody).thenCompose(a -> {
                if (a.status() != 200 && a.status() != 201) return CompletableFuture.completedFuture(failed(fromHttp(a)));
                JsonObject binding = a.body().has("profile") && a.body().get("profile").isJsonObject()
                        ? a.body().getAsJsonObject("profile") : null;
                if (binding == null) return CompletableFuture.completedFuture(
                        new Registration(Status.ERROR, "Backend did not return a profile binding.", null, 0));
                return host.http("GET", "/v1/actors/" + npc.actorId() + "/profile", null).thenApply(check -> {
                    if (check.status() != 200) {
                        Failure failure = fromHttp(check);
                        return new Registration(failure.status == Status.OFFLINE ? Status.UNKNOWN : failure.status,
                                "Registered, but verification failed: " + failure.message, null, 0);
                    }
                    int revision = intOf(check.body(), "revision");
                    if (revision != intOf(binding, "revision")
                            || !str(check.body(), "content_sha256").equals(str(binding, "content_sha256")))
                        return new Registration(Status.ERROR, "Registered, but the stored profile does not match what was sent.", null, 0);
                    return new Registration(Status.OK, "", binding, revision);
                });
            });
        }).exceptionally(error -> failed(classify(error)));
    }

    private static Registration failed(Failure failure) {
        return new Registration(failure.status, failure.message, null, 0);
    }

    // ---------------------------------------------------------------- LOAD / SAVE

    private CompletableFuture<ManagerProtocol.ToClient> load(ManagerProtocol.LoadReq request) {
        Npc npc = find(request.name());
        if (npc == null) return done(request.opId(), Op.LOAD, Status.REJECTED, "Unknown NPC '" + request.name() + "'.", null);
        return host.http("GET", "/v1/actors/" + npc.actorId() + "/profile", null).thenApply(response -> {
            if (response.status() != 200) {
                Failure failure = fromHttp(response);
                return (ManagerProtocol.ToClient) new OpRep(request.opId(), Op.LOAD, failure.status, clip(failure.message),
                        0, 0, rowFor(npc, null), null);
            }
            JsonObject exported = response.body();
            JsonObject profile = exported.has("profile") && exported.get("profile").isJsonObject()
                    ? exported.getAsJsonObject("profile") : new JsonObject();
            Draft draft;
            if ("structured".equals(str(profile, "composition")) && !(profile.has("sections") && profile.get("sections").isJsonObject()))
                return (ManagerProtocol.ToClient) new OpRep(request.opId(), Op.LOAD, Status.ERROR,
                        "The backend returned an unreadable structured profile.", 0, 0, rowFor(npc, null), null);
            if ("structured".equals(str(profile, "composition")) && profile.has("sections") && profile.get("sections").isJsonObject()) {
                JsonObject s = profile.getAsJsonObject("sections");
                draft = Draft.structured(str(profile, "display_name"), str(s, "personality"), str(s, "speech_style"), str(s, "backstory"));
            } else {
                draft = Draft.raw(str(profile, "display_name"), str(profile, "system_prompt"));
            }
            JsonObject actor = new JsonObject();
            JsonObject binding = new JsonObject();
            binding.addProperty("revision", intOf(exported, "revision"));
            binding.addProperty("source", str(exported, "source"));
            binding.addProperty("display_name", draft.displayName());
            binding.addProperty("composition", str(exported, "composition"));
            actor.add("profile", binding);
            return (ManagerProtocol.ToClient) new OpRep(request.opId(), Op.LOAD, Status.OK, "Loaded revision " + intOf(exported, "revision") + ".",
                    intOf(exported, "revision"), 0, rowFor(npc, actor), draft);
        }).exceptionally(error -> {
            Failure failure = classify(error);
            return new OpRep(request.opId(), Op.LOAD, failure.status, clip(failure.message), 0, 0, rowFor(npc, null), null);
        });
    }

    private CompletableFuture<ManagerProtocol.ToClient> save(ManagerProtocol.SaveReq request) {
        Npc npc = find(request.name());
        if (npc == null) return done(request.opId(), Op.SAVE, Status.REJECTED, "Unknown NPC '" + request.name() + "'.", null);
        if (request.expectedRevision() < 1)
            return done(request.opId(), Op.SAVE, Status.REJECTED, "Load the profile before saving.", rowFor(npc, null));
        String error = ManagerProtocol.validateDraft(request.draft());
        if (error != null) return done(request.opId(), Op.SAVE, Status.REJECTED, error, rowFor(npc, null));
        JsonObject body = new JsonObject();
        body.addProperty("expected_revision", request.expectedRevision());
        body.add("profile", profileJson(ManagerProtocol.normalised(request.draft())));
        return host.http("POST", "/v1/actors/" + npc.actorId() + "/profile/revisions", body).thenApply(response -> {
            if (response.status() == 200 || response.status() == 201) {
                JsonObject binding = response.body().has("profile") && response.body().get("profile").isJsonObject()
                        ? response.body().getAsJsonObject("profile") : new JsonObject();
                boolean applied = !response.body().has("applied") || response.body().get("applied").getAsBoolean();
                int revision = intOf(binding, "revision");
                JsonObject actor = new JsonObject();
                actor.add("profile", binding);
                String text = applied ? "Saved as revision " + revision + "." : "No changes to save; still revision " + revision + ".";
                return (ManagerProtocol.ToClient) new OpRep(request.opId(), Op.SAVE, Status.OK, text, revision, 0, rowFor(npc, actor), null);
            }
            Failure failure = fromHttp(response);
            return (ManagerProtocol.ToClient) new OpRep(request.opId(), Op.SAVE, failure.status, clip(failure.message),
                    0, failure.currentRevision, rowFor(npc, null), null);
        }).exceptionally(errorValue -> {
            Failure failure = classify(errorValue);
            return new OpRep(request.opId(), Op.SAVE, failure.status, clip(failure.message), 0, 0, rowFor(npc, null), null);
        });
    }

    // ---------------------------------------------------------------- NOOP world action (Stage 4)

    private CompletableFuture<ManagerProtocol.ToClient> noop(UUID player, ManagerProtocol.NoopReq request) {
        if (find(request.name()) == null)
            return done(request.opId(), Op.NOOP, Status.REJECTED, "Unknown NPC '" + request.name() + "'.", null);
        return host.worldAction(player, request.name(), request.reconcile()).thenApply(result -> {
            Status status = switch (result.kind()) {
                case OK -> Status.OK;
                case DENIED -> Status.DENIED;
                case REJECTED -> Status.REJECTED;
                case BUSY -> Status.BUSY;
                case ERROR -> Status.ERROR;
                case UNKNOWN -> Status.UNKNOWN;
            };
            Npc npc = find(request.name());
            return (ManagerProtocol.ToClient) new OpRep(request.opId(), Op.NOOP, status, clip(result.message()), 0, 0,
                    npc == null ? null : rowFor(npc, null), null);
        }).exceptionally(error -> new OpRep(request.opId(), Op.NOOP, Status.UNKNOWN,
                "World action outcome unknown; see the server log. Use Reconcile before retrying.", 0, 0, null, null));
    }

    // ---------------------------------------------------------------- NATIVE break / pickup (Stage 4 Option D)

    private CompletableFuture<ManagerProtocol.ToClient> nativeAction(UUID player, ManagerProtocol.NativeReq request) {
        String malformed = ManagerProtocol.validateNative(request);
        if (malformed != null) return done(request.opId(), Op.NATIVE, Status.REJECTED, malformed, null);
        if (find(request.name()) == null)
            return done(request.opId(), Op.NATIVE, Status.REJECTED, "Unknown NPC '" + request.name() + "'.", null);
        return host.nativeAction(player, request.name(), request.action(), request.nonce()).thenApply(result -> {
            Status status = switch (result.kind()) {
                case OK -> Status.OK;
                case DENIED -> Status.DENIED;
                case REJECTED -> Status.REJECTED;
                case BUSY -> Status.BUSY;
                case ERROR -> Status.ERROR;
                case UNKNOWN -> Status.UNKNOWN;
            };
            return (ManagerProtocol.ToClient) new ManagerProtocol.NativeRep(request.opId(), status, clip(result.message()), result.nonce());
        }).exceptionally(error -> new OpRep(request.opId(), Op.NATIVE, Status.UNKNOWN,
                "Native action outcome unknown; see the server log. Use Rec. before retrying.", 0, 0, null, null));
    }

    // ---------------------------------------------------------------- helpers

    private Npc find(String name) {
        for (Npc npc : host.npcs()) if (npc.name().equalsIgnoreCase(name)) return npc;
        return null;
    }

    private static CompletableFuture<ManagerProtocol.ToClient> done(long opId, Op op, Status status, String message, Row row) {
        return CompletableFuture.completedFuture(new OpRep(opId, op, status, clip(message), 0, 0, row, null));
    }

    static JsonObject profileJson(Draft draft) {
        JsonObject profile = new JsonObject();
        profile.addProperty("schema_version", 1);
        profile.addProperty("composition", draft.structured() ? "structured" : "raw");
        profile.addProperty("display_name", draft.displayName());
        if (draft.structured()) {
            JsonObject sections = new JsonObject();
            sections.addProperty("personality", draft.personality());
            sections.addProperty("speech_style", draft.speechStyle());
            sections.addProperty("backstory", draft.backstory());
            profile.add("sections", sections);
        } else {
            profile.addProperty("system_prompt", draft.systemPrompt());
        }
        return profile;
    }

    /** A classified failure. {@code currentRevision} is only set for revision conflicts. */
    record Failure(Status status, String message, int currentRevision) {}

    static Failure fromHttp(Http response) {
        String code = str(response.body(), "code");
        String text = str(response.body(), "error");
        switch (response.status()) {
            case 400:
                return new Failure(Status.REJECTED, text.isEmpty() ? "The backend rejected the profile." : text, 0);
            case 403:
                return new Failure(Status.ERROR, "The backend rejected the local credential (403). Restart the backend and the game, or check the local secret file.", 0);
            case 404:
                if ("profile_not_registered".equals(code)) return new Failure(Status.NOT_REGISTERED, text.isEmpty() ? "Profile is not registered." : text, 0);
                return new Failure(Status.NOT_REGISTERED, text.isEmpty() ? "Not registered with the backend." : text, 0);
            case 409:
                if ("revision_conflict".equals(code))
                    return new Failure(Status.CONFLICT, text + " Reload to see the newer version.", intOf(response.body(), "current_revision"));
                return new Failure(Status.CONFLICT, text.isEmpty() ? "Conflict with an existing registration." : text, 0);
            case 413:
                return new Failure(Status.REJECTED, "The profile is too large for the backend.", 0);
            default:
                return new Failure(Status.ERROR, "Backend error (HTTP " + response.status() + ")" + (text.isEmpty() ? "." : ": " + text), 0);
        }
    }

    /** Offline = the request was certainly not delivered; UNKNOWN = it may have been applied. */
    static Failure classify(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null)
            cause = cause.getCause();
        if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException)
            return new Failure(Status.OFFLINE, "Backend is offline (cannot connect). Start the Python backend and retry.", 0);
        if (cause instanceof HttpTimeoutException)
            return new Failure(Status.UNKNOWN, "No answer from the backend in time. The result is UNKNOWN: press Refresh to check.", 0);
        if (cause instanceof IllegalStateException && cause.getMessage() != null && cause.getMessage().startsWith("Local credential"))
            return new Failure(Status.ERROR, cause.getMessage(), 0);
        if (cause instanceof IllegalArgumentException)
            return new Failure(Status.REJECTED, cause.getMessage() == null ? "Request refused." : cause.getMessage(), 0);
        if (cause instanceof IOException)
            return new Failure(Status.UNKNOWN, "The connection to the backend broke. The result is UNKNOWN: press Refresh to check.", 0);
        return new Failure(Status.ERROR, "Unexpected error talking to the backend.", 0);
    }

    static String str(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    static int intOf(JsonObject object, String key) {
        try {
            JsonElement value = object == null ? null : object.get(key);
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    static String clip(String text) {
        String clean = (text == null ? "" : text).replaceAll("[\\p{Cntrl}]", " ").strip();
        return ManagerProtocol.length(clean) > ManagerProtocol.MESSAGE_MAX
                ? clean.substring(0, clean.offsetByCodePoints(0, ManagerProtocol.MESSAGE_MAX - 1)) + "…" : clean;
    }
}
