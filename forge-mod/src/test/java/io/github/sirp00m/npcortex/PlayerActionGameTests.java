package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * PAP-G5/G6 real GameTests: the REAL custom avatar places a block and uses a lever through vanilla {@code useItemOn}, against the REAL
 * Python ledger over loopback HTTP (scripts\pap\run-pap-gametests.ps1 starts it on a temporary database, then verifies that database).
 * Own namespace and run: it must never run without that backend. Not covered: a real client, chunk save, a dedicated server.
 * Every test line "PAPREAL ..." is evidence; world mutation here is real (setBlock by the product code, not by the test).
 */
@GameTestHolder("npcortexpapreal")
@PrefixGameTestTemplate(false)
public final class PlayerActionGameTests {
    private static final String ARENA = "arena", NS = "npcortexpapreal";
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private PlayerActionGameTests() {}

    // ------------------------------------------------------------------------------------------------ fixture
    static NativeGameTests.Fx fx(GameTestHelper h) {
        NativeGameTests.Fx f = NativeGameTests.Fx.real(h);
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        String region = "{\"min\":[" + (o.getX() - 60) + "," + (o.getY() - 20) + "," + (o.getZ() - 60) + "],\"max\":[" + (o.getX() + 60) + "," + (o.getY() + 40) + "," + (o.getZ() + 60) + "]}";
        NativeEffectRuntime.policyOverride = NativeActionPolicy.parse(("{\"schema_version\":1,\"entries\":[{\"id\":\"pap\",\"requester\":\"" + f.owner
                + "\",\"actions\":[\"break_block\",\"pickup_item\",\"place_block\",\"use_block\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"*\"],"
                + "\"blocks\":[\"minecraft:oak_log\",\"minecraft:cobblestone\",\"minecraft:lever\",\"minecraft:tnt\",\"minecraft:oak_door\"],"
                + "\"items\":[\"minecraft:oak_log\"],\"regions\":[" + region + "]}]}").getBytes(StandardCharsets.UTF_8));
        PlayerActionRuntime.forgetFlow(f.server);
        PlayerActionRuntime.clearOverrides();
        PlayerActionRuntime.lookOverride = actor -> looks.get(actor.name());
        looks.clear();
        return f;
    }

    static final java.util.Map<String, PlayerActionRuntime.Look> looks = new java.util.concurrent.ConcurrentHashMap<>();

    static void finish(NativeGameTests.Fx f) {
        PlayerActionRuntime.clearOverrides();
        PlayerActionRuntime.forgetFlow(f.server);
        f.finish();
    }

    /** The first request starts the binding handshake (BUSY); continue once the real backend answered. */
    static void bound(NativeGameTests.Fx f, String suffix, Runnable next) {
        f.whenSettled(() -> {
            PlayerActionRuntime.preview(f.server, f.owner, f.prefix + suffix, PlayerActionFlow.Action.PLACE).join();
            f.helper.runAfterDelay(30, next);
        });
    }

    static NativeEffectFlow.Result preview(NativeGameTests.Fx f, String suffix, PlayerActionFlow.Action action) {
        return PlayerActionRuntime.preview(f.server, f.owner, f.prefix + suffix, action).join();
    }

    private static CompletableFuture<NativeEffectFlow.Result> go(NativeGameTests.Fx f, String suffix, PlayerActionFlow.Action action) {
        NativeEffectFlow.Result p = preview(f, suffix, action);
        need(p.kind() == NativeEffectFlow.Kind.OK, "preview refused: " + p);
        return PlayerActionRuntime.confirm(f.server, f.owner, f.prefix + suffix, p.nonce());
    }

    static boolean idle(NativeGameTests.Fx f, C1PlayerAvatar av) { return !PlayerActionRuntime.pending(f.server, identity(f, av)); }

    static java.util.UUID identity(NativeGameTests.Fx f, C1PlayerAvatar av) {
        for (C1IdentityData.Entry e : C1IdentityData.get(f.server).all()) if (e.profileId().equals(av.getUUID())) return e.actorId();
        throw new net.minecraft.gametest.framework.GameTestAssertException("no identity for the avatar");
    }

