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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/** Minecraft/Forge binding of {@link NativeEffectFlow}: timed block breaking and native item pickup by the custom avatar.
 *
 * Authority = the authenticated Manager packet sender (the caller passes {@code requester}), the Owner's native allow-list
 * {@code config/npcortex-native-actions.json} (read by a background poller; stale or unreadable = deny everything) and the C1
 * identity saved on the server. Backend, command, data-pack and agent inputs are never consulted for authority, and the
 * backend cannot start a native call: Forge only ever executes an attempt it created in this session.
 *
 * No Minecraft server-thread task here reads a file or waits on a network call: transport, outbox files and the policy file
 * are handled by worker threads; replies are marshalled back with {@code server.execute}.
 */
final class NativeEffectRuntime {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final String POLICY_FILE = "npcortex-native-actions.json";
    private static final long POLICY_MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(4);
    private static final Map<MinecraftServer, NativeEffectFlow> FLOWS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Lanes> LANES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, PolicyPoller> POLLERS = new IdentityHashMap<>();

    /** Test seams (GameTest / self-tests only; production never sets them). */
    interface Backend { String post(String route, String body, boolean durableRecord) throws IOException; }
    static volatile Backend backendOverride;
    static volatile NativeActionPolicy policyOverride;
    static volatile java.util.function.Predicate<UUID> onlineOverride;
    static volatile java.util.function.Function<NativeEffectFlow.Actor, BlockPos> lookOverride;
    static volatile RuntimeException journalFault;

    /** Test seam: forget the flow of this server (a "restart": the in-memory evidence is gone, the ledger is not). */
    static void forgetFlow(MinecraftServer server) {
        NativeEffectFlow flow = FLOWS.remove(server);
        if (flow != null) flow.shutdown();
    }

    static void clearOverrides() {
        backendOverride = null; policyOverride = null; onlineOverride = null; lookOverride = null; journalFault = null;
    }

    private NativeEffectRuntime() {}

    // ------------------------------------------------------------------------------------------------ public surface
    static boolean pending(MinecraftServer server, UUID actorId) {
        NativeEffectFlow flow = FLOWS.get(server);
        return flow != null && flow.pending(actorId);
    }

    static boolean pendingProfile(MinecraftServer server, UUID profileId) {
        NativeEffectFlow flow = FLOWS.get(server);
        return flow != null && flow.pendingProfile(profileId);
    }

    static NativeEffectFlow flow(MinecraftServer server) {
        return FLOWS.computeIfAbsent(server, s -> new NativeEffectFlow(new ForgeEnv(s)));
    }

    /** Server thread. Never throws; the future always completes with a typed result. */
    static CompletableFuture<NativeEffectFlow.Result> preview(MinecraftServer server, UUID requester, String npc, NativeEffectFlow.Action action) {
        try {
            ServerThread.require(server, "native action");
            return flow(server).preview(requester, npc, action);
        } catch (RuntimeException e) {
            return failed();
        }
    }

    static CompletableFuture<NativeEffectFlow.Result> confirm(MinecraftServer server, UUID requester, String npc, String nonce) {
        try {
            ServerThread.require(server, "native action");
            return flow(server).confirm(requester, npc, nonce);
        } catch (RuntimeException e) {
            return failed();
        }
    }

    static CompletableFuture<NativeEffectFlow.Result> cancel(MinecraftServer server, UUID requester, String npc) {
        try {
            ServerThread.require(server, "native action");
            return CompletableFuture.completedFuture(flow(server).cancel(requester, npc));
        } catch (RuntimeException e) {
            return failed();
        }
    }

    static CompletableFuture<NativeEffectFlow.Result> reconcile(MinecraftServer server, UUID requester, String npc) {
        try {
            ServerThread.require(server, "native action");
            return flow(server).reconcile(requester, npc);
        } catch (RuntimeException e) {
            return failed();
        }
    }

    private static CompletableFuture<NativeEffectFlow.Result> failed() {
        return CompletableFuture.completedFuture(new NativeEffectFlow.Result(NativeEffectFlow.Kind.ERROR,
                "Native action unavailable; nothing was written.", ""));
    }

