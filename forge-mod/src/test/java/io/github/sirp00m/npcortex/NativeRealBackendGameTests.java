package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * The same real Forge executors as {@link NativeGameTests}, but the backend is the REAL Python ledger on a separate temporary
 * SQLite file, reached over real loopback HTTP through the real transport, outbox files and credential file. A flow only ever
 * reports success after a backend reply whose identity echo AND canonical receipt hash match what Forge computed, so the end of
 * each test (slot released, fence released) is itself proof of Java/Python parity over the wire. scripts\test-native-real-backend.ps1
 * starts the backend, runs this namespace and then inspects the database.
 *
 * Own namespace, own run task (runGameTestRealBackendServer): it must never run without that backend.
 * Still NOT covered: a real client, chunk save / crash windows, integrated single-player specifics.
 */
@GameTestHolder("npcortexreal")
@PrefixGameTestTemplate(false)
public final class NativeRealBackendGameTests {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final String ARENA = "arena";

    private NativeRealBackendGameTests() {}

    /** Preview + confirm as soon as the binding handshake has finished (real HTTP takes a few ticks). */
    private static void goWhenReady(NativeGameTests.Fx f, String suffix, NativeEffectFlow.Action action, int triesLeft,
                                    java.util.function.Consumer<CompletableFuture<NativeEffectFlow.Result>> then) {
        f.helper.runAfterDelay(3, () -> {
            NativeEffectFlow.Result p = f.preview(suffix, action);
            if (p.kind() == NativeEffectFlow.Kind.OK) { then.accept(f.confirm(suffix, p.nonce())); return; }
            need(triesLeft > 0 && p.kind() == NativeEffectFlow.Kind.BUSY, "preview never became ready: " + p);
            goWhenReady(f, suffix, action, triesLeft - 1, then);
        });
    }

    private static boolean idle(NativeGameTests.Fx f, String suffix) {
        var entry = C1IdentityData.get(f.server).find(f.prefix + suffix);
        return entry != null && !f.flow().pending(entry.actorId());
    }

