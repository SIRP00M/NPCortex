package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * X0 vanilla chunk-tracking experiment (TEST-ONLY; production C1/C2/PAP are untouched). The case comes from the environment
 * ({@code X0_CASE}: smoke | noavatar | spawned | hook | restart) and scripts\pap\x0\run-x0.ps1 starts each case on a FRESH disposable world.
 * "Hook" means: after a controlled test-only position change the TEST calls {@code ServerChunkCache.move(avatar)}. That is
 * instrumentation, not C2 navigation. Every measurement is one "X0DATA {json}" log line. Nothing here asserts gameplay.
 * The ChunkEvent/ChunkWatchEvent handlers only count: they never touch the level.
 */
@GameTestHolder("npcortexchunkx")
@PrefixGameTestTemplate(false)
public final class ChunkTrackingExperiment {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String NS = "npcortexchunkx";

    private static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v.trim(); }
    private static int envInt(String k, int d) { return Integer.parseInt(env(k, Integer.toString(d))); }

    // ---- counters filled by event handlers (record only)
    static final AtomicLong loads = new AtomicLong(), newLoads = new AtomicLong(), unloads = new AtomicLong(), avatarWatch = new AtomicLong(), avatarUnwatch = new AtomicLong();
    static final List<Long> tickNanos = new ArrayList<>();
    static long tickStart;
    static final long T0 = System.currentTimeMillis();   // class-load time; wall time matters because the headless server ticks faster than 20 TPS while worker threads generate in real time

    public static final class Handlers {
        @SubscribeEvent public void load(ChunkEvent.Load e) { loads.incrementAndGet(); if (e.isNewChunk()) newLoads.incrementAndGet(); }
        @SubscribeEvent public void unload(ChunkEvent.Unload e) { unloads.incrementAndGet(); }
        @SubscribeEvent public void watch(ChunkWatchEvent.Watch e) { if (e.getPlayer() instanceof C1PlayerAvatar) avatarWatch.incrementAndGet(); }
        @SubscribeEvent public void unwatch(ChunkWatchEvent.UnWatch e) { if (e.getPlayer() instanceof C1PlayerAvatar) avatarUnwatch.incrementAndGet(); }
        @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase == TickEvent.Phase.START) tickStart = System.nanoTime();
            else if (tickStart != 0) { synchronized (tickNanos) { tickNanos.add(System.nanoTime() - tickStart); } }
        }
    }

    public static final class Stepper {
        final Run r;
        Stepper(Run r) { this.r = r; }
        @SubscribeEvent public void t(TickEvent.ServerTickEvent e) { if (e.phase == TickEvent.Phase.END) r.step(); }
    }

    private ChunkTrackingExperiment() {}

    // ---- measurement helpers
    interface Stage { boolean run(Run r); }   // true = stage done

    static final class Run {
        final GameTestHelper helper; final MinecraftServer server; final ServerLevel level; final String kase;
        final List<Stage> stages = new ArrayList<>(); int stage; long stageTicks, ticks;
        ChunkPos home; BlockPos farPos; C1PlayerAvatar av; String name; int markTick; int markIdx0; long markT; boolean done;
        final int maxNew = envInt("X0_MAX_NEW_CHUNKS", 2500); final double heapLimit = 0.85;
        Run(GameTestHelper h, String kase) { this.helper = h; this.server = h.getLevel().getServer(); this.level = h.getLevel(); this.kase = kase; }

        void data(String tag, JsonObject o) {
            o.addProperty("case", kase); o.addProperty("tag", tag); o.addProperty("tick", ticks); o.addProperty("wall_ms", System.currentTimeMillis() - T0);
            LOG.info("X0DATA {}", o);
        }

        JsonObject snap(ChunkPos c, int radius) {
            int loaded = 0, full = 0, ticking = 0, maxDist = 0, ring10 = 0, ring10Ticking = 0;
            var cache = level.getChunkSource();
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                int x = c.x + dx, z = c.z + dz;
                if (!cache.hasChunk(x, z)) continue;
                loaded++;
                boolean f = cache.getChunkNow(x, z) != null; if (f) full++;
                boolean t = level.isPositionEntityTicking(new BlockPos(x * 16 + 8, 64, z * 16 + 8)); if (t) ticking++;
                int d = Math.max(Math.abs(dx), Math.abs(dz)); maxDist = Math.max(maxDist, d);
                if (d <= 10) { ring10++; if (t) ring10Ticking++; }
            }
            JsonObject o = new JsonObject();
            o.addProperty("center", c.x + "," + c.z); o.addProperty("radius", radius);
            o.addProperty("loaded", loaded); o.addProperty("full_ready", full); o.addProperty("entity_ticking", ticking);
            o.addProperty("max_loaded_dist", maxDist); o.addProperty("loaded_within10", ring10); o.addProperty("ticking_within10", ring10Ticking);
            o.addProperty("level_loaded_count", cache.getLoadedChunksCount());
            o.addProperty("events_load", loads.get()); o.addProperty("events_new", newLoads.get()); o.addProperty("events_unload", unloads.get());
            o.addProperty("avatar_watch", avatarWatch.get()); o.addProperty("avatar_unwatch", avatarUnwatch.get());
            o.addProperty("heap_used_mb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);
            o.addProperty("heap_max_mb", Runtime.getRuntime().maxMemory() >> 20);
            long gc = 0, gct = 0; for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) { gc += b.getCollectionCount(); gct += b.getCollectionTime(); }
            o.addProperty("gc_count", gc); o.addProperty("gc_ms", gct);
            return o;
        }

        JsonObject mspt(int fromIndex) {
            long[] a; synchronized (tickNanos) { a = tickNanos.subList(Math.min(fromIndex, tickNanos.size()), tickNanos.size()).stream().mapToLong(Long::longValue).toArray(); }
            JsonObject o = new JsonObject(); o.addProperty("ticks", a.length);
            if (a.length == 0) return o;
            Arrays.sort(a);
            o.addProperty("p50_ms", a[a.length / 2] / 1e6); o.addProperty("p95_ms", a[(int) (a.length * 0.95)] / 1e6);
            o.addProperty("max_ms", a[a.length - 1] / 1e6); o.addProperty("mean_ms", Arrays.stream(a).average().orElse(0) / 1e6);
            return o;
        }

        void guard() {
            double used = (double) (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / Runtime.getRuntime().maxMemory();
            if (newLoads.get() > maxNew) helper.fail("X0 LIMIT: " + newLoads.get() + " new chunks exceed X0_MAX_NEW_CHUNKS=" + maxNew);
            if (used > heapLimit) helper.fail("X0 LIMIT: heap use " + used + " exceeds " + heapLimit);
        }

        void spawn() {
            name = "x0" + Long.toString(System.nanoTime() % 60466176L, 36);
            BlockPos at = farPos != null ? farPos : helper.absolutePos(new BlockPos(4, 2, 4));
            C1IdentityData.Entry saved = C1IdentityData.get(server).ensure(name, level.dimension().location().toString(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, C1IdentityData.MODE_DEFAULT, "");
            C1P0Runtime.spawnExisting(server, saved);
            av = C1P0Runtime.activeAvatar(server, name);
            if (av == null) throw new GameTestAssertException("the custom avatar did not spawn");
            av.setInvulnerable(true);   // instrumentation: a fall or lava must not end the measurement
        }

        void despawn() { if (name != null) C1P0Runtime.despawn(server, name); av = null; }

        boolean waited(long n) { return stageTicks >= n; }
        int tickNanosSize() { synchronized (tickNanos) { return tickNanos.size(); } }

        void step() {
            if (done) return;
            ticks++; guard();
            if (stage >= stages.size()) { done = true; return; }
            if (stages.get(stage).run(this)) { stage++; stageTicks = 0; } else stageTicks++;
        }
    }

    // ---- stage builders
    static Stage sample(String tag, long afterTicks, int radius) {
        return r -> { if (!r.waited(afterTicks)) return false; r.data(tag, r.snap(r.home, radius)); return true; };
    }

    static Stage sampleAvatar(String tag, long afterTicks, int radius) {
        return r -> { if (!r.waited(afterTicks)) return false; r.data(tag, r.snap(r.av == null ? r.home : r.av.chunkPosition(), radius)); return true; };
    }

    static Stage config() {
        return r -> {
            JsonObject o = new JsonObject();
            var pl = r.server.getPlayerList();
            o.addProperty("harness_view_distance_before", pl.getViewDistance()); o.addProperty("harness_sim_distance_before", pl.getSimulationDistance());
            pl.setViewDistance(envInt("X0_VIEW", 10)); pl.setSimulationDistance(envInt("X0_SIM", 10));   // the GameTest server has view 0; emulate a default dedicated server
            o.addProperty("player_list_view_distance", pl.getViewDistance()); o.addProperty("player_list_sim_distance", pl.getSimulationDistance());
            o.addProperty("server_class", r.server.getClass().getName());
            o.addProperty("players_online", pl.getPlayerCount());
            ChunkMap cm = r.level.getChunkSource().chunkMap;
            try { o.addProperty("chunkmap_view_distance", (Integer) ObfuscationReflectionHelper.getPrivateValue(ChunkMap.class, cm, "f_140126_")); }
            catch (RuntimeException ex) { o.addProperty("chunkmap_view_distance", "unreadable: " + ex); }
            o.addProperty("game_rule_spectators_generate_chunks", r.server.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS));
            o.addProperty("game_rule_do_mob_spawning", r.server.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING));
            o.addProperty("difficulty", r.server.getWorldData().getDifficulty().name());
            o.addProperty("max_heap_mb", Runtime.getRuntime().maxMemory() >> 20);
            o.addProperty("hook_loaded", true);
            r.data("config", o);
            return true;
        };
    }

    static Stage mark() { return r -> { r.markT = r.ticks; r.markIdx0 = r.tickNanosSize(); return true; }; }

    static Stage sampleAt(String tag, long t, boolean avatarCentered) {
        return r -> { if (r.ticks - r.markT < t) return false; JsonObject o = r.snap(avatarCentered && r.av != null ? r.av.chunkPosition() : r.home, 16); o.add("mspt_since_mark", r.mspt(r.markIdx0)); r.data(tag, o); return true; };
    }

    /** Pick a point far from the world spawn (which keeps radius-11 chunks loaded) and the GameTest arena, and load ONE chunk there with a vanilla forceload ticket. */
    static Stage farBootstrap(int blocks) {
        return new Stage() {
            boolean started;
            public boolean run(Run r) {
                if (!started) {
                    BlockPos o = r.helper.absolutePos(new BlockPos(4, 2, 4));
                    r.home = new ChunkPos((o.getX() + blocks) >> 4, o.getZ() >> 4);
                    r.level.setChunkForced(r.home.x, r.home.z, true); started = true; return false;
                }
                if (r.stageTicks > 1200) r.helper.fail("X0: the far bootstrap chunk never became FULL + entity ticking");
                if (r.level.getChunkSource().getChunkNow(r.home.x, r.home.z) == null || !r.level.isPositionEntityTicking(new BlockPos(r.home.getMiddleBlockX(), 64, r.home.getMiddleBlockZ()))) return false;
                int y = r.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, r.home.getMiddleBlockX(), r.home.getMiddleBlockZ());
                r.farPos = new BlockPos(r.home.getMiddleBlockX(), y, r.home.getMiddleBlockZ());
                JsonObject d = new JsonObject(); d.addProperty("far_chunk", r.home.x + "," + r.home.z); d.addProperty("far_pos", r.farPos.toShortString());
                d.addProperty("blocks_from_arena", blocks); r.data("far_bootstrap", d); return true;
            }
        };
    }

    static Stage unforceFar() { return r -> { r.level.setChunkForced(r.home.x, r.home.z, false); JsonObject d = new JsonObject(); d.addProperty("unforced", true); r.data("far_unforced", d); return true; }; }

    static Stage farBootstrapRestart() {
        return r -> {
            BlockPos o = r.helper.absolutePos(new BlockPos(4, 2, 4)); r.home = new ChunkPos((o.getX() + 640) >> 4, o.getZ() >> 4);
            for (C1IdentityData.Entry e : C1IdentityData.get(r.server).all()) {
                C1PlayerAvatar a = C1P0Runtime.activeAvatar(r.server, e.character());
                JsonObject d = new JsonObject(); d.addProperty("character", e.character()); d.addProperty("identity_active", e.active());
                d.addProperty("saved_pos", e.x() + "," + e.y() + "," + e.z()); d.addProperty("avatar_present", a != null);
                if (a != null) { d.addProperty("avatar_chunk", a.chunkPosition().x + "," + a.chunkPosition().z); if (r.av == null) { r.av = a; r.name = e.character(); } }
                r.data("restart_identity", d);
            }
            return true;
        };
    }

    /** Sample every 100 ticks (avatar-centred) until the loaded count is unchanged for 3 samples or maxTicks pass: the ring has finished growing. */
    static Stage settleSeries(String tag, int maxTicks) {
        return new Stage() {
            int last = -1, same;
            public boolean run(Run r) {
                if (r.stageTicks % 100 != 99 && r.stageTicks < maxTicks) return false;
                JsonObject o = r.snap(r.av != null ? r.av.chunkPosition() : r.home, 16);
                o.addProperty("stage_ticks", r.stageTicks + 1); o.add("mspt_since_mark", r.mspt(r.markIdx0)); r.data(tag, o);
                int loaded = o.get("loaded").getAsInt(); same = loaded == last ? same + 1 : 0; last = loaded;
                return same >= 3 || r.stageTicks + 1 >= maxTicks;
            }
        };
    }

    /** Phase 2: REAL C2 navigation (navigate / navigateLong through the real backend and a stand-in model server) with the move() notifier on or off. */
    static Stage c2Nav(int[] dists, boolean hook) {
        return new Stage() {
            int idx; boolean started; long startTick, startWall; ChunkPos lastChunk; BlockPos target; int moves, unloadedTicks, minW10 = Integer.MAX_VALUE, waitReady;
            public boolean run(Run r) {
                ChunkPos now = r.av.chunkPosition();
                if (hook && lastChunk != null && !now.equals(lastChunk)) { r.level.getChunkSource().move(r.av); moves++; }   // the notifier: ChunkMap learns about the new section
                lastChunk = now;
                if (started && !r.level.hasChunkAt(r.av.blockPosition())) unloadedTicks++;
                if (idx >= dists.length) return true;
                if (!started) {
                    int d = dists[idx], tx = (int) Math.floor(r.av.getX()) + d, tz = (int) Math.floor(r.av.getZ());
                    if (r.level.getChunkSource().getChunkNow(tx >> 4, tz >> 4) == null) {      // readiness BEFORE the request
                        if (++waitReady > 600) { JsonObject o = new JsonObject(); o.addProperty("dist", d); o.addProperty("result", "TARGET_CHUNK_NOT_READY"); r.data("c2nav_skip", o); idx++; waitReady = 0; }
                        return false;
                    }
                    waitReady = 0;
                    // instrumentation (disposable world): a flat stone strip with air above it from here to just past the target, built only where chunks are FULL
                    int sx = (int) Math.floor(r.av.getX()), sz = (int) Math.floor(r.av.getZ()), y = (int) Math.floor(r.av.getY());
                    for (int cx = sx >> 4; cx <= (tx + 3) >> 4; cx++) if (r.level.getChunkSource().getChunkNow(cx, sz >> 4) == null) { if (++waitReady > 600) { JsonObject o = new JsonObject(); o.addProperty("dist", d); o.addProperty("result", "STRIP_CHUNK_NOT_READY"); r.data("c2nav_skip", o); idx++; waitReady = 0; } return false; }
                    for (int x = sx - 1; x <= tx + 3; x++) for (int z = sz - 5; z <= sz + 5; z++) {
                        r.level.setBlock(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                        for (int dy = 0; dy < 4; dy++) r.level.setBlock(new BlockPos(x, y + dy, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                    target = new BlockPos(tx, y, tz);
                    String msg = d <= 5 ? C2AgentRuntime.navigate(r.server, r.name, tx, y, tz) : C2AgentRuntime.navigateLong(r.server, r.name, tx, y, tz);
                    JsonObject o = new JsonObject(); o.addProperty("dist", d); o.addProperty("hook", hook); o.addProperty("request_reply", msg); o.addProperty("target", target.toShortString());
                    boolean queued = msg.contains("queued");
                    o.addProperty("accepted", queued); r.data("c2nav_request", o);
                    if (!queued) { idx++; return false; }
                    started = true; startTick = r.ticks; startWall = System.currentTimeMillis(); moves = 0; unloadedTicks = 0; minW10 = Integer.MAX_VALUE;
                    return false;
                }
                if (r.ticks % 10 == 0) minW10 = Math.min(minW10, r.snap(r.av.chunkPosition(), 12).get("loaded_within10").getAsInt());
                String st = C2AgentRuntime.status(r.server, r.name);
                boolean active = st.contains(" profile=");
                if (active && r.ticks - startTick < 6000) return false;
                JsonObject o = new JsonObject(); o.addProperty("dist", dists[idx]); o.addProperty("hook", hook); o.addProperty("status", st);
                o.addProperty("elapsed_ticks", r.ticks - startTick); o.addProperty("elapsed_wall_ms", System.currentTimeMillis() - startWall);
                o.addProperty("final_distance_to_target", Math.sqrt(r.av.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5)));
                o.addProperty("move_calls", moves); o.addProperty("ticks_avatar_in_unloaded_chunk", unloadedTicks); o.addProperty("min_loaded_within10_during_walk", minW10 == Integer.MAX_VALUE ? -1 : minW10);
                o.addProperty("timed_out", active);
                r.data("c2nav_result", o);
                started = false; idx++;
                return false;
            }
        };
    }

    /** Instrumentation in the DISPOSABLE world: a flat stone strip with air above it, so a C2 navigation failure is not just "the spawn point was inside a tree". */
    static Stage buildStrip(int length) {
        return r -> {
            int y = r.farPos.getY(), x0 = r.farPos.getX() - 2, z0 = r.farPos.getZ() - 5;
            for (int x = x0; x <= x0 + length; x++) for (int z = z0; z <= z0 + 10; z++) {
                r.level.setBlock(new BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 4; dy++) r.level.setBlock(new BlockPos(x, y + dy, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            }
            r.av.moveTo(r.farPos.getX() + 0.5, y, r.farPos.getZ() + 0.5, 0f, 0f); r.av.setDeltaMovement(Vec3.ZERO);
            r.level.getChunkSource().move(r.av);
            JsonObject o = new JsonObject(); o.addProperty("strip_x", x0 + ".." + (x0 + length)); o.addProperty("floor_y", y - 1); r.data("strip_built", o);
            return true;
        };
    }

    static Stage spawnStage() { return r -> { r.spawn(); r.markTick = r.tickNanosSize(); return true; }; }

    static Stage hookMove(String tag) {
        return r -> {
            long t0 = System.nanoTime();
            if (!env("X0_NOMOVE", "0").equals("1")) r.level.getChunkSource().move(r.av);
            JsonObject o = new JsonObject(); o.addProperty("nomove", env("X0_NOMOVE", "0")); o.addProperty("move_call_ms", (System.nanoTime() - t0) / 1e6);
            r.data(tag, o);
            return true;
        };
    }

    /** One controlled boundary crossing per chunk: wait for readiness BEFORE crossing, cross, call move, let the ring settle. */
    static Stage crossings(int steps) {
        return new Stage() {
            int k; int phase; int settleSame; int lastLoaded = -1; int markIdx; long t0; int readyWait;
            public boolean run(Run r) {
                if (k >= steps) return true;
                int tx = r.av.chunkPosition().x + 1, tz = r.av.chunkPosition().z;
                if (phase == 0) {                                   // readiness BEFORE the simulated crossing: the next section AND a lead window around the avatar
                    int lead = envInt("X0_LEAD", 4); ChunkPos here = r.av.chunkPosition(); boolean ok = true;
                    for (int dx = -lead; dx <= lead && ok; dx++) for (int dz = -lead; dz <= lead && ok; dz++) {
                        int cx = here.x + dx, cz = here.z + dz;
                        ok = r.level.getChunkSource().getChunkNow(cx, cz) != null && r.level.isPositionEntityTicking(new BlockPos(cx * 16 + 8, 64, cz * 16 + 8));
                    }
                    boolean next = r.level.getChunkSource().getChunkNow(tx, tz) != null;
                    if (ok && next) { JsonObject o = new JsonObject(); o.addProperty("step", k); o.addProperty("lead", lead); o.addProperty("lead_wait_ticks", readyWait); r.data("crossing_ready", o); phase = 1; readyWait = 0; }
                    else if (++readyWait > envInt("X0_LEAD_WAIT", 1500)) {
                        JsonObject o = new JsonObject(); o.addProperty("step", k); o.addProperty("target_chunk", tx + "," + tz); o.addProperty("lead", lead);
                        o.addProperty("result", "NOT_READY_AFTER_" + (readyWait - 1) + "_TICKS"); r.data("crossing_blocked", o); return true;
                    }
                    return false;
                }
                if (phase == 1) {                                   // cross
                    int x = tx * 16 + 8, z = tz * 16 + 8;
                    int y = r.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    markIdx = r.tickNanosSize(); t0 = System.nanoTime();
                    r.av.moveTo(x + 0.5, y, z + 0.5, 0f, 0f);
                    r.av.setDeltaMovement(Vec3.ZERO);
                    long t1 = System.nanoTime();
                    if (!env("X0_NOMOVE", "0").equals("1")) r.level.getChunkSource().move(r.av);   // X0_NOMOVE=1: same position changes, NO ChunkMap notification (stale-ring control)
                    JsonObject o = new JsonObject(); o.addProperty("step", k); o.addProperty("move_call_ms", (System.nanoTime() - t1) / 1e6);
                    o.addProperty("surface_y", y); r.data("crossing_move", o);
                    phase = 2; settleSame = 0; lastLoaded = -1; return false;
                }
                if (r.stageTicks % 1 == 0 && ++settleSame < 5) return false;
                JsonObject o = r.snap(r.av.chunkPosition(), 16); o.addProperty("step", k); o.add("mspt", r.mspt(markIdx));
                o.addProperty("wall_ms_since_cross", (System.nanoTime() - t0) / 1e6);
                r.data("crossing_settled", o);
                k++; phase = 0; return k >= steps;
            }
        };
    }

    static Stage itemProbe(String tag) {
        return new Stage() {
            final List<ItemEntity> items = new ArrayList<>(); final List<Integer> dists = new ArrayList<>(); long start;
            public boolean run(Run r) {
                if (items.isEmpty() && start == 0) {
                    ChunkPos c = r.av.chunkPosition();
                    for (int d : new int[] {2, 5, 8, 10, 12}) {
                        int x = c.x * 16 + 8, z = (c.z + d) * 16 + 8;
                        if (r.level.getChunkSource().getChunkNow(c.x, c.z + d) == null) { JsonObject o = new JsonObject(); o.addProperty("dist_chunks", d); o.addProperty("result", "CHUNK_NOT_FULL"); r.data(tag + "_skip", o); continue; }
                        int y = r.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
                        ItemEntity ie = new ItemEntity(r.level, x + 0.5, y, z + 0.5, new ItemStack(Items.STICK));
                        ie.setNeverPickUp(); ie.setDeltaMovement(Vec3.ZERO); r.level.addFreshEntity(ie); items.add(ie); dists.add(d);
                    }
                    start = r.ticks; return false;
                }
                if (r.ticks - start < 100) return false;
                for (int i = 0; i < items.size(); i++) {
                    JsonObject o = new JsonObject(); o.addProperty("dist_chunks", dists.get(i)); o.addProperty("age_after_100_ticks", items.get(i).getAge());
                    o.addProperty("removed", items.get(i).isRemoved());
                    o.addProperty("entity_ticking", r.level.isPositionEntityTicking(items.get(i).blockPosition()));
                    r.data(tag, o); items.get(i).discard();
                }
                return true;
            }
        };
    }

    static Stage spawnWindow(String tag, int ticksWindow) {
        return new Stage() {
            boolean started; int markIdx; java.lang.reflect.Method eligible;
            public boolean run(Run r) {
                if (!started) {
                    started = true; markIdx = r.tickNanosSize();
                    r.server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, r.server);
                    r.server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, r.server);
                    r.server.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, r.server);
                    r.level.setDayTime(18000L);
                    r.server.setDifficulty(net.minecraft.world.Difficulty.NORMAL, true);
                    JsonObject o = new JsonObject();
                    ChunkMap cm = r.level.getChunkSource().chunkMap;
                    try {
                        eligible = ObfuscationReflectionHelper.findMethod(ChunkMap.class, "m_140425_", ChunkPos.class); // anyPlayerCloseEnoughForSpawning
                        ChunkPos c = r.av == null ? r.home : r.av.chunkPosition();
                        for (int d : new int[] {0, 3, 6, 9, 12}) {
                            ChunkPos p = new ChunkPos(c.x + d, c.z);
                            o.addProperty("eligible_dist_" + d, (Boolean) eligible.invoke(cm, p));
                            o.addProperty("players_listed_dist_" + d, cm.getPlayers(p, false).size());
                        }
                    } catch (ReflectiveOperationException | RuntimeException ex) { o.addProperty("eligibility_probe", "unavailable: " + ex); }
                    if (r.av != null) { o.addProperty("avatar_spectator", r.av.isSpectator()); o.addProperty("avatar_in_level_players", r.level.players().contains(r.av)); }
                    r.data(tag + "_eligibility", o);
                    return false;
                }
                if (r.stageTicks < ticksWindow) return false;
                Vec3 at = r.av == null ? Vec3.atCenterOf(r.helper.absolutePos(BlockPos.ZERO)) : r.av.position();
                int[] cat = new int[8]; int mobs = 0;
                for (Mob m : r.level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class), x -> true)) {
                    if (m.distanceToSqr(at) > 128 * 128) continue;
                    mobs++; cat[m.getType().getCategory().ordinal() % 8]++;
                }
                JsonObject o = new JsonObject(); o.addProperty("window_ticks", ticksWindow); o.addProperty("mobs_within_128", mobs);
                o.addProperty("monsters", cat[net.minecraft.world.entity.MobCategory.MONSTER.ordinal() % 8]);
                o.addProperty("creatures", cat[net.minecraft.world.entity.MobCategory.CREATURE.ordinal() % 8]);
                o.add("mspt", r.mspt(markIdx)); r.data(tag, o);
                for (Mob m : r.level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Mob.class), x -> true)) m.discard(); // leave the world clean
                return true;
            }
        };
    }

    static Stage packetCost() {
        return r -> {
            var cache = r.level.getChunkSource(); ChunkPos c = r.av.chunkPosition(); List<LevelChunk> chunks = new ArrayList<>();
            for (int dx = -8; dx <= 8 && chunks.size() < 64; dx++) for (int dz = -8; dz <= 8 && chunks.size() < 64; dz++) {
                LevelChunk lc = cache.getChunkNow(c.x + dx, c.z + dz); if (lc != null) chunks.add(lc);
            }
            for (int i = 0; i < 8 && !chunks.isEmpty(); i++) new ClientboundLevelChunkWithLightPacket(chunks.get(0), r.level.getLightEngine(), null, null); // warm-up
            long t0 = System.nanoTime(); int n = 0;
            for (LevelChunk lc : chunks) { new ClientboundLevelChunkWithLightPacket(lc, r.level.getLightEngine(), null, null); n++; }
            JsonObject o = new JsonObject(); o.addProperty("packets_built", n);
            o.addProperty("avg_us_per_packet", n == 0 ? 0 : (System.nanoTime() - t0) / 1e3 / n);
            o.addProperty("note", "same constructor vanilla calls in ChunkMap.playerLoadedChunk; microbenchmark, not a count of packets sent");
            r.data("packet_cost", o); return true;
        };
    }

    static Stage despawnStage() { return r -> { JsonObject o = new JsonObject(); o.addProperty("region", "before despawn"); r.data("despawn", o); r.despawn(); return true; }; }

    static Stage homeStage() { return r -> { r.home = new ChunkPos(r.helper.absolutePos(new BlockPos(4, 2, 4))); return true; }; }

    // ---- the one GameTest (case selected by environment)
    @GameTest(batch = "x0", template = "arena", templateNamespace = NS, timeoutTicks = 100000)
    public static void experiment(GameTestHelper helper) {
        String kase = env("X0_CASE", "smoke");
        String end = env("X0_END", "despawn");        // despawn | quit
        int steps = envInt("X0_STEPS", 24);
        boolean spawnWatch = env("X0_SPAWNWATCH", "0").equals("1");
        Run r = new Run(helper, kase);
        Handlers h = new Handlers();
        MinecraftForge.EVENT_BUS.register(h);
        LOG.info("X0DATA {{\"case\":\"{}\",\"tag\":\"hook_loaded\",\"end\":\"{}\",\"steps\":{},\"max_new_chunks\":{}}}", kase, end, steps, r.maxNew);
        r.stages.add(homeStage()); r.stages.add(config());
        long[] gaps = {1, 20, 100, 300};
        switch (kase) {
            case "smoke" -> { r.stages.add(sample("snapshot", 20, 16)); }
            case "noavatar" -> {
                r.stages.add(farBootstrap(640)); r.stages.add(sampleAt("forced_ready", 0, false)); r.stages.add(unforceFar()); r.stages.add(mark());
                for (long t : new long[] {1, 20, 100, 300, 600}) r.stages.add(sampleAt("noavatar_t" + t, t, false));
            }
            case "spawned" -> {
                r.stages.add(farBootstrap(640)); r.stages.add(sampleAt("forced_ready", 0, false)); r.stages.add(spawnStage()); r.stages.add(unforceFar()); r.stages.add(mark());
                for (long t : gaps) r.stages.add(sampleAt("spawned_hookoff_t" + t, t, true));
                r.stages.add(settleSeries("spawned_hookoff_series", 4000));
                r.stages.add(itemProbe("item_hookoff"));
                if (spawnWatch) r.stages.add(spawnWindow("spawn_hookoff", 1200));
                if (end.equals("despawn")) { r.stages.add(despawnStage()); r.stages.add(mark()); for (long t : new long[] {1, 20, 100, 300, 600, 1200}) r.stages.add(sampleAt("despawned_t" + t, t, false)); }
            }
            case "hook" -> {
                r.stages.add(farBootstrap(640)); r.stages.add(sampleAt("forced_ready", 0, false)); r.stages.add(spawnStage()); r.stages.add(unforceFar()); r.stages.add(mark());
                for (long t : new long[] {1, 20, 100}) r.stages.add(sampleAt("spawned_before_hook_t" + t, t, true));
                r.stages.add(settleSeries("spawned_before_hook_series", 4000));
                r.stages.add(hookMove("hook_first_move")); r.stages.add(mark()); r.stages.add(sampleAt("after_first_move_t40", 40, true));
                r.stages.add(mark()); r.stages.add(crossings(steps)); r.stages.add(settleSeries("post_cross_series", 4000));
                r.stages.add(itemProbe("item_hookon")); r.stages.add(packetCost());
                if (spawnWatch) r.stages.add(spawnWindow("spawn_hookon", 1200));
                if (end.equals("despawn")) { r.stages.add(despawnStage()); r.stages.add(mark()); for (long t : new long[] {1, 20, 100, 300, 600, 1200}) r.stages.add(sampleAt("despawned_t" + t, t, false)); }
                else { r.stages.add(mark()); r.stages.add(sampleAt("end_before_quit", 40, true)); }
            }
            case "c2nav" -> {
                boolean hook = env("X0_HOOK", "1").equals("1");
                r.stages.add(farBootstrap(640)); r.stages.add(spawnStage()); r.stages.add(unforceFar()); r.stages.add(mark());
                r.stages.add(settleSeries("c2nav_ring_series", 4000));
                r.stages.add(buildStrip(110)); r.stages.add(mark()); r.stages.add(sampleAt("strip_settle", 60, true)); r.stages.add(c2Nav(new int[] {5, 20, 40, 80, 80, 80}, hook));
                r.stages.add(sampleAt("c2nav_after", 20, true));
                r.stages.add(despawnStage()); r.stages.add(mark()); r.stages.add(sampleAt("c2nav_despawned_t300", 300, false));
            }
            case "earlydespawn" -> {
                r.stages.add(farBootstrap(640)); r.stages.add(spawnStage()); r.stages.add(unforceFar()); r.stages.add(mark());
                r.stages.add(sampleAt("before_early_despawn", 100, true));
                r.stages.add(despawnStage()); r.stages.add(mark());
                for (long t : new long[] {1, 20, 100, 300, 600, 1200}) r.stages.add(sampleAt("early_despawned_t" + t, t, false));
            }
            case "restart" -> { r.stages.add(farBootstrapRestart()); r.stages.add(mark()); for (long t : new long[] {1, 40, 200}) r.stages.add(sampleAt("restart_t" + t, t, true)); r.stages.add(settleSeries("restart_series", 3000)); r.stages.add(despawnStage()); r.stages.add(mark()); for (long t : new long[] {1, 20, 300}) r.stages.add(sampleAt("restart_despawned_t" + t, t, false)); }
            default -> throw new GameTestAssertException("unknown X0_CASE " + kase);
        }
        MinecraftForge.EVENT_BUS.register(new Stepper(r));
        helper.succeedWhen(() -> {
            if (!r.done) throw new GameTestAssertException("X0 running: case " + kase + " stage " + r.stage + "/" + r.stages.size());
            JsonObject o = r.snap(r.home, 16); o.add("mspt_all", r.mspt(0)); r.data("final", o);
            MinecraftForge.EVENT_BUS.unregister(h);
        });
    }
}