    // ------------------------------------------------------------------------------------------------ events
    static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        NativeEffectFlow flow = FLOWS.get(event.getServer());
        if (flow != null) flow.tick();
    }

    static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        NativeEffectFlow flow = FLOWS.remove(server);
        if (flow != null) flow.shutdown();
        Lanes lanes = LANES.remove(server);
        if (lanes != null) lanes.stop();
        PolicyPoller poller = POLLERS.remove(server);
        if (poller != null) poller.stop();
    }

    /** Best-effort: tell the backend this epoch reached a save checkpoint. It narrows crash lag; it proves no disk flush. */
    static void onLevelSave(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        MinecraftServer server = level.getServer();
        NativeEffectFlow flow = FLOWS.get(server);
        if (flow == null || flow.boundSessionOrNull() == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("schema_version", 1);
        body.addProperty("world_id", C1IdentityData.get(server).worldId().toString());
        body.addProperty("session_id", flow.boundSessionOrNull().toString());
        body.addProperty("epoch", WorldBindingData.get(server).epoch());
        try {
            new ForgeEnv(server).post(NativeEffectFlow.SAVED, body, false).exceptionally(e -> null);
        } catch (RuntimeException ignored) {
            // best effort only
        }
    }

    // ---- pickup witness: the Forge event the vanilla playerTouch path fires after Inventory.add ----
    private static final class Watch {
        final UUID player, entity;
        int count;
        Watch(UUID player, UUID entity) { this.player = player; this.entity = entity; }
    }
    private static volatile Watch watch;

    static void onItemPickup(PlayerEvent.ItemPickupEvent event) {
        Watch w = watch;
        if (w != null && event.getEntity() != null && event.getEntity().getUUID().equals(w.player)
                && event.getOriginalEntity() != null && event.getOriginalEntity().getUUID().equals(w.entity))
            w.count += event.getStack().getCount();
    }

    // ------------------------------------------------------------------------------------------------ transport lanes
    /** Critical messages (START, FINALIZE, HOLD, CANCEL, REPAIR, RECONCILE) have a lane of their own whose capacity is the completion slots plus a margin
     * of 8 for the slot-less REPAIR/RECONCILE messages; everything else shares a small bounded lane and may be answered with BUSY. Threads: provisional. */
    private static final class Lanes {
        final ThreadPoolExecutor critical = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(NativeEffectFlow.SLOTS + 8), r -> daemon(r, "npcortex-native-critical"), new ThreadPoolExecutor.AbortPolicy());
        final ThreadPoolExecutor normal = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), r -> daemon(r, "npcortex-native-io"), new ThreadPoolExecutor.AbortPolicy());
        void stop() { critical.shutdownNow(); normal.shutdownNow(); }
        private static Thread daemon(Runnable r, String name) { Thread t = new Thread(r, name); t.setDaemon(true); return t; }
    }

    /** GameTest seam only: the shared non-critical lane, so a test can saturate it. */
    static ThreadPoolExecutor normalLaneForTest(MinecraftServer server) { return lanes(server).normal; }

    private static Lanes lanes(MinecraftServer server) { return LANES.computeIfAbsent(server, s -> new Lanes()); }

    // ------------------------------------------------------------------------------------------------ policy poller
    private static final class PolicyPoller {
        volatile NativeActionPolicy policy = NativeActionPolicy.denyAll("native allow-list not loaded yet");
        volatile long readAt;
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "npcortex-native-policy"); t.setDaemon(true); return t; });

        PolicyPoller() { timer.scheduleWithFixedDelay(this::read, 0, 1000, TimeUnit.MILLISECONDS); }

        private void read() {
            NativeActionPolicy next;
            try {
                Path file = FMLPaths.CONFIGDIR.get().resolve(POLICY_FILE);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) next = NativeActionPolicy.denyAll("native allow-list file missing");
                else if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) next = NativeActionPolicy.denyAll("native allow-list is not a regular file");
                else if (Files.size(file) > NativeActionPolicy.MAX_BYTES) next = NativeActionPolicy.denyAll("native allow-list too large");
                else next = NativeActionPolicy.parse(Files.readAllBytes(file));
            } catch (IOException | RuntimeException e) {
                next = NativeActionPolicy.denyAll("native allow-list unreadable");
            }
            policy = next;
            readAt = System.nanoTime();
        }

        void stop() { timer.shutdownNow(); }
    }

    // ------------------------------------------------------------------------------------------------ env
    private static final class ForgeEnv implements NativeEffectFlow.Env {
        private final MinecraftServer server;

        ForgeEnv(MinecraftServer server) { this.server = server; }

        @Override public void onServerThread(Runnable task) { server.execute(task); }

        @Override public CompletableFuture<JsonObject> post(String route, JsonObject body, boolean critical) {
            final String payload = body.toString();
            final boolean record = critical && body.has("task_id")
                    && !route.equals(NativeEffectFlow.REPAIR) && !route.equals(NativeEffectFlow.RECONCILE);
            ThreadPoolExecutor lane = critical ? lanes(server).critical : lanes(server).normal;
            try {
                final long queuedAt = System.nanoTime();
                return CompletableFuture.supplyAsync(() -> {
                    long begun = System.nanoTime();
                    try {
                        Backend override = backendOverride;
                        String reply = override != null ? override.post(route, payload, record) : send(route, body, record);
                        long done = System.nanoTime();
                        if (done - queuedAt > 500_000_000L)
                            LOG.warn("NPCortex native transport slow: {} queued {} ms, round trip {} ms", route, (begun - queuedAt) / 1_000_000L, (done - begun) / 1_000_000L);
                        return JsonParser.parseString(reply).getAsJsonObject();
                    } catch (IOException e) {
                        throw classify(e);
                    } catch (RuntimeException e) {
                        throw classify(e);
                    }
                }, lane);
            } catch (RejectedExecutionException busy) {
                // A non-critical request that never left the queue is a certain "nothing was sent" (no ambiguous reserve, no cancel).
                return CompletableFuture.failedFuture(critical ? new IllegalStateException("native transport lane full")
                        : new NativeEffectFlow.BackendRefusal("the native transport is busy; nothing was sent, try again"));
            }
        }

        /** Worker thread only: token file, config and outbox file I/O never run on the server thread. */
        private String send(String route, JsonObject body, boolean record) throws IOException {
            String token = CPlusLocalAuth.readToken();
            URI base = CPlusF1Delivery.safeBackend(NPCortexConfig.BACKEND_URL.get());
            return record ? CPlusOutbox.send(CPlusOutbox.directory(server), base, token, route, body)
                    : CPlusTransport.post(base.resolve(route), token, body.toString(), 5, 24000);
        }

        /** HTTP 4xx means the backend ANSWERED no; everything else is an unknown transport outcome. */
        private static RuntimeException classify(Throwable error) {
            for (Throwable t = error; t != null; t = t.getCause())
                if (t instanceof CPlusTransport.Rejected r && r.status >= 400 && r.status < 500)
                    return new NativeEffectFlow.BackendRefusal(r.getMessage());
            return error instanceof RuntimeException r ? r : new IllegalStateException(error);
        }

        private C1IdentityData.Entry entry(String name) { return C1IdentityData.get(server).find(name); }

        private C1PlayerAvatar avatar(NativeEffectFlow.Actor a) { return C1P0Runtime.activeAvatar(server, a.name()); }

        @Override public NativeEffectFlow.Actor resolve(String npcName) {
            if (npcName == null) return null;
            C1IdentityData.Entry identity = entry(npcName);
            C1PlayerAvatar avatar = identity == null ? null : C1P0Runtime.activeAvatar(server, npcName);
            if (identity == null || avatar == null || avatar.isRemoved() || !avatar.isAlive() || !avatar.getUUID().equals(identity.profileId())
                    || !C1P1CLifecycleRuntime.mayRestore(server, identity.profileId()))
                return null;
            String dimension = avatar.serverLevel().dimension().location().toString();
            if (!dimension.equals(identity.dimension())) return null;
            return new NativeEffectFlow.Actor(identity.character(), C1IdentityData.get(server).worldId(), identity.actorId(),
                    identity.profileId(), dimension, CPlusF2WorldSampler.session(server), avatar.getId());
        }

        @Override public String ownershipBlock(NativeEffectFlow.Actor a) {
            if (WorldEffectRuntime.pending(server, a.actorId())) return "no-op world action active";
            if (C2AgentRuntime.hasActiveActor(a.actorId())) return "C2 goal active";
            if (C2FollowRuntime.hasActiveActor(a.actorId())) return "Follow active";
            if (CPlusE3G2Runtime.pending(server, a.actorId())) return "E3 G2 admission pending";
            if (CPlusE3G3Runtime.pending(server, a.actorId())) return "E3 G3 operation pending";
            if (CPlusTransportRecovery.busy(server, a.actorId())) return "transport recovery busy";
            if (C1P1MotionRuntime.c2Receipt(a.profileId()).active()) return "C1/C2 motion receipt active";
            C2ActionJournal.Row old = C2ActionJournal.get(server).find(a.actorId());
            return old != null && old.uncertain() ? "C2 journal unresolved" : null;
        }

        @Override public NativeActionPolicy policy(boolean fresh) {
            NativeActionPolicy forced = policyOverride;
            if (forced != null) return forced;
            PolicyPoller poller = POLLERS.computeIfAbsent(server, s -> new PolicyPoller());
            if (poller.readAt == 0L) return NativeActionPolicy.denyAll("native allow-list not loaded yet");
            if (System.nanoTime() - poller.readAt > POLICY_MAX_AGE_NANOS) return NativeActionPolicy.denyAll("native allow-list snapshot is stale");
            return poller.policy;
        }

        @Override public boolean online(UUID player) {
            java.util.function.Predicate<UUID> forced = onlineOverride;
            if (forced != null) return forced.test(player);
            return server.isRunning() && server.getPlayerList().getPlayer(player) != null;
        }

        @Override public long tick() { return server.overworld().getGameTime(); }

        @Override public UUID newId() { return UUID.randomUUID(); }

        @Override public void notifyRequester(UUID requester, String text) {
            ServerPlayer p = server.getPlayerList().getPlayer(requester);
            LOG.info("NPCortex native: {}", text);
            if (p != null) p.sendSystemMessage(Component.literal(text));
        }

        // ---- binding ----
        @Override public JsonObject bindingRequest(NativeEffectFlow.Actor actor) {
            JsonObject b = new JsonObject();
            b.addProperty("schema_version", 1);
            b.addProperty("world_id", actor.world().toString());
            b.addProperty("folder_key", folderKey());
            b.addProperty("epoch", WorldBindingData.get(server).epoch());
            b.addProperty("session_id", actor.session().toString());
            b.addProperty("contract", NativeEffectFlow.CONTRACT);
            return b;
        }

        private String folderKeyCache;

        private String folderKey() {
            if (folderKeyCache != null) return folderKeyCache;       // resolved once: toRealPath is file I/O and must not vary between calls
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            String key = NativeWorldKey.folderKey(root);
            LOG.info("NPCortex native: world binding folder key {} (path {})", key, root);   // the operator needs it for native-rebind.ps1
            return folderKeyCache = key;
        }

        @Override public void bindingAdvanced(long epoch) { WorldBindingData.get(server).setEpoch(epoch); }

        // ---- break ----
        @Override public NativeEffectFlow.Pick<NativeEffectFlow.BlockRef> pickBlock(UUID requester, NativeEffectFlow.Actor actor) {
            final ServerLevel level;
            final BlockPos pos;
            java.util.function.Function<NativeEffectFlow.Actor, BlockPos> forced = lookOverride;       // GameTest seam only
            if (forced != null) {
                C1PlayerAvatar who = avatar(actor);
                if (who == null) return NativeEffectFlow.Pick.no("The NPC is not live.");
                level = who.serverLevel();
                pos = forced.apply(actor);
            } else {
                ServerPlayer p = server.getPlayerList().getPlayer(requester);
                if (p == null) return NativeEffectFlow.Pick.no("The requester is not online.");
                HitResult hit = p.pick(8.0, 1.0f, false);
                if (hit.getType() != HitResult.Type.BLOCK) return NativeEffectFlow.Pick.no("Look at a block (within 8 blocks), then press Break.");
                level = p.serverLevel();
                if (!level.dimension().location().toString().equals(actor.dimension()))
                    return NativeEffectFlow.Pick.no("You and the NPC are in different dimensions.");
                pos = ((BlockHitResult) hit).getBlockPos();
            }
            BlockState state = level.getBlockState(pos);
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            NativeEffectFlow.BlockRef ref = new NativeEffectFlow.BlockRef(actor.dimension(), pos.getX(), pos.getY(), pos.getZ(), id,
                    WorldActionPolicy.sha(state.toString()));
            C1PlayerAvatar av = avatar(actor);
            float rate = av == null ? 0f : state.getDestroyProgress(av, level, pos);
            String time = rate > 0f ? String.format("about %.1f s of mining", Math.ceil(1.0 / rate) / 20.0) : "cannot be mined with the current tool";
            return new NativeEffectFlow.Pick<>(ref, "", "break " + id + " at " + pos.toShortString() + " (" + time + ", tool: "
                    + (av == null ? "?" : toolId(av)) + ").");
        }

        @Override public String breakCheck(NativeEffectFlow.Actor actor, NativeEffectFlow.BlockRef ref) {
            C1PlayerAvatar av = avatar(actor);
            if (av == null || av.isRemoved()) return "the NPC is not live";
            if (!av.gameMode.isSurvival()) return "the NPC is not in survival mode";
            ServerLevel level = av.serverLevel();
            if (!level.dimension().location().toString().equals(ref.dimension())) return "the NPC is in another dimension";
            BlockPos pos = new BlockPos(ref.x(), ref.y(), ref.z());
            if (!level.hasChunkAt(pos)) return "the chunk is not loaded";
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return "the target is air";
            if (state.hasBlockEntity()) return "the block has a block entity (not supported in this milestone)";
            if (!state.getFluidState().isEmpty()) return "the block holds a fluid";
            if (!level.mayInteract(av, pos)) return "the position is protected (spawn protection or world border)";
            if (!av.canReach(pos, 0.5)) return "out of reach: stand next to the block";
            Vec3 eye = av.getEyePosition();
            BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, av));
            if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(pos))
                return "no line of sight to the block (" + level.getBlockState(hit.getBlockPos()).getBlock() + " at " + hit.getBlockPos().toShortString() + " is in the way; eye "
                        + String.format("%.2f,%.2f,%.2f", eye.x, eye.y, eye.z) + "; feet y " + String.format("%.2f", av.getY())
                        + ", pose " + av.getPose() + ")";
            if (!(state.getDestroyProgress(av, level, pos) > 0f)) return "the block cannot be mined with the current tool";
            if (!state.canHarvestBlock(level, pos, av)) return "the held tool would yield no drops";
            return null;
        }

        @Override public String blockState(NativeEffectFlow.BlockRef ref) {
            ServerLevel level = level(ref.dimension());
            BlockPos pos = new BlockPos(ref.x(), ref.y(), ref.z());
            return level == null || !level.hasChunkAt(pos) ? null : WorldActionPolicy.sha(level.getBlockState(pos).toString());
        }

        private ServerLevel level(String dimension) {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        }

        @Override public NativeEffectFlow.MineStep mineStep(NativeEffectFlow.Actor actor, NativeEffectFlow.BlockRef ref) {
            C1PlayerAvatar av = avatar(actor);
            if (av == null || av.isRemoved()) return new NativeEffectFlow.MineStep(0f, "", "the NPC is not live");
            if (!av.gameMode.isSurvival()) return new NativeEffectFlow.MineStep(0f, "", "the game mode changed");
            ServerLevel level = av.serverLevel();
            BlockPos pos = new BlockPos(ref.x(), ref.y(), ref.z());
            if (!level.hasChunkAt(pos)) return new NativeEffectFlow.MineStep(0f, "", "the chunk is not loaded");
            if (level.getGameTime() % 6 == 0) av.swing(InteractionHand.MAIN_HAND, true);
            return new NativeEffectFlow.MineStep(level.getBlockState(pos).getDestroyProgress(av, level, pos), toolFingerprint(av), null);
        }

        @Override public void crack(NativeEffectFlow.Actor actor, NativeEffectFlow.BlockRef ref, int stage) {
            ServerLevel level = level(ref.dimension());
            if (level != null) level.destroyBlockProgress(actor.incarnation(), new BlockPos(ref.x(), ref.y(), ref.z()), stage);
        }

        @Override public NativeEffectFlow.BreakOutcome executeBreak(NativeEffectFlow.Started marker, NativeEffectFlow.Actor actor,
                                                                    NativeEffectFlow.BlockRef ref, UUID attempt) {
            if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker");
            C1PlayerAvatar av = avatar(actor);
            ServerLevel level = av.serverLevel();
            BlockPos pos = new BlockPos(ref.x(), ref.y(), ref.z());
            String beforeId = id(level.getBlockState(pos));
            String toolBefore = toolId(av);
            String neighbours = neighbourhood(level, pos);
            Set<UUID> baseline = itemUuids(level, pos);
            ListTag invBefore = av.getInventory().save(new ListTag());
            boolean threw = false, returned = false;
            try {
                returned = av.gameMode.destroyBlock(pos);
            } catch (Throwable t) {
                threw = true;
                LOG.error("NPCortex native break threw for attempt {}: the world may have changed", attempt, t);
            }
            String afterId = id(level.getBlockState(pos));
            String toolAfter = toolId(av);
            boolean toolBroke = !toolBefore.equals("minecraft:air") && av.getMainHandItem().isEmpty();
            List<NativeEffectFlow.Drop> drops = new ArrayList<>();
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0)))
                if (!baseline.contains(ie.getUUID()) && drops.size() < 16)
                    drops.add(new NativeEffectFlow.Drop(ie.getUUID(), itemId(ie.getItem()), Math.max(1, ie.getItem().getCount())));
            boolean collateral = !neighbours.equals(neighbourhood(level, pos));
            NativeEffectFlow.Journal journal = NativeEffectFlow.Journal.NOT_REQUIRED;
            Object token = null;
            if (!threw) {
                try {
                    ListTag invAfter = av.getInventory().save(new ListTag());
                    token = new InventoryToken(invAfter, av.getInventory().selected);
                    if (!invBefore.equals(invAfter)) journal = commit(av, attempt, "native-break|" + attempt, "break " + beforeId + " -> " + afterId);
                } catch (RuntimeException e) {
                    journal = NativeEffectFlow.Journal.FAILED;
                    LOG.error("NPCortex native break: inventory snapshot failed", e);
                }
            }
            return new NativeEffectFlow.BreakOutcome(threw, returned, beforeId, afterId, toolBefore, toolAfter, toolBroke, drops, collateral, journal, token);
        }

        // ---- pickup ----
        @Override public NativeEffectFlow.Pick<NativeEffectFlow.ItemRef> pickItem(UUID requester, NativeEffectFlow.Actor actor) {
            C1PlayerAvatar av = avatar(actor);
            if (av == null) return NativeEffectFlow.Pick.no("The NPC is not live.");
            ServerLevel level = av.serverLevel();
            ItemEntity best = null;
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, pickupZone(av))) {
                if (!eligible(ie, av).isEmpty()) continue;
                if (best == null || ie.distanceToSqr(av) < best.distanceToSqr(av)
                        || ie.distanceToSqr(av) == best.distanceToSqr(av) && ie.getUUID().compareTo(best.getUUID()) < 0) best = ie;
            }
            if (best == null) return NativeEffectFlow.Pick.no("No pickable item within the NPC's pickup reach (move the NPC next to the drop; "
                    + "a fresh drop has a short pickup delay).");
            ItemStack stack = best.getItem();
            NativeEffectFlow.ItemRef ref = new NativeEffectFlow.ItemRef(actor.dimension(), best.getUUID(), itemId(stack), tagSha(stack),
                    stack.getCount(), best.getX(), best.getY(), best.getZ(), countMatching(av, stack));
            return new NativeEffectFlow.Pick<>(ref, "", "pick up " + stack.getCount() + " x " + itemId(stack) + " at " + best.blockPosition().toShortString() + ".");
        }

        private static AABB pickupZone(C1PlayerAvatar av) { return av.getBoundingBox().inflate(1.0, 0.5, 1.0); }

        /** Empty = eligible; otherwise why not. The vanilla playerTouch path checks the delay and the Owner field but NOT distance. */
        private static String eligible(ItemEntity ie, C1PlayerAvatar av) {
            if (ie.isRemoved() || !ie.isAlive()) return "gone: the item no longer exists";
            if (ie.hasPickUpDelay()) return "the item is not pickable yet (pickup delay)";
            CompoundTag tag = new CompoundTag();
            ie.addAdditionalSaveData(tag);
            if (tag.hasUUID("Thrower")) return "the item was thrown by a player";
            if (tag.hasUUID("Owner") && !tag.getUUID("Owner").equals(av.getUUID())) return "the item is reserved for another player";
            if (!ie.getBoundingBox().intersects(pickupZone(av))) return "the item is outside the NPC's pickup reach";
            return "";
        }

        @Override public String pickupCheck(NativeEffectFlow.Actor actor, NativeEffectFlow.ItemRef ref) {
            C1PlayerAvatar av = avatar(actor);
            if (av == null || av.isRemoved()) return "the NPC is not live";
            if (!av.gameMode.isSurvival()) return "the NPC is not in survival mode";
            ServerLevel level = av.serverLevel();
            if (!level.dimension().location().toString().equals(ref.dimension())) return "the NPC is in another dimension";
            if (!(level.getEntity(ref.entity()) instanceof ItemEntity ie) || ie.isRemoved()) return "gone: the item no longer exists";
            if (!itemId(ie.getItem()).equals(ref.itemKey())) return "gone: the stack is now a different item";
            if (ie.getItem().getCount() < 1) return "gone: the stack is empty";
            String why = eligible(ie, av);
            return why.isEmpty() ? null : why;
        }

        @Override public NativeEffectFlow.PickupOutcome executePickup(NativeEffectFlow.Started marker, NativeEffectFlow.Actor actor,
                                                                      NativeEffectFlow.ItemRef ref, UUID attempt) {
            if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker");
            C1PlayerAvatar av = avatar(actor);
            ServerLevel level = av.serverLevel();
            ItemEntity ie = (ItemEntity) level.getEntity(ref.entity());
            ItemStack proto = ie.getItem().copy();
            int entityBefore = proto.getCount();
            int pre = countMatching(av, proto);
            ListTag invBefore = av.getInventory().save(new ListTag());
            Watch w = new Watch(av.getUUID(), ie.getUUID());
            watch = w;
            boolean threw = false;
            try {
                ie.playerTouch(av);
            } catch (Throwable t) {
                threw = true;
                LOG.error("NPCortex native pickup threw for attempt {}: the item and the inventory may have changed", attempt, t);
            } finally {
                watch = null;
            }
            int entityAfter = ie.isRemoved() ? 0 : ie.getItem().getCount();
            int post = countMatching(av, proto);
            NativeEffectFlow.Journal journal = NativeEffectFlow.Journal.NOT_REQUIRED;
            Object token = null;
            boolean complete = false;
            if (!threw) {
                try {
                    ListTag invAfter = av.getInventory().save(new ListTag());
                    token = new InventoryToken(invAfter, av.getInventory().selected);
                    complete = true;
                    if (!invBefore.equals(invAfter)) journal = commit(av, attempt, "native-pickup|" + attempt,
                            "pickup " + itemId(proto) + " x" + (post - pre) + " (events " + w.count + ")");
                } catch (RuntimeException e) {
                    journal = NativeEffectFlow.Journal.FAILED;
                    LOG.error("NPCortex native pickup: inventory snapshot failed", e);
                }
            }
            return new NativeEffectFlow.PickupOutcome(threw, entityBefore, entityAfter, post - pre, w.count, pre, post, journal, complete, token);
        }

        // ---- inventory journal (accepted C1 contract: commit() unchanged, REPLAY first, no rollback after an effect) ----
        @Override public String journalPrecheck(NativeEffectFlow.Actor actor, UUID attempt, String fingerprint) {
            C1InventoryData data = C1InventoryData.get(server);
            String blocked = data.blockage(actor.profileId());
            if (blocked != null) return "the inventory SavedData is blocked";
            C1InventoryData.Record record = data.find(actor.profileId());
            if (record == null) return null;
            C1InventoryJournal.Lookup lookup = record.journal.lookup(attempt, fingerprint);
            if (lookup != C1InventoryJournal.Lookup.NEW) return "journal lookup is " + lookup;
            if (record.journal.size() >= C1InventoryJournal.MAX_RECEIPTS) return "the journal holds " + C1InventoryJournal.MAX_RECEIPTS + " receipts (migration required)";
            return null;
        }

        private NativeEffectFlow.Journal commit(C1PlayerAvatar av, UUID attempt, String fingerprint, String detail) {
            try {
                RuntimeException injected = journalFault;                                                  // GameTest seam only
                if (injected != null) throw injected;
                C1InventoryData.get(server).commit(av.getUUID(), av.getInventory(), attempt, fingerprint, detail);
                return NativeEffectFlow.Journal.COMMITTED;
            } catch (RuntimeException e) {
                LOG.error("NPCortex native: C1 journal commit failed AFTER the effect (no rollback; HOLD)", e);
                return NativeEffectFlow.Journal.FAILED;
            }
        }

        @Override public NativeEffectFlow.Repair repair(NativeEffectFlow.Actor actor, UUID attempt, String fingerprint, Object evidence) {
            if (!(evidence instanceof InventoryToken token)) return NativeEffectFlow.Repair.REFUSED;
            C1PlayerAvatar av = avatar(actor);
            if (av == null || av.isRemoved() || av.getId() != actor.incarnation()) return NativeEffectFlow.Repair.REFUSED;
            C1InventoryData data = C1InventoryData.get(server);
            if (data.blockage(actor.profileId()) != null) return NativeEffectFlow.Repair.REFUSED;
            C1InventoryData.Record record = data.find(actor.profileId());
            if (record != null) {
                C1InventoryJournal.Lookup lookup = record.journal.lookup(attempt, fingerprint);      // REPLAY is checked FIRST
                if (lookup == C1InventoryJournal.Lookup.REPLAY)
                    return record.snapshot.equals(token.post) && record.selected == token.selected
                            ? NativeEffectFlow.Repair.REPLAY_PERSISTED : NativeEffectFlow.Repair.REFUSED;
                if (lookup != C1InventoryJournal.Lookup.NEW) return NativeEffectFlow.Repair.REFUSED;
            }
            try {
                ListTag now = av.getInventory().save(new ListTag());
                if (!now.equals(token.post) || av.getInventory().selected != token.selected) return NativeEffectFlow.Repair.REFUSED;
                data.commit(av.getUUID(), av.getInventory(), attempt, fingerprint, "persist-only repair");
                return NativeEffectFlow.Repair.DONE;
            } catch (RuntimeException e) {
                return NativeEffectFlow.Repair.REFUSED;
            }
        }

        // ---- reconcile observations (read-only) ----
        @Override public String observeBlock(String dimension, int x, int y, int z) {
            ServerLevel level = level(dimension);
            BlockPos pos = new BlockPos(x, y, z);
            return level == null || !level.hasChunkAt(pos) ? null : WorldActionPolicy.sha(level.getBlockState(pos).toString());
        }

        @Override public NativeEffectFlow.PickupObservation observePickup(String dimension, UUID entity, String itemKey, int count,
                                                                          double x, double y, double z, UUID profile, UUID attempt, String fingerprint) {
            ServerLevel level = level(dimension);
            if (level == null || !level.hasChunkAt(BlockPos.containing(x, y, z))) return null;
            C1InventoryData.Record record = C1InventoryData.get(server).find(profile);
            String journal = record != null && record.journal.find(attempt) != null ? "PRESENT" : "ABSENT";
            String state;
            if (level.getEntity(entity) instanceof ItemEntity ie && !ie.isRemoved())
                state = itemId(ie.getItem()).equals(itemKey) && ie.getItem().getCount() == count ? "PRESENT_UNCHANGED" : "PRESENT_CHANGED";
            else state = "ABSENT";
            return new NativeEffectFlow.PickupObservation(journal, state);
        }

        // ---- small helpers ----
        private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }

        private static String itemId(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

        private static String toolId(C1PlayerAvatar av) {
            ItemStack tool = av.getMainHandItem();
            return tool.isEmpty() ? "minecraft:air" : itemId(tool) + "#" + tool.getDamageValue();
        }

        private static String toolFingerprint(C1PlayerAvatar av) {
            ItemStack tool = av.getMainHandItem();
            return av.getInventory().selected + "|" + toolId(av) + "|" + tagSha(tool).substring(0, 16);
        }

        private static String tagSha(ItemStack stack) {
            return WorldActionPolicy.sha(stack.getTag() == null ? "" : stack.getTag().toString());
        }

        /** Same item and same tags, as the vanilla stacking rule sees it. */
        private static int countMatching(C1PlayerAvatar av, ItemStack proto) {
            int n = 0;
            for (ItemStack s : av.getInventory().items) if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, proto)) n += s.getCount();
            for (ItemStack s : av.getInventory().offhand) if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, proto)) n += s.getCount();
            return n;
        }

        private static Set<UUID> itemUuids(ServerLevel level, BlockPos pos) {
            Set<UUID> out = new HashSet<>();
            for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3.0))) out.add(ie.getUUID());
            return out;
        }

        /** The 3x3x3 block states around the target except the target cell itself (what a break must not have disturbed). */
        private static String neighbourhood(ServerLevel level, BlockPos center) {
            StringBuilder sb = new StringBuilder();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                sb.append(level.hasChunkAt(pos) ? level.getBlockState(pos).toString() : "unloaded").append(';');
            }
            return WorldActionPolicy.sha(sb.toString());
        }
    }

    /** The exact post-effect inventory structure a persist-only repair must find unchanged (same session, same avatar). */
    private record InventoryToken(ListTag post, int selected) {}
}