    static int journal(NativeGameTests.Fx f, C1PlayerAvatar av) {
        C1InventoryData.Record r = C1InventoryData.get(f.server).find(av.getUUID());
        return r == null ? 0 : r.journal.size();
    }

    static int count(C1PlayerAvatar av, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : av.getInventory().items) if (s.is(item)) n += s.getCount();
        return n;
    }

    // ------------------------------------------------------------------------------------------------ place
    @GameTest(batch = "pap_01", template = ARENA, templateNamespace = NS, timeoutTicks = 600)
    public static void placeBlockWithToolSelectionEndToEnd(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        C1PlayerAvatar av = f.npc("a", 4, 1, 4, null);
        av.getInventory().setItem(3, new ItemStack(Items.COBBLESTONE, 4));                     // hotbar slot 4; slot 1 (selected) is empty
        BlockPos support = f.abs(5, 0, 4), cell = f.abs(5, 1, 4);
        looks.put(f.prefix + "a", new PlayerActionRuntime.Look(support, Direction.UP));
        CompletableFuture<NativeEffectFlow.Result>[] confirm = new CompletableFuture[1];
        bound(f, "a", () -> confirm[0] = go(f, "a", PlayerActionFlow.Action.PLACE));
        helper.succeedWhen(() -> {
            need(confirm[0] != null && confirm[0].isDone() && confirm[0].join().kind() == NativeEffectFlow.Kind.OK, "accepted: " + (confirm[0] == null ? null : confirm[0].getNow(null)));
            need(f.level.getBlockState(cell).is(Blocks.COBBLESTONE), "the avatar really placed the block (vanilla useItemOn)");
            need(count(av, Items.COBBLESTONE) == 3, "exactly one item was consumed: " + count(av, Items.COBBLESTONE));
            need(av.getInventory().selected == 3, "tool selection: the avatar selected the hotbar slot that holds the block");
            need(idle(f, av) && PlayerActionRuntime.flow(f.server).slotsUsed() == 0, "a CONFIRMED terminal (matching receipt hash from the real backend)");
            need(journal(f, av) == 1, "exactly one inventory receipt was committed: " + journal(f, av));
            LOG.info("PAPREAL place: EFFECT_VERIFIED cobblestone placed, selected slot {}, receipts {}", av.getInventory().selected, journal(f, av));
            finish(f);
        });
    }

    @GameTest(batch = "pap_02", template = ARENA, templateNamespace = NS, timeoutTicks = 600)
    public static void useLeverEndToEnd(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        C1PlayerAvatar av = f.npc("a", 4, 1, 4, null);
        f.set(6, 1, 4, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        BlockPos lever = f.abs(6, 1, 4);
        looks.put(f.prefix + "a", new PlayerActionRuntime.Look(lever, Direction.UP));
        CompletableFuture<NativeEffectFlow.Result>[] confirm = new CompletableFuture[1];
        bound(f, "a", () -> confirm[0] = go(f, "a", PlayerActionFlow.Action.USE));
        helper.succeedWhen(() -> {
            need(confirm[0] != null && confirm[0].isDone() && confirm[0].join().kind() == NativeEffectFlow.Kind.OK, "accepted: " + (confirm[0] == null ? null : confirm[0].getNow(null)));
            need(f.level.getBlockState(lever).getValue(LeverBlock.POWERED), "the avatar really toggled the lever (block use through vanilla useItemOn)");
            need(idle(f, av) && PlayerActionRuntime.flow(f.server).slotsUsed() == 0, "a CONFIRMED terminal");
            need(journal(f, av) == 0, "a use writes no inventory receipt");
            LOG.info("PAPREAL use: EFFECT_VERIFIED lever powered");
            finish(f);
        });
    }

    @GameTest(batch = "pap_03", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void deniedBeforeAnyLedgerWrite(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        C1PlayerAvatar av = f.npc("a", 4, 1, 4, null);
        BlockPos support = f.abs(5, 0, 4);
        looks.put(f.prefix + "a", new PlayerActionRuntime.Look(support, Direction.UP));
        bound(f, "a", () -> {
            av.getInventory().setItem(0, new ItemStack(Items.TNT, 4));                         // on the Owner list, but refused by the built-in ceiling
            NativeEffectFlow.Result tnt = preview(f, "a", PlayerActionFlow.Action.PLACE);
            need(tnt.kind() == NativeEffectFlow.Kind.DENIED && tnt.message().contains("safety ceiling"), "TNT is refused: " + tnt);
            av.getInventory().setItem(0, new ItemStack(Items.DIRT, 4));                        // not on the Owner list at all
            NativeEffectFlow.Result dirt = preview(f, "a", PlayerActionFlow.Action.PLACE);
            need(dirt.kind() == NativeEffectFlow.Kind.DENIED && dirt.message().contains("not on the allow-list"), "dirt is refused: " + dirt);
            av.getInventory().setItem(0, ItemStack.EMPTY);
            NativeEffectFlow.Result none = preview(f, "a", PlayerActionFlow.Action.PLACE);
            need(none.kind() == NativeEffectFlow.Kind.REJECTED && none.message().contains("no placeable block"), "no block in the hotbar: " + none);
            f.set(6, 1, 4, Blocks.OAK_DOOR);
            looks.put(f.prefix + "a", new PlayerActionRuntime.Look(f.abs(6, 1, 4), Direction.UP));
            NativeEffectFlow.Result door = preview(f, "a", PlayerActionFlow.Action.USE);
            need(door.kind() == NativeEffectFlow.Kind.DENIED && door.message().contains("lever and buttons only"), "a door is refused by the ceiling: " + door);
            looks.put(f.prefix + "a", new PlayerActionRuntime.Look(f.abs(5, 1, 4), Direction.UP));      // air: nothing to use
            need(preview(f, "a", PlayerActionFlow.Action.USE).kind() != NativeEffectFlow.Kind.OK, "air cannot be used");
            need(f.level.getBlockState(f.abs(5, 1, 4)).isAir() && journal(f, av) == 0, "nothing changed");
            LOG.info("PAPREAL denied: ceiling, allow-list, empty hotbar, door and air were all refused before any reservation");
            finish(f);
        });
    }

    // ------------------------------------------------------------------------------------------------ HOLD after a real effect
    private static void holdScenario(GameTestHelper helper, boolean restartBeforeReconcile) {
        NativeGameTests.Fx f = fx(helper);
        C1PlayerAvatar av = f.npc("a", 4, 1, 4, null);
        av.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        BlockPos support = f.abs(5, 0, 4), cell = f.abs(5, 1, 4);
        looks.put(f.prefix + "a", new PlayerActionRuntime.Look(support, Direction.UP));
        CompletableFuture<NativeEffectFlow.Result>[] rec = new CompletableFuture[1];
        boolean[] stage = new boolean[3];
        bound(f, "a", () -> {
            NativeEffectRuntime.journalFault = new IllegalStateException("injected: inventory persistence failed after the effect");
            go(f, "a", PlayerActionFlow.Action.PLACE);
        });
        helper.succeedWhen(() -> {
            if (!stage[0]) {
                need(f.level.getBlockState(cell).is(Blocks.COBBLESTONE), "the effect happened");
                need(PlayerActionRuntime.pending(f.server, identity(f, av)) && PlayerActionRuntime.flow(f.server).slotsUsed() == 0, "the attempt is HELD (a durable HOLD ACK released the slot), not finished");
                need(count(av, Items.COBBLESTONE) == 3 && journal(f, av) == 0, "no rollback and no duplicate: the item stays consumed, no receipt was written");
                stage[0] = true;
                NativeEffectRuntime.journalFault = null;
                NativeEffectFlow.Result again = preview(f, "a", PlayerActionFlow.Action.PLACE);
                need(again.kind() == NativeEffectFlow.Kind.BUSY || again.kind() == NativeEffectFlow.Kind.REJECTED, "the HELD actor takes no second action: " + again);
                if (restartBeforeReconcile) PlayerActionRuntime.forgetFlow(f.server);              // a restart: the in-memory evidence is gone, the ledger is not
                rec[0] = PlayerActionRuntime.reconcile(f.server, f.owner, f.prefix + "a");
            }
            need(rec[0] != null && rec[0].isDone(), "reconcile answered");
            NativeEffectFlow.Result r = rec[0].join();
            need(r.kind() == NativeEffectFlow.Kind.OK && r.state().equals("RECONCILED_EFFECT_OBSERVED"), "reconciled from the OBSERVED block: " + r);
            need(!PlayerActionRuntime.pending(f.server, identity(f, av)), "the actor is free again");
            need(count(av, Items.COBBLESTONE) == 3 && f.level.getBlockState(cell).is(Blocks.COBBLESTONE), "still exactly one block and one consumed item");
            LOG.info("PAPREAL hold: HOLD_IN_DOUBT after a real place, restart={}, reconciled {} without repeating the effect", restartBeforeReconcile, r.state());
            finish(f);
        });
    }

    @GameTest(batch = "pap_04", template = ARENA, templateNamespace = NS, timeoutTicks = 700)
    public static void inventoryFailureAfterPlaceHoldsThenReconciles(GameTestHelper helper) { holdScenario(helper, false); }

    @GameTest(batch = "pap_05", template = ARENA, templateNamespace = NS, timeoutTicks = 700)
    public static void heldPlaceSurvivesAForgeRestartAndReconcilesFromTheLedger(GameTestHelper helper) { holdScenario(helper, true); }

    // ------------------------------------------------------------------------------------------------ cross-action contention
    @GameTest(batch = "pap_06", template = ARENA, templateNamespace = NS, timeoutTicks = 900)
    public static void nativeBreakClaimBlocksAPlaceOnTheSameBlock(GameTestHelper helper) {
        NativeGameTests.Fx f = fx(helper);
        BlockPos log = NativeGameTests.standingLog(f, "a", 10, 6, null);                       // bare hands: mining takes about three seconds
        C1PlayerAvatar b = f.npc("b", 10, 1, 8, null);
        b.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        BlockPos above = log.above();
        looks.put(f.prefix + "b", new PlayerActionRuntime.Look(log, Direction.UP));            // place ON the log being broken: its cell is the support
        CompletableFuture<NativeEffectFlow.Result>[] breakConfirm = new CompletableFuture[1], placeConfirm = new CompletableFuture[1];
        f.whenSettled(() -> {
            NativeEffectRuntime.preview(f.server, f.owner, f.prefix + "a", NativeEffectFlow.Action.BREAK).join();            // binding
            PlayerActionRuntime.preview(f.server, f.owner, f.prefix + "b", PlayerActionFlow.Action.PLACE).join();
            f.helper.runAfterDelay(30, () -> {
                NativeEffectFlow.Result pb = f.preview("a", NativeEffectFlow.Action.BREAK);
                need(pb.kind() == NativeEffectFlow.Kind.OK, "break preview: " + pb);
                breakConfirm[0] = f.confirm("a", pb.nonce());
                NativeEffectFlow.Result pp = preview(f, "b", PlayerActionFlow.Action.PLACE);
                need(pp.kind() == NativeEffectFlow.Kind.OK, "place preview takes no claim: " + pp);
                f.helper.runAfterDelay(8, () -> placeConfirm[0] = PlayerActionRuntime.confirm(f.server, f.owner, f.prefix + "b", pp.nonce()));
            });
        });
        helper.succeedWhen(() -> {
            need(placeConfirm[0] != null && placeConfirm[0].isDone(), "the place confirm answered");
            NativeEffectFlow.Result r = placeConfirm[0].join();
            need(r.kind() == NativeEffectFlow.Kind.REJECTED && r.message().contains("CLAIM_CONFLICT"), "the ledger refused the place while the break owns the block: " + r);
            need(f.level.getBlockState(above).isAir(), "nothing was placed");
            need(breakConfirm[0].isDone() && breakConfirm[0].join().kind() == NativeEffectFlow.Kind.OK, "the break was accepted first");
            need(f.level.getBlockState(log).isAir() && NativeEffectRuntime.flow(f.server).slotsUsed() == 0, "the break completed normally");
            need(count(b, Items.COBBLESTONE) == 4 && journal(f, b) == 0, "the refused place consumed nothing");
            LOG.info("PAPREAL contention: native break owned the block, the place was refused with CLAIM_CONFLICT, the break completed");
            finish(f);
        });
    }

    // ------------------------------------------------------------------------------------------------ 1 / 3 / 5 / 10 NPCs
    private static void many(GameTestHelper helper, int n) {
        NativeGameTests.Fx f = fx(helper);
        List<C1PlayerAvatar> avs = new ArrayList<>();
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int x = 2 + 2 * i;
            C1PlayerAvatar av = f.npc("n" + i, x, 1, 12, null);
            av.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 4));
            avs.add(av);
            cells.add(f.abs(x + 1, 1, 12));
            looks.put(f.prefix + "n" + i, new PlayerActionRuntime.Look(f.abs(x + 1, 0, 12), Direction.UP));
        }
        List<CompletableFuture<NativeEffectFlow.Result>> confirms = new ArrayList<>();
        bound(f, "n0", () -> {
            List<NativeEffectFlow.Result> previews = new ArrayList<>();
            for (int i = 0; i < n; i++) previews.add(preview(f, "n" + i, PlayerActionFlow.Action.PLACE));
            for (int i = 0; i < n; i++) {
                need(previews.get(i).kind() == NativeEffectFlow.Kind.OK, "preview " + i + ": " + previews.get(i));
                confirms.add(PlayerActionRuntime.confirm(f.server, f.owner, f.prefix + "n" + i, previews.get(i).nonce()));         // all in the same tick
            }
        });
        helper.succeedWhen(() -> {
            need(confirms.size() == n, "all confirmed");
            for (int i = 0; i < n; i++) {
                need(confirms.get(i).isDone() && confirms.get(i).join().kind() == NativeEffectFlow.Kind.OK, "accepted " + i + ": " + confirms.get(i).getNow(null));
                need(f.level.getBlockState(cells.get(i)).is(Blocks.COBBLESTONE), "block " + i + " placed");
                need(count(avs.get(i), Items.COBBLESTONE) == 3 && journal(f, avs.get(i)) == 1, "npc " + i + " consumed exactly one item with one receipt");
                need(idle(f, avs.get(i)), "npc " + i + " reached a CONFIRMED terminal");
            }
            need(PlayerActionRuntime.flow(f.server).slotsUsed() == 0, "no slot leaked");
            LOG.info("PAPREAL many: {} NPCs placed {} blocks concurrently, each with exactly one receipt", n, n);
            finish(f);
        });
    }

    @GameTest(batch = "pap_07", template = ARENA, templateNamespace = NS, timeoutTicks = 900)
    public static void onePlayerNpcPlaces(GameTestHelper helper) { many(helper, 1); }

    @GameTest(batch = "pap_08", template = ARENA, templateNamespace = NS, timeoutTicks = 900)
    public static void threeNpcsPlaceConcurrently(GameTestHelper helper) { many(helper, 3); }

    @GameTest(batch = "pap_09", template = ARENA, templateNamespace = NS, timeoutTicks = 900)
    public static void fiveNpcsPlaceConcurrently(GameTestHelper helper) { many(helper, 5); }

    @GameTest(batch = "pap_10", template = ARENA, templateNamespace = NS, timeoutTicks = 1200)
    public static void tenNpcsPlaceConcurrently(GameTestHelper helper) { many(helper, 10); }
}
