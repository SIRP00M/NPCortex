package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;
import static io.github.sirp00m.npcortex.PlayerActionGameTests.bound;
import static io.github.sirp00m.npcortex.PlayerActionGameTests.count;
import static io.github.sirp00m.npcortex.PlayerActionGameTests.fx;
import static io.github.sirp00m.npcortex.PlayerActionGameTests.identity;
import static io.github.sirp00m.npcortex.PlayerActionGameTests.idle;
import static io.github.sirp00m.npcortex.PlayerActionGameTests.journal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * PAP-G3/G5 natural-language path and skill chaining with the REAL avatar and the REAL Python ledger
 * (scripts\pap\run-pap-gametests.ps1). The chat handler is called directly with a fixed "look": a headless GameTest server has no
 * logged-in player; the ServerChatEvent adapter is a thin wrapper of the same call (NOT TESTED live).
 *
 * Walking is NOT exercised end to end here: the accepted C2 goal pipeline asks the model backend for one /decide per move, and a
 * Qwen-free C2 path would change the accepted C0/C2 contract (see docs/pap/PAP_HANDOFF.md). These tests cover everything that does
 * not need a walk, plus the conservative refusals around it.
 */
@GameTestHolder("npcortexpapreal")
@PrefixGameTestTemplate(false)
public final class PlayerChatGameTests {
    private static final String ARENA = "arena", NS = "npcortexpapreal";
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final List<String> said = new CopyOnWriteArrayList<>();

    private PlayerChatGameTests() {}

    private static boolean saidContains(String text) {
        for (String m : said) if (m.contains(text)) return true;
        return false;
    }

    private static void setup(NativeGameTests.Fx f) {
        said.clear();
        PlayerTaskRuntime.clearForTest(f.server);
        PlayerTaskRuntime.messageSink = said::add;
        PlayerActionRuntime.lookOverride = null;                    // the chat path must use its own pinned look
        NativeEffectRuntime.lookOverride = null;                    // ... and the native flow its pinned block
    }

    private static void chat(NativeGameTests.Fx f, String suffix, String text, PlayerActionRuntime.Look look) {
        need(PlayerTaskRuntime.handleChat(f.server, f.owner, f.prefix + suffix, text, () -> look), "the chat line was an NPC task: " + text);
    }

    private static void done(NativeGameTests.Fx f) {
        PlayerTaskRuntime.clearForTest(f.server);
        PlayerActionGameTests.finish(f);
    }

    @GameTest(batch = "pap_11", template = ARENA, templateNamespace = NS, timeoutTicks = 900)
    public static void chatPlacePreviewsFirstAndNeedsAnExplicitConfirm(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        setup(f);
        C1PlayerAvatar av = f.npc("a", 4, 1, 4, null);
        av.getInventory().setItem(2, new ItemStack(Items.COBBLESTONE, 4));
        BlockPos support = f.abs(5, 0, 4), cell = f.abs(5, 1, 4);
        PlayerActionRuntime.Look look = new PlayerActionRuntime.Look(support, Direction.UP);
        int[] stage = new int[1];
        long[] previewAt = new long[1];
        bound(f, "a", () -> { chat(f, "a", "place cobblestone", look); stage[0] = 1; });
        helper.succeedWhen(() -> {
            need(stage[0] >= 1, "chat sent");
            if (stage[0] == 1) {
                need(saidContains("Preview:") && saidContains("confirm"), "a preview that asks for confirmation: " + said);
                need(!saidContains("walks to"), "the target is within reach: no walk");
                need(PlayerActionRuntime.flow(f.server).slotsUsed() == 0 && !PlayerActionRuntime.pending(f.server, identity(f, av)), "a preview holds no claim and no reservation");
                previewAt[0] = f.level.getGameTime();
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                need(f.level.getGameTime() - previewAt[0] >= 60, "waiting 60 ticks WITHOUT confirming");
                need(f.level.getBlockState(cell).isAir() && count(av, Items.COBBLESTONE) == 4, "no confirm, no effect: the preview changed nothing");
                chat(f, "a", "confirm", look);
                stage[0] = 3;
            }
            need(f.level.getBlockState(cell).is(Blocks.COBBLESTONE), "after the Owner's confirm the block was placed");
            need(idle(f, av) && count(av, Items.COBBLESTONE) == 3 && journal(f, av) == 1, "verified terminal, one item, one receipt");
            LOG.info("PAPREAL chat: 'place cobblestone' -> previewed, waited 60 ticks, placed only after the Owner's confirm");
            done(f);
        });
    }

