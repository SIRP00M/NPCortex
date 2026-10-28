package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.github.sirp00m.npcortex.cplus.CPlusF1Delivery;
import io.github.sirp00m.npcortex.cplus.CPlusLocalAuth;
import io.github.sirp00m.npcortex.cplus.CPlusTransport;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Minecraft/Forge binding of {@link WorldEffectFlow}: the NO-OP world action. It changes NOTHING in the world.
 *
 * Authority = the authenticated Manager packet sender (the caller passes {@code requester}), the Owner's allow-list file
 * {@code config/npcortex-world-actions.json} (read on every request: missing/invalid = deny everything) and the C1 identity
 * saved on the server. Backend, command and data-pack inputs are never consulted for authority.
 */
final class WorldEffectRuntime {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final String POLICY_FILE = "npcortex-world-actions.json";
    private static final Map<MinecraftServer, WorldEffectFlow> FLOWS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, ExecutorService> WORKERS = new IdentityHashMap<>();

    private WorldEffectRuntime() {}

    static boolean pending(MinecraftServer server, UUID actor) {
        WorldEffectFlow flow = FLOWS.get(server);
        return flow != null && flow.pending(actor);
    }

    /** Server thread. Never throws; the future always completes with a typed result. */
    static CompletableFuture<WorldEffectFlow.Result> request(MinecraftServer server, UUID requester, String npcName,
                                                             boolean reconcile) {
        try {
            ServerThread.require(server, "world action");
            WorldEffectFlow flow = FLOWS.computeIfAbsent(server, s -> new WorldEffectFlow(new ForgeEnv(s)));
            return reconcile ? flow.reconcile(requester, npcName) : flow.run(requester, npcName);
        } catch (RuntimeException e) {
            LOG.warn("NPCortex world action refused: {}", e.toString());
            return CompletableFuture.completedFuture(new WorldEffectFlow.Result(WorldEffectFlow.Kind.ERROR,
                    "World action unavailable; nothing was written.", ""));
        }
    }

    static void onServerStopping(ServerStoppingEvent event) {
        FLOWS.remove(event.getServer());
        ExecutorService worker = WORKERS.remove(event.getServer());
        if (worker != null) worker.shutdownNow();      // an unfinished attempt is settled by the backend session rule
    }

