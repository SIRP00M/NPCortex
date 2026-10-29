package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * T24/T25 cost, sequentially in ONE test of its own namespace (GameTests of a namespace run in parallel and would contaminate each other).
 * For N in 0/1/3/5/10 idle avatars, in two layouts: CLUSTERED (all in the arena: the chunk rings overlap) and SPREAD (336 blocks apart
 * = 21 chunks > 2 x view distance 10: rings do not overlap, so the per-NPC ring cost is not hidden). Each configuration is measured for
 * REPS repetitions and the MEDIAN is reported. Per run: ms per server tick over 100 ticks, then the release: duration of the despawn call
 * and the longest gap between consecutive server ticks in the next 10 ticks (the X0 chunk-release stall, 307-333 ms at N=1 then).
 * GameTestServer wall clock: relative evidence only, not a dedicated-server figure, not an acceptance threshold.
 */
@GameTestHolder("npcortexparitybench")
@PrefixGameTestTemplate(false)
public final class ParityBenchGameTests {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final int REPS = 3;
    private static final int SPACING = 336;
    /** {count, spread(1)/clustered(0), survivalTick(1)/off(0)} */
    private static final int[][] CONFIG = {{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {3, 0, 1}, {3, 1, 1}, {5, 0, 1}, {5, 1, 1}, {10, 0, 1}, {10, 1, 1}, {10, 0, 0}};

    private ParityBenchGameTests() {}

    /** Longest wall-clock gap between consecutive server ticks since reset. */
    public static final class Gap {
        long last, max; boolean on;
        @SubscribeEvent public void on(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || !on) return;
            long now = System.nanoTime();
            if (last != 0) max = Math.max(max, now - last);
            last = now;
        }
    }

    @GameTest(batch = "parity_bench", template = "arena", templateNamespace = "npcortexparitybench", timeoutTicks = 24000)
    public static void t24IdleCostByCount(GameTestHelper helper) {
        Gap gap = new Gap();
        MinecraftForge.EVENT_BUS.register(gap);
        step(helper, gap, 0, 0, new double[CONFIG.length][REPS], new double[CONFIG.length][REPS], new double[CONFIG.length][REPS]);
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }

    private static void step(GameTestHelper helper, Gap gap, int i, int rep, double[][] ms, double[][] despawnMs, double[][] gapMs) {
        int[] cfg = CONFIG[i];
        C1SurvivalParity.survivalTick = cfg[2] == 1;
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        BlockPos origin = f.abs(2, 1, 2);
        List<String> names = new ArrayList<>();
        for (int k = 0; k < cfg[0]; k++) {
            String suffix = "b" + i + "_" + rep + "_" + k;
            double x = origin.getX() + 0.5 + (cfg[1] == 1 ? (double) k * SPACING : (k % 5) * 2.0), z = origin.getZ() + 0.5 + (cfg[1] == 1 ? 0 : (k / 5) * 3.0);
            var saved = C1IdentityData.get(f.server).ensure(f.prefix + suffix, f.level.dimension().location().toString(), x, origin.getY(), z, 0f,
                    C1IdentityData.MODE_DEFAULT, "");
            C1P0Runtime.spawnExisting(f.server, saved);
            names.add(f.prefix + suffix);
        }
        long[] t0 = new long[1];
        helper.runAfterDelay(100, () -> t0[0] = System.nanoTime());   // longer warm-up: the rings load/generate first
        helper.runAfterDelay(200, () -> {
            ms[i][rep] = (System.nanoTime() - t0[0]) / 1.0e6 / 100.0;
            gap.max = 0; gap.last = 0; gap.on = true;
            long d0 = System.nanoTime();
            for (String n : names) C1P0Runtime.despawn(f.server, n);
            despawnMs[i][rep] = (System.nanoTime() - d0) / 1.0e6;
        });
        helper.runAfterDelay(215, () -> {
            gap.on = false;
            gapMs[i][rep] = gap.max / 1.0e6;
            f.releaseAvatars();
            if (rep + 1 < REPS) step(helper, gap, i, rep + 1, ms, despawnMs, gapMs);
            else {
                LOG.info("PARITY|T24|BENCH npcs={}|layout={}|survivalTick={}|ms_per_tick_median={}|despawn_call_ms_median={}|max_tick_gap_after_release_ms_median={}|reps={}|runs_ms={}|harness=GameTestServer(not dedicated)",
                        cfg[0], cfg[1] == 1 ? "SPREAD" : "CLUSTERED", cfg[2] == 1, String.format("%.3f", median(ms[i])), String.format("%.1f", median(despawnMs[i])),
                        String.format("%.1f", median(gapMs[i])), REPS, java.util.Arrays.toString(ms[i]));
                if (i + 1 < CONFIG.length) step(helper, gap, i + 1, 0, ms, despawnMs, gapMs);
                else { C1SurvivalParity.survivalTick = true; MinecraftForge.EVENT_BUS.unregister(gap); helper.succeed(); }
            }
        });
    }
}