    @GameTest(batch = "pap_12", template = ARENA, templateNamespace = NS, timeoutTicks = 600)
    public static void chatCancelAfterPreviewAndAFarTargetIsRefusedConservatively(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        setup(f);
        C1PlayerAvatar av = f.npc("a", 4, 1, 4, null);
        av.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        PlayerActionRuntime.Look near = new PlayerActionRuntime.Look(f.abs(5, 0, 4), Direction.UP);
        PlayerActionRuntime.Look far = new PlayerActionRuntime.Look(f.abs(28, 0, 28), Direction.UP);
        bound(f, "a", () -> {
            chat(f, "a", "place cobblestone", near);
            need(saidContains("Preview:"), "preview: " + said);
            chat(f, "a", "cancel", near);
            need(saidContains("Cancelled"), "cancel acknowledged: " + said);
            chat(f, "a", "confirm", near);
            need(saidContains("Nothing to confirm"), "a cancelled preview cannot be confirmed: " + said);
            need(f.level.getBlockState(f.abs(5, 1, 4)).isAir() && count(av, Items.COBBLESTONE) == 4, "nothing placed");
            said.clear();
            chat(f, "a", "place cobblestone", far);                                           // beyond the accepted C2 scan window
            need(saidContains("Cannot walk there") || saidContains("walks to") || saidContains("No standing position"), "answered: " + said);
            chat(f, "a", "cancel", far);
            f.helper.runAfterDelay(20, () -> {
                need(!C2AgentRuntime.hasActiveActor(identity(f, av)) && !PlayerTaskRuntime.hasTask(f.server, identity(f, av)) && !PlayerActionRuntime.pending(f.server, identity(f, av)),
                        "no C2 goal, no task, no reservation");
                need(f.level.getBlockState(f.abs(28, 1, 28)).isAir(), "nothing placed far away");
                LOG.info("PAPREAL chat-cancel: cancel after preview, and a far target answered with: {}", said);
                done(f);
            });
        });
    }

    @GameTest(batch = "pap_13", template = ARENA, templateNamespace = NS, timeoutTicks = 600)
    public static void unreachableTargetIsRefusedWithoutSideEffects(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        setup(f);
        C1PlayerAvatar av = f.npc("a", 3, 1, 3, null);
        av.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) if (dx != 0 || dz != 0) { f.set(22 + dx, 1, 22 + dz, Blocks.STONE); f.set(22 + dx, 2, 22 + dz, Blocks.STONE); }
        PlayerActionRuntime.Look look = new PlayerActionRuntime.Look(f.abs(22, 0, 22), Direction.UP);
        bound(f, "a", () -> {
            chat(f, "a", "put cobblestone here", look);
            need(saidContains("No standing position"), "refused: " + said);
            need(!PlayerTaskRuntime.hasTask(f.server, identity(f, av)) && !C2AgentRuntime.hasActiveActor(identity(f, av)), "nothing was started");
            need(f.level.getBlockState(f.abs(22, 1, 22)).isAir() && count(av, Items.COBBLESTONE) == 4, "nothing changed");
            LOG.info("PAPREAL unreachable: refused before any movement or reservation");
            done(f);
        });
    }

    @GameTest(batch = "pap_14", template = ARENA, templateNamespace = NS, timeoutTicks = 2400)
    public static void chatBreakThenCollectChainNeedsOneConfirmPerEffect(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        setup(f);
        C1PlayerAvatar a = f.npc("a", 12, 1, 11, null);                                         // bare hands, adjacent: the drop lands inside the pickup zone
        f.set(12, 1, 12, Blocks.OAK_LOG);
        BlockPos log = f.abs(12, 1, 12);
        PlayerActionRuntime.Look look = new PlayerActionRuntime.Look(log, Direction.SOUTH);
        int[] stage = new int[1];
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "a", NativeEffectFlow.Action.BREAK).join();                  // binding
            f.helper.runAfterDelay(30, () -> { chat(f, "a", "break that log", look); stage[0] = 1; });
        });
        helper.succeedWhen(() -> {
            need(stage[0] >= 1, "chat sent");
            if (stage[0] == 1) {
                need(saidContains("Preview:") && saidContains("break"), "break preview: " + said);
                need(f.level.getBlockState(log).is(Blocks.OAK_LOG), "preview changed nothing");
                chat(f, "a", "confirm", look);
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                need(f.level.getBlockState(log).isAir(), "the log is broken after the first confirm");
                for (net.minecraft.world.entity.item.ItemEntity ie : f.drops(log, 4.0)) {               // staging only: vanilla tosses a drop up to ~1.5 blocks away
                    ie.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    ie.setPos(a.getX(), a.getY(), a.getZ());
                }
                need(saidContains("Preview: pick up"), "waiting for the chained pickup preview: " + said);
                need(f.itemCount(a, Items.OAK_LOG) == 0, "the chain only PREVIEWED the pickup; the Owner has not confirmed it: " + said);
                chat(f, "a", "yes", look);
                stage[0] = 3;
            }
            need(f.itemCount(a, Items.OAK_LOG) >= 1, "after the second confirm the drop is in the inventory");
            need(!NativeEffectRuntime.pending(f.server, identity(f, a)), "terminal reached");
            LOG.info("PAPREAL chain: break -> pickup preview -> pickup, one confirm per effect");
            done(f);
        });
    }
}