    private static ExecutorService worker(MinecraftServer server) {
        return WORKERS.computeIfAbsent(server, ignored -> new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), job -> {
                    Thread t = new Thread(job, "npcortex-world-effect");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    private static final class ForgeEnv implements WorldEffectFlow.Env {
        private final MinecraftServer server;

        ForgeEnv(MinecraftServer server) { this.server = server; }

        @Override public void onServerThread(Runnable task) { server.execute(task); }

        @Override public CompletableFuture<JsonObject> post(String route, JsonObject body) {
            final URI base;
            final String token;
            try {
                token = CPlusLocalAuth.readToken();
                base = CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.failedFuture(new IllegalStateException("authentication/config unavailable", e));
            }
            // status and reconcile are operator-time reads/decisions: no outbox file (their body has no durable task identity
            // of its own and a stale file would block a later, different reconcile).
            boolean direct = route.equals(WorldEffectFlow.STATUS) || route.equals(WorldEffectFlow.RECONCILE);
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        String reply = direct
                                ? CPlusTransport.post(base.resolve(route), token, body.toString(), 5, 24000)
                                : CPlusOutbox.send(CPlusOutbox.directory(server), base, token, route, body);
                        return JsonParser.parseString(reply).getAsJsonObject();
                    } catch (IOException e) {
                        throw classify(e);
                    } catch (RuntimeException e) {
                        throw classify(e);
                    }
                }, worker(server));
            } catch (RejectedExecutionException busy) {
                return CompletableFuture.failedFuture(new IllegalStateException("world-effect worker busy"));
            }
        }

        /** HTTP 4xx means the backend ANSWERED no; everything else is an unknown transport outcome. */
        private static RuntimeException classify(Throwable error) {
            for (Throwable t = error; t != null; t = t.getCause())
                if (t instanceof CPlusTransport.Rejected r && r.status >= 400 && r.status < 500)
                    return new WorldEffectFlow.BackendRefusal(r.getMessage());
            return error instanceof RuntimeException r ? r : new IllegalStateException(error);
        }

        private C1IdentityData.Entry entry(String name) { return C1IdentityData.get(server).find(name); }

        @Override public WorldEffectFlow.Actor resolve(String npcName) {
            if (npcName == null) return null;
            C1IdentityData.Entry identity = entry(npcName);
            C1PlayerAvatar avatar = identity == null ? null : C1P0Runtime.activeAvatar(server, npcName);
            if (identity == null || avatar == null || avatar.isRemoved() || !avatar.getUUID().equals(identity.profileId())
                    || !C1P1CLifecycleRuntime.mayRestore(server, identity.profileId()))
                return null;
            String dimension = avatar.serverLevel().dimension().location().toString();
            if (!dimension.equals(identity.dimension())) return null;
            return new WorldEffectFlow.Actor(identity.character(), C1IdentityData.get(server).worldId(), identity.actorId(),
                    identity.profileId(), dimension, CPlusF2WorldSampler.session(server));
        }

        @Override public String ownershipBlock(WorldEffectFlow.Actor a) {
            if (NativeEffectRuntime.pending(server, a.actorId())) return "native world action active";
            if (C2AgentRuntime.hasActiveActor(a.actorId())) return "C2 goal active";
            if (C2FollowRuntime.hasActiveActor(a.actorId())) return "Follow active";
            if (CPlusE3G2Runtime.pending(server, a.actorId())) return "E3 G2 admission pending";
            if (CPlusE3G3Runtime.pending(server, a.actorId())) return "E3 G3 operation pending";
            if (CPlusTransportRecovery.busy(server, a.actorId())) return "transport recovery busy";
            if (C1P1MotionRuntime.c2Receipt(a.profileId()).active()) return "C1/C2 motion receipt active";
            C2ActionJournal.Row old = C2ActionJournal.get(server).find(a.actorId());
            return old != null && old.uncertain() ? "C2 journal unresolved" : null;
        }

        @Override public WorldActionPolicy policy() {
            Path file = FMLPaths.CONFIGDIR.get().resolve(POLICY_FILE);
            try {
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return WorldActionPolicy.denyAll("allow-list file missing");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return WorldActionPolicy.denyAll("allow-list is not a regular file");
                if (Files.size(file) > WorldActionPolicy.MAX_BYTES) return WorldActionPolicy.denyAll("allow-list too large");
                return WorldActionPolicy.parse(Files.readAllBytes(file));
            } catch (IOException | RuntimeException e) {
                return WorldActionPolicy.denyAll("allow-list unreadable");
            }
        }

        @Override public boolean online(UUID player) {
            return server.isRunning() && server.getPlayerList().getPlayer(player) != null;
        }

        /** Read-only: loaded chunks via getChunkNow only; item entities are queried, never touched. */
        @Override public String fixtureHash(WorldEffectFlow.Actor a) {
            C1IdentityData.Entry identity = entry(a.name());
            C1PlayerAvatar avatar = identity == null ? null : C1P0Runtime.activeAvatar(server, a.name());
            if (avatar == null) return null;
            ServerLevel level = avatar.serverLevel();
            BlockPos at = avatar.blockPosition();
            List<String> parts = new ArrayList<>();
            parts.add(level.dimension().location().toString());
            parts.add(at.toShortString());
            LevelChunk chunk = null;
            int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = at.getX() + dx, y = at.getY() + dy, z = at.getZ() + dz;
                if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) { parts.add("void"); continue; }
                if (chunk == null || chunkX != (x >> 4) || chunkZ != (z >> 4)) {
                    chunkX = x >> 4; chunkZ = z >> 4;
                    chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                }
                if (chunk == null) return null;                    // unknown is never "unchanged"
                parts.add(chunk.getBlockState(pos.set(x, y, z)).toString());
            }
            parts.add(C1P1InventoryRuntime.inventory(server, a.name()));
            parts.add(C1P1InventoryRuntime.equipment(server, a.name()));
            List<String> drops = new ArrayList<>();
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(8.0)))
                drops.add(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()) + "x" + item.getItem().getCount()
                        + "@" + item.blockPosition().toShortString());
            drops.sort(null);
            parts.addAll(drops);
            return WorldEffectFlow.hashParts(parts);
        }

        @Override public long tick() { return server.overworld().getGameTime(); }

        @Override public UUID newId() { return UUID.randomUUID(); }
    }
}