    @GameTest(batch = "real_01", template = ARENA, templateNamespace = "npcortexreal", timeoutTicks = 900)
    public static void realBreakThenPickupOverHttp(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.real(helper);
        BlockPos target = NativeGameTests.standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        C1PlayerAvatar av = f.avatars.get(0);
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "a", NativeEffectFlow.Action.BREAK).join();          // starts the handshake
            goWhenReady(f, "a", NativeEffectFlow.Action.BREAK, 60, c -> { });
        });
        boolean[] pickedUp = new boolean[1];
        helper.succeedWhen(() -> {
            need(f.level.getBlockState(target).isAir(), "the log was broken");
            need(idle(f, "a") && f.flow().slotsUsed() == 0, "the break reached a CONFIRMED terminal (matching receipt hash from the real backend)");
            if (!pickedUp[0]) {
                pickedUp[0] = true;
                // Staging only: the NPC stands two blocks from the log, outside its pickup zone, so bring the drop to the NPC.
                for (ItemEntity ie : f.drops(target, 3.0)) { ie.setNoPickUpDelay(); ie.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO); ie.setPos(av.getX(), av.getY(), av.getZ()); }
                goWhenReady(f, "a", NativeEffectFlow.Action.PICKUP, 60, c -> { });
            }
            need(f.itemCount(av, Items.OAK_LOG) >= 1, "the drop is in the inventory");
            need(idle(f, "a") && f.flow().slotsUsed() == 0, "the pickup reached a CONFIRMED terminal");
            f.finish();
        });
    }

    @GameTest(batch = "real_02", template = ARENA, templateNamespace = "npcortexreal", timeoutTicks = 900)
    public static void realTwoNpcsOneBlockAndOneItemExactlyOneWinsEach(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.real(helper);
        BlockPos target = NativeGameTests.standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        f.npc("b", 8, 1, 6, new ItemStack(Items.DIAMOND_AXE));
        f.look.put(f.prefix + "b", target);
        List<CompletableFuture<NativeEffectFlow.Result>> confirms = new ArrayList<>();
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "a", NativeEffectFlow.Action.BREAK).join();
            f.helper.runAfterDelay(30, () -> {
                NativeEffectFlow.Result pa = f.preview("a", NativeEffectFlow.Action.BREAK), pb = f.preview("b", NativeEffectFlow.Action.BREAK);
                need(pa.kind() == NativeEffectFlow.Kind.OK && pb.kind() == NativeEffectFlow.Kind.OK, "previews: " + pa + " / " + pb);
                confirms.add(f.confirm("a", pa.nonce()));
                confirms.add(f.confirm("b", pb.nonce()));
            });
        });
        helper.succeedWhen(() -> {
            need(confirms.size() == 2 && confirms.get(0).isDone() && confirms.get(1).isDone(), "both confirms answered by the real backend");
            long ok = confirms.stream().filter(c -> c.join().kind() == NativeEffectFlow.Kind.OK).count();
            long refused = confirms.stream().filter(c -> c.join().kind() == NativeEffectFlow.Kind.REJECTED).count();
            need(ok == 1 && refused == 1, "the real ledger admits exactly one claimant: ok " + ok + " refused " + refused);
            need(confirms.stream().anyMatch(c -> c.join().kind() == NativeEffectFlow.Kind.REJECTED && c.join().message().contains("CLAIM_CONFLICT")),
                    "the loser was refused for the claim, not for another reason: " + confirms.stream().map(c -> c.join().message()).toList());
            need(f.level.getBlockState(target).isAir() && idle(f, "a") && idle(f, "b") && f.flow().slotsUsed() == 0, "one break, everything released");
            f.finish();
        });
    }

    @GameTest(batch = "real_03", template = ARENA, templateNamespace = "npcortexreal", timeoutTicks = 900)
    public static void realCancelledBreakEventThenExceptionHoldThenRestartReconcile(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.real(helper);
        BlockPos refusedLog = NativeGameTests.standingLog(f, "r", 4, 6, new ItemStack(Items.DIAMOND_AXE));
        BlockPos boomLog = NativeGameTests.standingLog(f, "h", 12, 6, new ItemStack(Items.DIAMOND_AXE));
        java.util.function.Consumer<BlockEvent.BreakEvent> deny = e -> { if (e.getPos().equals(refusedLog)) e.setCanceled(true); };
        java.util.function.Consumer<BlockEvent.BreakEvent> boom = e -> { if (e.getPos().equals(boomLog)) throw new IllegalStateException("simulated mod fault"); };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, deny);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, boom);
        int[] phase = new int[1];
        CompletableFuture<NativeEffectFlow.Result>[] rec = new CompletableFuture[1];
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "r", NativeEffectFlow.Action.BREAK).join();
            goWhenReady(f, "r", NativeEffectFlow.Action.BREAK, 60, c -> { });
            goWhenReady(f, "h", NativeEffectFlow.Action.BREAK, 60, c -> { });
        });
        helper.succeedWhen(() -> {
            if (phase[0] == 0) {
                need(idle(f, "r"), "the refused break reached a confirmed terminal");
                var held = C1IdentityData.get(f.server).find(f.prefix + "h");
                need(held != null && f.flow().pending(held.actorId()) && f.flow().slotsUsed() == 0, "the thrown break is a confirmed HOLD (fenced, slot released)");
                need(f.level.getBlockState(refusedLog).is(Blocks.OAK_LOG), "the cancelled event left the block");
                MinecraftForge.EVENT_BUS.unregister(deny);
                MinecraftForge.EVENT_BUS.unregister(boom);
                NativeEffectRuntime.forgetFlow(f.server);                           // "the game restarted"
                phase[0] = 1;
                NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "h", NativeEffectFlow.Action.BREAK).join();   // new run: bind again
            }
            if (phase[0] == 1) {
                need(f.flow().boundSessionOrNull() != null, "the new run is bound again by the real backend");
                rec[0] = NativeEffectRuntime.reconcile(f.server, f.owner, f.prefix + "h");
                phase[0] = 2;
            }
            need(rec[0].isDone() && rec[0].join().kind() == NativeEffectFlow.Kind.OK, "reconcile from observed state: " + rec[0].getNow(null));
            need("RECONCILED_PERSISTED_STATE_UNCHANGED".equals(rec[0].join().state()) || "RECONCILED_EFFECT_OBSERVED".equals(rec[0].join().state()),
                    "judged from the observed block, never NO_EFFECT: " + rec[0].join().state());
            LOG.info("REAL reconcile result: {}", rec[0].join());
            f.finish();
        });
    }

    @GameTest(batch = "real_04", template = ARENA, templateNamespace = "npcortexreal", timeoutTicks = 900)
    public static void realPickupJournalFailureThenPersistOnlyRepair(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.real(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        NativeGameTests.drop(f, 6, 6, 4, true);
        NativeEffectRuntime.journalFault = new IllegalStateException("injected journal fault");
        boolean[] asked = new boolean[1];
        CompletableFuture<NativeEffectFlow.Result>[] rec = new CompletableFuture[1];
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "a", NativeEffectFlow.Action.BREAK).join();
            goWhenReady(f, "a", NativeEffectFlow.Action.PICKUP, 60, c -> { });
        });
        helper.succeedWhen(() -> {
            var entry = C1IdentityData.get(f.server).find(f.prefix + "a");
            if (!asked[0]) need(entry != null && f.flow().pending(entry.actorId()) && f.flow().slotsUsed() == 0 && f.itemCount(av, Items.OAK_LOG) == 4, "HOLD_IN_DOUBT is confirmed by the real backend; the transfer stands");
            if (!asked[0]) {
                asked[0] = true;
                NativeEffectRuntime.journalFault = null;
                rec[0] = NativeEffectRuntime.reconcile(f.server, f.owner, f.prefix + "a");
            }
            need(rec[0].isDone() && rec[0].join().kind() == NativeEffectFlow.Kind.OK, "persist-only repair confirmed by the real backend: " + rec[0].getNow(null));
            need(!f.flow().pending(entry.actorId()) && f.itemCount(av, Items.OAK_LOG) == 4, "released, and no second pickup");
            f.finish();
        });
    }

    @GameTest(batch = "real_05", template = ARENA, templateNamespace = "npcortexreal", timeoutTicks = 1200)
    public static void realTenNpcsInParallel(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.real(helper);
        List<BlockPos> targets = new ArrayList<>();
        for (int i = 0; i < 10; i++) targets.add(NativeGameTests.standingLog(f, "n" + i, 3 + 2 * i, 8, new ItemStack(Items.DIAMOND_AXE)));
        int[] started = new int[1];
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "n0", NativeEffectFlow.Action.BREAK).join();
            f.helper.runAfterDelay(30, () -> {
                for (int i = 0; i < 10; i++) {
                    final int n = i;
                    LOG.info("REAL n{} head/feet cells {} / {}", n, f.level.getBlockState(f.abs(3 + 2 * i, 2, 6)).getBlock(), f.level.getBlockState(f.abs(3 + 2 * i, 1, 7)).getBlock());
                    f.go("n" + i, NativeEffectFlow.Action.BREAK).thenAccept(r -> { if (r.kind() != NativeEffectFlow.Kind.OK) LOG.warn("REAL n{} not accepted: {}", n, r); });
                    started[0]++;
                }
            });
        });
        double[] worst = new double[1];
        helper.onEachTick(() -> worst[0] = Math.max(worst[0], f.server.getAverageTickTime()));
        helper.succeedWhen(() -> {
            need(started[0] == 10, "ten requests sent");
            for (BlockPos t : targets) need(f.level.getBlockState(t).isAir(), "every log is gone");
            need(f.flow().slotsUsed() == 0, "all ten reached a terminal (slots released): " + f.flow().slotsUsed());
            // a HOLD also releases the slot but keeps the actor fenced, so every NPC must be idle (no hold) as well
            for (int i = 0; i < 10; i++) need(idle(f, "n" + i), "n" + i + " is not fenced by a HOLD");
            LOG.info("REAL G5 10 NPCs over HTTP: worst averageTickTime {} ms (OBSERVATION, not an acceptance)", worst[0]);
            f.finish();
        });
    }
}
