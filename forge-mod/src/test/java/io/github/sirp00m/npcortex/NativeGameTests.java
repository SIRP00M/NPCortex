package io.github.sirp00m.npcortex;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Real Forge GameTests for the native world-interaction milestone. They run the REAL custom {@link C1PlayerAvatar}, the REAL
 * {@code ServerPlayerGameMode.destroyBlock} and {@code ItemEntity.playerTouch} paths, the REAL NativeEffectFlow with its REAL
 * Forge binding, real blocks, real drops and real item merging, in a headless GameTestServer (`gradlew runGameTestServer`).
 *
 * What is scripted: the BACKEND (an in-process ledger that applies the contract's state rules, shared with the pure self-test),
 * the requester's look direction and the requester's presence (the GameTest server has no real players).
 * What is NOT covered: the real HTTP transport and the real Python ledger (see the backend tests and the wire fixture), a real
 * client (viewer sync, rendering, the crack animation), integrated single-player specifics, chunk save/unload and crash windows.
 * Each test sits in its own batch because the seams below are static.
 */
@GameTestHolder("npcortex")
@PrefixGameTestTemplate(false)
public final class NativeGameTests {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    static final String ARENA = "arena";
    private static int counter;
    /** Names are persisted in the throw-away test world: make them unique per run so a stale stored position is never reused. */
    private static final String RUN = Long.toString(System.nanoTime() % 60466176L, 36);

    private NativeGameTests() {}

    // ----------------------------------------------------------------------------------------------------- fixture
    static final class Fx {
        final GameTestHelper helper;
        final MinecraftServer server;
        final ServerLevel level;
        final NativeEffectFlowSelfTest.Sim sim = new NativeEffectFlowSelfTest.Sim();
        final UUID owner = UUID.randomUUID();
        final java.util.Map<String, BlockPos> look = new java.util.HashMap<>();
        volatile boolean online = true;
        volatile Runnable onStart;
        final List<String> routes = new ArrayList<>();
        final List<C1PlayerAvatar> avatars = new ArrayList<>();
        final String prefix;

        final boolean real;

        Fx(GameTestHelper helper, String blocks, String items) { this(helper, blocks, items, false); }

        /** {@code real}: no scripted backend - the flow talks HTTP to the real Python backend (scripts\test-native-real-backend.ps1). */
        Fx(GameTestHelper helper, String blocks, String items, boolean real) {
            this.real = real;
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = level.getServer();
            this.prefix = "G" + RUN + (++counter);
            // The accepted native/PAP pickup fixtures stage a drop at the NPC and then run the explicit ledgered pickup; vanilla touch pickup
            // (default since D1) would take it first. Parity tests of pickup itself re-enable it AFTER building their fixture.
            C1SurvivalParity.autoPickup = false;
            // The test world is natural terrain: gravel just above the cleared arena can fall into it when disturbed and block an NPC's
            // line of sight (seen in the real-backend run and, rarely, here). Cap the arena; fixture only, no product change.
            for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) for (int y = 9; y <= 10; y++)
                helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
            // A test world that survived an earlier run may still hold item entities in this arena; they would merge with ours.
            BlockPos lo = helper.absolutePos(BlockPos.ZERO);
            for (ItemEntity stale : level.getEntitiesOfClass(ItemEntity.class, new AABB(lo, lo.offset(32, 8, 32)).inflate(2.0))) stale.discard();
            NativeEffectRuntime.clearOverrides();
            NativeEffectRuntime.forgetFlow(server);
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            String region = "{\"min\":[" + (origin.getX() - 60) + "," + (origin.getY() - 20) + "," + (origin.getZ() - 60) + "],\"max\":["
                    + (origin.getX() + 60) + "," + (origin.getY() + 40) + "," + (origin.getZ() + 60) + "]}";
            NativeEffectRuntime.policyOverride = NativeActionPolicy.parse(("{\"schema_version\":1,\"entries\":[{\"id\":\"gt\",\"requester\":\"" + owner
                    + "\",\"actions\":[\"break_block\",\"pickup_item\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"*\"],\"blocks\":" + blocks
                    + ",\"items\":" + items + ",\"regions\":[" + region + "]}]}").getBytes(StandardCharsets.UTF_8));
            NativeEffectRuntime.onlineOverride = u -> online && u.equals(owner);
            NativeEffectRuntime.lookOverride = actor -> look.get(actor.name());
            if (!real) NativeEffectRuntime.backendOverride = (route, payload, record) -> {
                String name = route.substring(route.lastIndexOf('/') + 1);
                JsonObject body = JsonParser.parseString(payload).getAsJsonObject();
                synchronized (sim) {
                    Runnable hook = onStart;
                    if (name.equals("start") && hook != null) { onStart = null; hook.run(); }
                    routes.add(name);
                    sim.sent.put(name, body.deepCopy());
                    return sim.serve(name, body).toString();
                }
            };
        }

        static Fx real(GameTestHelper h) {
            return new Fx(h, "[\"minecraft:oak_log\",\"minecraft:gravel\",\"minecraft:stone\"]", "[\"minecraft:oak_log\",\"minecraft:stick\",\"minecraft:torch\"]", true);
        }

        static Fx logs(GameTestHelper h) { return new Fx(h, "[\"minecraft:oak_log\",\"minecraft:gravel\",\"minecraft:stone\"]", "[\"minecraft:oak_log\",\"minecraft:stick\",\"minecraft:torch\"]"); }

        NativeEffectFlow flow() { return NativeEffectRuntime.flow(server); }

        /** Arena coordinates: y=1 is the first air layer ABOVE the stone floor (the GameTest places template layer 0 at relative y=1). */
        BlockPos abs(int x, int y, int z) { return helper.absolutePos(new BlockPos(x, y + 1, z)); }

        void set(int x, int y, int z, net.minecraft.world.level.block.Block block) { level.setBlock(abs(x, y, z), block.defaultBlockState(), 3); }

        void set(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) { level.setBlock(abs(x, y, z), state, 3); }

        C1PlayerAvatar npc(String suffix, int x, int y, int z, ItemStack tool) {
            String name = prefix + suffix;
            Vec3 at = Vec3.atBottomCenterOf(abs(x, y, z));
            C1IdentityData.Entry saved = C1IdentityData.get(server).ensure(name, level.dimension().location().toString(), at.x, at.y, at.z, 0f,
                    C1IdentityData.MODE_DEFAULT, "");
            C1P0Runtime.spawnExisting(server, saved);
            C1PlayerAvatar av = C1P0Runtime.activeAvatar(server, name);
            if (av == null) throw new GameTestAssertException("the custom avatar did not spawn: " + name);
            if (tool != null) av.getInventory().setItem(av.getInventory().selected, tool);
            avatars.add(av);
            return av;
        }

        /** A freshly spawned avatar runs a C1 settling session until it stands: the native flow (rightly) refuses to start then. */
        void whenSettled(Runnable next) {
            helper.runAfterDelay(1, () -> {
                boolean busy = false;
                for (C1PlayerAvatar av : avatars) busy |= C1P1MotionRuntime.c2Receipt(av.getUUID()).active();
                if (!busy) next.run();
                else if (++settleWaits > 200) throw new GameTestAssertException("the avatars never settled");
                else whenSettled(next);
            });
        }

        private int settleWaits;

        /** The first request starts the binding handshake (BUSY); the caller continues a few ticks later. */
        void bind(String suffix, Runnable next) {
            whenSettled(() -> {
                NativeEffectRuntime.preview(server, owner, prefix + suffix, NativeEffectFlow.Action.BREAK).join();
                helper.runAfterDelay(4, next);
            });
        }

        NativeEffectFlow.Result preview(String suffix, NativeEffectFlow.Action action) {
            return NativeEffectRuntime.preview(server, owner, prefix + suffix, action).join();
        }

        CompletableFuture<NativeEffectFlow.Result> confirm(String suffix, String nonce) {
            return NativeEffectRuntime.confirm(server, owner, prefix + suffix, nonce);
        }

        List<NativeEffectFlow.Result> results = new ArrayList<>();

        /** preview + confirm; returns the confirm future. */
        CompletableFuture<NativeEffectFlow.Result> go(String suffix, NativeEffectFlow.Action action) {
            NativeEffectFlow.Result p = preview(suffix, action);
            if (p.kind() != NativeEffectFlow.Kind.OK) throw new GameTestAssertException("preview refused: " + p);
            return confirm(suffix, p.nonce());
        }

        JsonObject lastSent(String route) {
            synchronized (sim) {
                JsonObject o = sim.sent.get(route);
                if (o == null) throw new GameTestAssertException("nothing was sent to /" + route + " yet");
                return o.deepCopy();
            }
        }

        String stateOfRun(int index) {
            synchronized (sim) {
                int i = 0;
                for (NativeEffectFlowSelfTest.Sim.Run r : sim.runs.values()) if (i++ == index) return r.state;
                return "NONE";
            }
        }

        int runs() { synchronized (sim) { return sim.runs.size(); } }

        int itemCount(C1PlayerAvatar av, net.minecraft.world.item.Item item) {
            int n = 0;
            for (ItemStack s : av.getInventory().items) if (s.is(item)) n += s.getCount();
            return n;
        }

        List<ItemEntity> drops(BlockPos around, double r) { return level.getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(r)); }

        void finish() {
            releaseAvatars();
            NativeEffectRuntime.clearOverrides();
            helper.succeed();
        }

        /** Despawn this fixture's avatars without ending the test (multi-step tests). */
        void releaseAvatars() {
            for (C1PlayerAvatar av : avatars) {
                try { C1P0Runtime.despawn(server, nameOf(av)); } catch (RuntimeException ignored) { }
            }
            avatars.clear();
        }

        private String nameOf(C1PlayerAvatar av) {
            for (C1IdentityData.Entry e : C1IdentityData.get(server).all()) if (e.profileId().equals(av.getUUID())) return e.character();
            return "";
        }
    }

    static void need(boolean ok, String message) {
        if (!ok) throw new GameTestAssertException(message);
    }

    // ----------------------------------------------------------------------------------------------------- G0 spike
    /** G0: can the environment run the custom avatar through the vanilla break and pickup paths at all? */
    @GameTest(batch = "nat_g0", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 200)
    public static void g0SpikeAvatarBreaksAndPicksUp(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar avatar = f.npc("spike", 6, 1, 4, null);
        BlockPos target = f.abs(6, 1, 6);
        f.set(6, 1, 6, Blocks.OAK_LOG);
        LOG.info("G0 avatar gameMode={} survival={} progress/tick={}", avatar.gameMode.getGameModeForPlayer(), avatar.gameMode.isSurvival(),
                f.level.getBlockState(target).getDestroyProgress(avatar, f.level, target));
        for (int dy = -2; dy <= 2; dy++)
            LOG.info("G0 arena column (6,{},4): {}", dy, f.level.getBlockState(f.abs(6, dy, 4)).getBlock());
        LOG.info("G0 avatar at {} (origin {}, abs(6,1,4) {})", avatar.position(), f.abs(0, 0, 0), f.abs(6, 1, 4));
        helper.runAfterDelay(30, () -> LOG.info("G0 avatar 30 ticks later at {} onGround {} motion {}", avatar.position(), avatar.onGround(), avatar.getDeltaMovement()));
        need(avatar.gameMode.destroyBlock(target) && f.level.getBlockState(target).isAir(), "destroyBlock did not remove the log");
        List<ItemEntity> drops = f.drops(target, 3.0);
        need(!drops.isEmpty(), "no item entity after the break");
        ItemEntity drop = drops.get(0);
        drop.setNoPickUpDelay();
        int before = f.itemCount(avatar, Items.OAK_LOG);
        C1SurvivalParity.autoPickup = true;   // this spike proves raw vanilla playerTouch; the fixture pinned the explicit contract
        drop.playerTouch(avatar);
        need(f.itemCount(avatar, Items.OAK_LOG) == before + 1, "playerTouch did not move the log into the inventory");
        f.finish();
    }

    @GameTest(batch = "nat_g0b", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 100)
    public static void diagSettle(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("diag", 6, 1, 4, null);
        int[] t = new int[1];
        helper.onEachTick(() -> {
            t[0]++;
            if (t[0] <= 12 || t[0] % 10 == 0)
                LOG.info("DIAG t={} y={} onGround={} motion={} receipt={} floorBelow={}", t[0], av.getY(), av.onGround(), av.getDeltaMovement().y,
                        C1P1MotionRuntime.c2Receipt(av.getUUID()), f.level.getBlockState(f.abs(6, 0, 4)).getBlock());
        });
        helper.runAfterDelay(60, () -> f.finish());
    }

    // ----------------------------------------------------------------------------------------------------- G3: break
    static BlockPos standingLog(Fx f, String suffix, int x, int z, ItemStack tool) {
        f.npc(suffix, x, 1, z - 2, tool);
        f.set(x, 1, z, Blocks.OAK_LOG);
        BlockPos pos = f.abs(x, 1, z);
        f.look.put(f.prefix + suffix, pos);
        return pos;
    }

    @GameTest(batch = "nat_b01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void breakHappyPath(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        ItemStack axe = new ItemStack(Items.DIAMOND_AXE);
        BlockPos target = standingLog(f, "a", 6, 6, axe);
        C1PlayerAvatar av = f.avatars.get(0);
        float rate = f.level.getBlockState(target).getDestroyProgress(av, f.level, target);
        long[] startedAt = new long[1];
        CompletableFuture<NativeEffectFlow.Result>[] confirm = new CompletableFuture[1];
        f.bind("a", () -> {
            confirm[0] = f.go("a", NativeEffectFlow.Action.BREAK);
            startedAt[0] = f.level.getGameTime();
        });
        helper.succeedWhen(() -> {
            need(confirm[0] != null && confirm[0].isDone() && confirm[0].join().kind() == NativeEffectFlow.Kind.OK, "accepted: " + (confirm[0] == null ? null : confirm[0].getNow(null)));
            need("EFFECT_VERIFIED".equals(f.stateOfRun(0)), "ledger terminal EFFECT_VERIFIED, now " + f.stateOfRun(0));
            long ticks = f.level.getGameTime() - startedAt[0];
            need(ticks >= Math.ceil(1.0 / rate) - 1, "mining takes game time (rate " + rate + "): " + ticks + " ticks");
            need(f.level.getBlockState(target).isAir(), "the log is gone");
            JsonObject receipt = f.lastSent("finalize");
            JsonObject evidence = receipt.getAsJsonObject("evidence");
            need(receipt.get("result").getAsString().equals("EFFECT_VERIFIED") && evidence.getAsJsonArray("drops").size() >= 1, "typed receipt with the observed drop: " + receipt);
            need(evidence.get("tool_before").getAsString().equals("minecraft:diamond_axe#0") && evidence.get("tool_after").getAsString().equals("minecraft:diamond_axe#1"),
                    "durability change is recorded: " + evidence);
            need("COMMITTED".equals(evidence.get("journal").getAsString()), "tool damage went through the C1 journal");
            UUID attempt = UUID.fromString(f.lastSent("reserve").get("attempt_id").getAsString());
            C1InventoryData.Record record = C1InventoryData.get(f.server).find(av.getUUID());
            need(record != null && record.journal.find(attempt) != null, "the journal holds the attempt id as its transaction id");
            need(f.flow().slotsUsed() == 0 && !f.flow().pending(C1IdentityData.get(f.server).find(f.prefix + "a").actorId()), "fence and slot released");
            LOG.info("G3 break happy path: {} ticks, drops {}", ticks, evidence.getAsJsonArray("drops"));
            f.finish();
        });
    }

    /** The accumulator against vanilla's own state machine: same tool, same block, same number of ticks. */
    @GameTest(batch = "nat_b02", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void miningParityWithVanilla(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos ours = standingLog(f, "o", 4, 6, new ItemStack(Items.IRON_AXE));
        f.npc("v", 12, 1, 4, new ItemStack(Items.IRON_AXE));
        f.set(12, 1, 6, Blocks.OAK_LOG);
        BlockPos theirs = f.abs(12, 1, 6);
        C1PlayerAvatar vanilla = f.avatars.get(1);
        long[] start = new long[2], end = new long[2];
        CompletableFuture<NativeEffectFlow.Result>[] confirm = new CompletableFuture[1];
        f.bind("o", () -> {
            vanilla.gameMode.handleBlockBreakAction(theirs, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.SOUTH, f.level.getMaxBuildHeight(), 0);
            start[1] = f.level.getGameTime();
            // Vanilla's SERVER never completes a break by itself: the CLIENT adds the per-tick rate until it reaches 1.0 and then sends
            // STOP_DESTROY_BLOCK (the server only re-checks a >= 0.7 threshold). Emulate that client exactly, with the same float sum.
            float rate = f.level.getBlockState(theirs).getDestroyProgress(vanilla, f.level, theirs);
            float sum = 0f;
            int n = 0;
            while (sum < 1.0f && n < 5000) { sum += rate; n++; }
            helper.runAfterDelay(n, () -> vanilla.gameMode.handleBlockBreakAction(theirs, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.SOUTH, f.level.getMaxBuildHeight(), 1));
            LOG.info("PARITY the vanilla client would send STOP after {} ticks (rate {})", n, rate);
            confirm[0] = f.go("o", NativeEffectFlow.Action.BREAK);
            start[0] = f.level.getGameTime();
        });
        helper.succeedWhen(() -> {
            if (end[1] == 0 && f.level.getBlockState(theirs).isAir()) end[1] = f.level.getGameTime();
            if (end[0] == 0 && f.level.getBlockState(ours).isAir()) end[0] = f.level.getGameTime();
            need(end[0] != 0 && end[1] != 0, "both logs are mined (ours " + end[0] + ", vanilla " + end[1] + "; ledger " + f.stateOfRun(0)
                    + "; vanilla avatar " + vanilla.position() + " block " + f.level.getBlockState(theirs).getBlock());
            long mine = end[0] - start[0], vanillaTicks = end[1] - start[1];
            LOG.info("G3 parity: accumulator {} ticks, vanilla {} ticks", mine, vanillaTicks);
            need(Math.abs(mine - vanillaTicks) <= 4, "timed mining tracks vanilla: " + mine + " vs " + vanillaTicks);
            need(mine >= 3, "iron axe on oak is not instant");
            f.finish();
        });
    }

    @GameTest(batch = "nat_b03", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void breakCollateralAndLaterChangesAreNotAttributed(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        f.set(6, 2, 6, Blocks.GRAVEL);
        f.set(7, 1, 6, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST));
        CompletableFuture<NativeEffectFlow.Result>[] confirm = new CompletableFuture[1];
        f.bind("a", () -> confirm[0] = f.go("a", NativeEffectFlow.Action.BREAK));
        helper.succeedWhen(() -> {
            need("EFFECT_VERIFIED".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            JsonObject evidence = f.lastSent("finalize").getAsJsonObject("evidence");
            need("COLLATERAL".equals(evidence.get("collateral").getAsString()), "the torch that popped off is a reported collateral change: " + evidence);
            need("minecraft:air".equals(evidence.get("after_block").getAsString()), "at the observation tick the cell is air (the gravel falls later): " + evidence);
            need(f.level.getBlockState(target).is(Blocks.GRAVEL) || !f.level.getBlockState(target).isAir() || f.level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new AABB(target).inflate(2)).size() > 0,
                    "the gravel above fell into the cell afterwards - a later change the receipt cannot attribute");
            f.finish();
        });
    }

    @GameTest(batch = "nat_b04", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void cancelledBreakEventIsNoEffectNotSuccess(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        java.util.function.Consumer<BlockEvent.BreakEvent> deny = e -> { if (e.getPos().equals(target)) e.setCanceled(true); };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, deny);
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.BREAK));
        helper.succeedWhen(() -> {
            need("FAILED_NO_EFFECT".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            MinecraftForge.EVENT_BUS.unregister(deny);
            JsonObject receipt = f.lastSent("finalize");
            need(receipt.get("result").getAsString().equals("NO_EFFECT") && receipt.get("reason").getAsString().equals("BREAK_REFUSED") && receipt.get("executed").getAsBoolean(),
                    "a protection mod cancelling the event is BREAK_REFUSED with executed=true: " + receipt);
            need(f.level.getBlockState(target).is(Blocks.OAK_LOG), "the block is untouched");
            need(f.avatars.get(0).getMainHandItem().getDamageValue() == 0, "the tool is untouched");
            need(f.drops(target, 3.0).isEmpty(), "no drops");
            f.finish();
        });
    }

    @GameTest(batch = "nat_b05", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void throwingBreakListenerIsNeverFailedNoEffectWhenTheWorldChanged(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        java.util.function.Consumer<BlockEvent.BreakEvent> boom = e -> { if (e.getPos().equals(target)) throw new IllegalStateException("simulated mod fault"); };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, boom);
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.BREAK));
        helper.succeedWhen(() -> {
            String state = f.stateOfRun(0);
            need(state.equals("HOLD_IN_DOUBT") || state.equals("EFFECT_VERIFIED") || state.equals("FAILED_NO_EFFECT"), "settled: " + state);
            MinecraftForge.EVENT_BUS.unregister(boom);
            boolean gone = f.level.getBlockState(target).isAir();
            LOG.info("G3 throwing listener: state {} blockGone {}", state, gone);
            need(!(gone && state.equals("FAILED_NO_EFFECT")), "the world changed but the ledger says no effect");
            need(!(!gone && state.equals("EFFECT_VERIFIED")), "the block is still there but the ledger says it was broken");
            if (state.equals("HOLD_IN_DOUBT")) need(f.lastSent("hold").get("reason").getAsString().equals("EXECUTOR_EXCEPTION"), "hold reason");
            f.finish();
        });
    }

    @GameTest(batch = "nat_b06", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void ownershipGuardsRefuseOtherSystemsWhileAnAttemptIsActive(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        standingLog(f, "a", 6, 6, new ItemStack(Items.IRON_PICKAXE));      // a pickaxe is slow on logs: the attempt stays active for a while
        String name = f.prefix + "a";
        boolean[] checked = new boolean[1];
        f.bind("a", () -> {
            f.go("a", NativeEffectFlow.Action.BREAK);
            helper.runAfterDelay(3, () -> {
                MinecraftServer s = f.server;
                String give = C1P1InventoryRuntime.give(s, name, new ResourceLocation("minecraft:stick"), 1, UUID.randomUUID());
                String follow = C2FollowRuntime.follow(s, name, "nobody");
                String move = C1P1MotionRuntime.move(s, name, "forward", 2.0);
                String goal = C2AgentRuntime.start(s, name, 1, 1, 1);
                need(give.startsWith("BLOCKED"), "inventory commands are refused while the attempt is active: " + give);
                need(follow.contains("native world action"), "Follow is refused: " + follow);
                need(move.contains("Native world action"), "operator motion is refused: " + move);
                need(goal.contains("native world action"), "a C2 goal is refused: " + goal);
                need(NativeEffectRuntime.pending(s, C1IdentityData.get(s).find(name).actorId()), "pending is visible to the other systems");
                checked[0] = true;
            });
        });
        helper.succeedWhen(() -> {
            need(checked[0] && "EFFECT_VERIFIED".equals(f.stateOfRun(0)), "the attempt finishes: " + f.stateOfRun(0));
            String give = C1P1InventoryRuntime.give(f.server, name, new ResourceLocation("minecraft:stick"), 1, UUID.randomUUID());
            need(!give.startsWith("BLOCKED"), "the inventory is free again after the confirmed terminal: " + give);
            f.finish();
        });
    }

    @GameTest(batch = "nat_b07", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void operatorCancelWhileMiningLeavesTheBlockAndReleasesTheClaim(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.IRON_PICKAXE));
        f.bind("a", () -> {
            f.go("a", NativeEffectFlow.Action.BREAK);
            helper.runAfterDelay(5, () -> NativeEffectRuntime.cancel(f.server, f.owner, f.prefix + "a").join());
        });
        helper.succeedWhen(() -> {
            need("CANCELLED_NO_EFFECT".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            need(f.level.getBlockState(target).is(Blocks.OAK_LOG), "the log is untouched");
            synchronized (f.sim) { need(f.sim.blockClaims.isEmpty(), "claim released"); }
            need(f.flow().slotsUsed() == 0, "slot released");
            f.finish();
        });
    }

    @GameTest(batch = "nat_b08", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void toolSwapRestartsTheProgressAndTheNewToolIsUsed(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.WOODEN_AXE));
        C1PlayerAvatar av = f.avatars.get(0);
        f.bind("a", () -> {
            f.go("a", NativeEffectFlow.Action.BREAK);
            helper.runAfterDelay(8, () -> av.getInventory().setItem(av.getInventory().selected, new ItemStack(Items.DIAMOND_AXE)));
        });
        helper.succeedWhen(() -> {
            need("EFFECT_VERIFIED".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            JsonObject evidence = f.lastSent("finalize").getAsJsonObject("evidence");
            need(evidence.get("tool_before").getAsString().startsWith("minecraft:diamond_axe"), "the tool that actually broke the block is the one recorded: " + evidence);
            f.finish();
        });
    }

    @GameTest(batch = "nat_b09", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void unreachableOrProtectedOrUnlistedTargetsNeverReachTheBackend(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        f.npc("a", 6, 1, 1, new ItemStack(Items.DIAMOND_AXE));
        f.set(6, 1, 12, Blocks.OAK_LOG);          // 11 blocks away: out of reach
        f.set(5, 1, 3, Blocks.DIAMOND_BLOCK);     // in reach but not on the allow-list
        f.set(7, 1, 3, Blocks.CHEST);              // a block entity
        f.bind("a", () -> {
            f.look.put(f.prefix + "a", f.abs(6, 1, 12));
            need(f.preview("a", NativeEffectFlow.Action.BREAK).message().contains("out of reach"), "far block refused");
            f.look.put(f.prefix + "a", f.abs(5, 1, 3));
            need(f.preview("a", NativeEffectFlow.Action.BREAK).kind() == NativeEffectFlow.Kind.DENIED, "an unlisted block is denied");
            f.look.put(f.prefix + "a", f.abs(7, 1, 3));
            need(f.preview("a", NativeEffectFlow.Action.BREAK).kind() == NativeEffectFlow.Kind.DENIED, "a chest is denied by the allow-list");
            Fx g = f;
            NativeEffectRuntime.policyOverride = NativeActionPolicy.parse(("{\"schema_version\":1,\"entries\":[{\"id\":\"gt\",\"requester\":\"" + f.owner
                    + "\",\"actions\":[\"break_block\"],\"dimensions\":[\"minecraft:overworld\"],\"actors\":[\"*\"],\"blocks\":[\"minecraft:chest\"],\"regions\":[{\"min\":["
                    + (f.abs(0, 0, 0).getX() - 60) + "," + (f.abs(0, 0, 0).getY() - 20) + "," + (f.abs(0, 0, 0).getZ() - 60) + "],\"max\":["
                    + (f.abs(0, 0, 0).getX() + 60) + "," + (f.abs(0, 0, 0).getY() + 40) + "," + (f.abs(0, 0, 0).getZ() + 60) + "]}]}]}").getBytes(StandardCharsets.UTF_8));
            String chest = g.preview("a", NativeEffectFlow.Action.BREAK).message();
            need(chest.contains("block entity"), "even when listed, a block entity is refused by the native check: " + chest);
            need(f.runs() == 0, "nothing reached the ledger");
            f.finish();
        });
    }

    @GameTest(batch = "nat_k01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 300)
    public static void bindingStoresTheEpochSendsSaveConfirmationAndRefusalFailsClosed(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        f.npc("a", 6, 1, 4, null);
        long before = WorldBindingData.get(f.server).epoch();
        boolean[] saved = new boolean[1];
        f.bind("a", () -> {
            need(f.sim.epoch >= 1 && WorldBindingData.get(f.server).epoch() == f.sim.epoch,
                    "the epoch the backend returned is stored in the world: backend " + f.sim.epoch + ", world " + WorldBindingData.get(f.server).epoch());
            JsonObject request = f.lastSent("bind");
            need(request.get("epoch").getAsLong() == before && request.get("folder_key").getAsString().length() > 0
                    && request.get("contract").getAsString().equals(NativeEffectFlow.CONTRACT), "the handshake carries the world epoch, the folder key and the contract: " + request);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.level.LevelEvent.Save(f.level));
            saved[0] = true;
        });
        helper.succeedWhen(() -> {
            need(saved[0] && f.routes.contains("saved"), "LevelEvent.Save is reported to the backend (best effort)");
            f.finish();
        });
    }

    @GameTest(batch = "nat_k02", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 300)
    public static void aRefusedBindingBlocksEveryNativeRequest(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        f.sim.bindOk = false;
        long epochBefore = WorldBindingData.get(f.server).epoch();
        f.bind("a", () -> {
            NativeEffectFlow.Result r = f.preview("a", NativeEffectFlow.Action.BREAK);
            need(r.kind() == NativeEffectFlow.Kind.REJECTED && r.message().contains("Native effects refused"), "a refused binding fails closed: " + r);
            need(f.runs() == 0 && !f.routes.contains("reserve"), "nothing is reserved");
            need(f.flow().boundSessionOrNull() == null, "the session is not bound");
            need(WorldBindingData.get(f.server).epoch() == epochBefore, "the world epoch did not move");
            f.finish();
        });
    }

    @GameTest(batch = "nat_k03", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 300)
    public static void lineOfSightIsRequiredToBreak(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        f.set(6, 1, 5, Blocks.STONE);                              // a wall between the NPC and the log
        f.set(6, 2, 5, Blocks.STONE);
        f.set(5, 1, 5, Blocks.STONE);
        f.set(7, 1, 5, Blocks.STONE);
        f.set(5, 2, 5, Blocks.STONE);
        f.set(7, 2, 5, Blocks.STONE);
        f.bind("a", () -> {
            NativeEffectFlow.Result r = f.preview("a", NativeEffectFlow.Action.BREAK);
            need(r.kind() == NativeEffectFlow.Kind.REJECTED && r.message().contains("no line of sight"), "a wall in between refuses the break: " + r);
            need(f.runs() == 0, "nothing reached the ledger");
            f.set(6, 1, 5, Blocks.AIR);
            f.set(6, 2, 5, Blocks.AIR);
            f.set(5, 1, 5, Blocks.AIR);
            f.set(7, 1, 5, Blocks.AIR);
            f.set(5, 2, 5, Blocks.AIR);
            f.set(7, 2, 5, Blocks.AIR);
            NativeEffectFlow.Result clear = f.preview("a", NativeEffectFlow.Action.BREAK);
            need(clear.kind() == NativeEffectFlow.Kind.OK, "with the wall gone the same target is fine: " + clear);
            f.finish();
        });
    }

    // ----------------------------------------------------------------------------------------------------- the data pack
    private static java.nio.file.Path datapackFunctions() {
        java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
        for (java.nio.file.Path p = here; p != null; p = p.getParent()) {
            java.nio.file.Path candidate = p.resolve("integrations/datapacks/NPCortex_Native_Acceptance/data/npcortex_native/functions");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
        }
        throw new GameTestAssertException("the acceptance data pack was not found above " + here);
    }

    /** Expands `function npcortex_native:x` lines inline. {@code tellraw @a} becomes a scoreboard counter by colour so the conditions run for real. */
    private static void runFunction(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
                                    net.minecraft.commands.CommandSourceStack source, java.nio.file.Path root, String name, int depth) throws Exception {
        if (depth > 6) throw new GameTestAssertException("function nesting too deep");
        for (String raw : java.nio.file.Files.readAllLines(root.resolve(name + ".mcfunction"))) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("function npcortex_native:")) { runFunction(dispatcher, source, root, line.substring("function npcortex_native:".length()), depth + 1); continue; }
            if (line.startsWith("tellraw ") || line.startsWith("npcortex c1 spawn") || line.contains("run npcortex ")) continue;
            line = line.replaceAll("run tellraw @a \\{[^\\n]*\"color\":\"green\"\\}$", "run scoreboard players add #pass npx_tmp 1")
                    .replaceAll("run tellraw @a \\{[^\\n]*\"color\":\"red\"\\}$", "run scoreboard players add #fail npx_tmp 1")
                    .replaceAll("run tellraw @a [\\[{][^\\n]*$", "run scoreboard players add #info npx_tmp 1");
            try {
                dispatcher.execute(line, source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                // A vanilla function CONTINUES after a failing command; only the expected "nothing to kill / objective exists" are allowed.
                String m = e.getMessage();
                if (!(m.contains("No entity was found") || m.contains("already exists"))) throw new GameTestAssertException("function " + name + ": '" + line + "' failed: " + m);
            }
        }
    }

    @GameTest(batch = "nat_d01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void acceptanceDataPackIsValidBuildsTheArenaAndItsChecksJudgeTheWorld(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        java.nio.file.Path functions = datapackFunctions();
        var dispatcher = f.server.getCommands().getDispatcher();
        BlockPos anchor = f.abs(2, 1, 2);
        var source = f.server.createCommandSourceStack().withLevel(f.level).withPosition(Vec3.atBottomCenterOf(anchor)).withPermission(4).withSuppressedOutput();
        int[] parsed = new int[1];
        try (var walk = java.nio.file.Files.walk(functions)) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) walk.filter(p -> p.toString().endsWith(".mcfunction"))::iterator) {
                for (String raw : java.nio.file.Files.readAllLines(file)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("function ")) continue;
                    var result = dispatcher.parse(line, source);
                    need(result.getExceptions().isEmpty() && !result.getReader().canRead(),
                            file.getFileName() + ": does not parse: " + line + " " + result.getExceptions().values());
                    parsed[0]++;
                }
            }
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("cannot read the data pack: " + e);
        }
        LOG.info("DATAPACK {} command lines parse against the real 1.20.1 command tree", parsed[0]);
        need(parsed[0] > 60, "the data pack has content: " + parsed[0]);
        try {
            dispatcher.execute("forceload add " + (anchor.getX() - 3) + " " + (anchor.getZ() - 3) + " " + (anchor.getX() + 45) + " " + (anchor.getZ() + 18), source);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            LOG.info("DATAPACK forceload: {} (the chunks were already forced by the test framework)", e.getMessage());
        }
        // Everything runs from ONE succeedWhen registered here: scheduling more framework callbacks from inside a tick callback
        // can corrupt the framework's delayed-task map (seen as a server crash / a test that never ends).
        int[] ticks = new int[1], waited = new int[1];
        boolean[] setupDone = new boolean[1], ran = new boolean[1];
        RuntimeException[] failure = new RuntimeException[1];
        Runnable setupStage = () -> {
            try {
                dispatcher.execute("scoreboard objectives add npx_tmp dummy", source);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) { /* already there */ }
            try {
                runFunction(dispatcher, source, functions, "setup", 0);
            } catch (Exception e) {
                throw new GameTestAssertException("setup: " + e);
            }
            net.minecraft.world.level.block.Block[][] expect = {{Blocks.OAK_LOG}};
            need(f.level.getBlockState(anchor.offset(4, 0, 6)).is(Blocks.OAK_LOG), "S1 log built");
            need(f.level.getBlockState(anchor.offset(8, 0, 6)).is(Blocks.OAK_LOG) && f.level.getBlockState(anchor.offset(9, 0, 6)).is(Blocks.OAK_LOG), "S2 logs built");
            need(f.level.getBlockState(anchor.offset(18, 1, 6)).is(Blocks.GRAVEL) && f.level.getBlockState(anchor.offset(19, 0, 6)).is(Blocks.WALL_TORCH), "S4 gravel and torch built");
            need(f.level.getBlockState(anchor.offset(34, 0, 6)).is(Blocks.DIAMOND_BLOCK) && f.level.getBlockState(anchor.offset(36, 0, 6)).is(Blocks.CHEST), "denied targets built");
            for (int i = 0; i < 10; i++) need(f.level.getBlockState(anchor.offset(2 + 2 * i, 0, 12)).is(Blocks.OAK_LOG), "row log " + i);
            need(f.level.getBlockState(anchor.offset(4, -1, 4)).is(Blocks.STONE), "the floor is stone");
        };
        {
            // The three summoned stacks are polled for up to 40 ticks: on a fresh world a summoned entity can be queued (not yet
            // queryable) while its chunk finishes loading. The diagnostics below list where the item entities really are.
            java.util.function.IntFunction<String> missing = tries -> {
                for (int x : new int[]{22, 26, 30}) {
                    List<ItemEntity> here = f.level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor.offset(x, 0, 6)).inflate(0.3, 1.0, 0.3));
                    if (!(here.size() == 1 && here.get(0).getItem().getCount() == 4 && here.get(0).getItem().is(Items.OAK_LOG))) {
                        StringBuilder all = new StringBuilder();
                        for (ItemEntity ie : f.level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor.offset(x, 0, 6)).inflate(8)))
                            all.append(ie.getItem().getCount()).append('x').append(ie.getItem().getItem()).append('@').append(ie.position()).append(' ');
                        return "a stack of four logs at S" + x + ", found " + here.size() + "; item entities within 8: " + all;
                    }
                }
                return null;
            };
            Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (ran[0]) return;
                if (++waited[0] < 2) throw new GameTestAssertException("settling");
                String problem = missing.apply(0);
                if (problem != null && waited[0] < 42) throw new GameTestAssertException(problem);   // succeedWhen retries next tick
                ran[0] = true;
                need(problem == null, problem + " (after " + waited[0] + " ticks)");
                LOG.info("datapack stacks queryable after {} extra ticks", waited[0]);
                try {
                    var score = f.server.getScoreboard();
                    var objective = score.getObjective("npx_tmp");
                    need(objective != null, "the scoreboard objective exists");
                    java.util.function.Function<String, Integer> read = name -> score.getOrCreatePlayerScore(name, objective).getScore();
                    for (String n : new String[]{"#pass", "#fail", "#info"}) score.getOrCreatePlayerScore(n, objective).setScore(0);
                    runFunction(dispatcher, source, functions, "check/s1_break", 0);
                    need(read.apply("#fail") == 1 && read.apply("#pass") == 0, "S1 before the break: FAIL (the log is there): pass " + read.apply("#pass") + " fail " + read.apply("#fail"));
                    f.level.setBlock(anchor.offset(4, 0, 6), Blocks.AIR.defaultBlockState(), 3);
                    for (String n : new String[]{"#pass", "#fail"}) score.getOrCreatePlayerScore(n, objective).setScore(0);
                    runFunction(dispatcher, source, functions, "check/s1_break", 0);
                    need(read.apply("#pass") == 1 && read.apply("#fail") == 0, "S1 after the break: PASS");
                    for (String n : new String[]{"#pass", "#fail"}) score.getOrCreatePlayerScore(n, objective).setScore(0);
                    runFunction(dispatcher, source, functions, "check/denied", 0);
                    need(read.apply("#pass") == 2 && read.apply("#fail") == 0, "the denied targets are untouched: pass " + read.apply("#pass"));
                    runFunction(dispatcher, source, functions, "check/row", 0);
                    runFunction(dispatcher, source, functions, "check/items_left", 0);
                    need(read.apply("#items") >= 3, "check/items_left counts at least the three stacks: " + read.apply("#items"));
                    runFunction(dispatcher, source, functions, "check/all", 0);       // the one-click check and the guide must execute too
                    runFunction(dispatcher, source, functions, "guide", 0);
                    runFunction(dispatcher, source, functions, "teardown", 0);
                    for (int x : new int[]{22, 26, 30})
                        need(f.level.getEntitiesOfClass(ItemEntity.class, new AABB(anchor.offset(x, 0, 6)).inflate(0.3, 1.0, 0.3)).isEmpty(), "teardown removes the loose stacks");
                    dispatcher.execute("forceload remove all", source);
                } catch (GameTestAssertException e) {
                    throw e;
                } catch (Exception e) {
                    throw new GameTestAssertException("data pack run: " + e);
                }
                f.finish();
            };
            // The one-shot stages must never run twice: a failure after they started is remembered and rethrown every tick.
            helper.succeedWhen(() -> {
                if (failure[0] != null) throw failure[0];
                if (++ticks[0] < 40) throw new GameTestAssertException("waiting for the chunks");
                try {
                    if (!setupDone[0]) { setupDone[0] = true; setupStage.run(); }
                    poll[0].run();
                } catch (GameTestAssertException e) {
                    if (ran[0] || setupDone[0] && waited[0] == 0) failure[0] = e;
                    throw e;
                }
            });
        }
    }

    // ----------------------------------------------------------------------------------------------------- multi-NPC
    @GameTest(batch = "nat_m01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 600)
    public static void twoNpcsOneBlockExactlyOneWins(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        f.npc("b", 8, 1, 6, new ItemStack(Items.DIAMOND_AXE));
        f.look.put(f.prefix + "b", target);
        CompletableFuture<NativeEffectFlow.Result>[] c = new CompletableFuture[2];
        f.bind("a", () -> {
            f.bind("b", () -> { });
            NativeEffectFlow.Result pa = f.preview("a", NativeEffectFlow.Action.BREAK), pb = f.preview("b", NativeEffectFlow.Action.BREAK);
            need(pa.kind() == NativeEffectFlow.Kind.OK && pb.kind() == NativeEffectFlow.Kind.OK, "both previews are fine (a preview takes no claim)");
            c[0] = f.confirm("a", pa.nonce());
            c[1] = f.confirm("b", pb.nonce());
        });
        helper.succeedWhen(() -> {
            need(c[0] != null && c[0].isDone() && c[1] != null && c[1].isDone(), "both confirms answered");
            int ok = 0, refused = 0;
            for (var r : List.of(c[0].join(), c[1].join())) {
                if (r.kind() == NativeEffectFlow.Kind.OK) ok++;
                if (r.kind() == NativeEffectFlow.Kind.REJECTED) refused++;
            }
            need(ok == 1 && refused == 1, "exactly one of two NPCs wins the same block: ok=" + ok + " refused=" + refused);
            need(f.runs() == 1 && "EFFECT_VERIFIED".equals(f.stateOfRun(0)), "one attempt, one terminal: " + f.stateOfRun(0));
            need(f.level.getBlockState(target).isAir() && f.drops(target, 3.0).size() >= 1, "one break");
            f.finish();
        });
    }

    @GameTest(batch = "nat_m02", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 800)
    public static void tenNpcsBreakTenBlocksInParallel(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        List<BlockPos> targets = new ArrayList<>();
        for (int i = 0; i < 10; i++) targets.add(standingLog(f, "n" + i, 3 + 2 * i, 8, new ItemStack(Items.DIAMOND_AXE)));
        double[] worst = new double[1];
        f.bind("n0", () -> {
            for (int i = 0; i < 10; i++) f.go("n" + i, NativeEffectFlow.Action.BREAK);
        });
        helper.onEachTick(() -> worst[0] = Math.max(worst[0], f.server.getAverageTickTime()));
        helper.succeedWhen(() -> {
            need(f.runs() == 10, "ten reservations: " + f.runs());
            for (int i = 0; i < 10; i++) need("EFFECT_VERIFIED".equals(f.stateOfRun(i)), "run " + i + " " + f.stateOfRun(i));
            for (BlockPos t : targets) need(f.level.getBlockState(t).isAir(), "every log is gone");
            need(f.flow().slotsUsed() == 0, "all slots released");
            LOG.info("G5 10 NPCs: all ten broke their own block; worst averageTickTime observed {} ms (OBSERVATION, not an acceptance)", worst[0]);
            f.finish();
        });
    }

    // ----------------------------------------------------------------------------------------------------- G4: pickup
    static ItemEntity drop(Fx f, int x, int z, int count, boolean noDelay) {
        Vec3 at = Vec3.atCenterOf(f.abs(x, 1, z));
        ItemEntity ie = new ItemEntity(f.level, at.x, at.y, at.z, new ItemStack(Items.OAK_LOG, count));
        ie.setDeltaMovement(Vec3.ZERO);
        f.level.addFreshEntity(ie);
        if (noDelay) ie.setNoPickUpDelay();
        return ie;
    }

    @GameTest(batch = "nat_p01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void pickupVerifiedTransferWithJournal(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        ItemEntity drop = drop(f, 6, 6, 4, true);
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.PICKUP));
        helper.succeedWhen(() -> {
            need("EFFECT_VERIFIED".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            JsonObject evidence = f.lastSent("finalize").getAsJsonObject("evidence");
            need(evidence.get("delta").getAsInt() == 4 && evidence.get("event_count").getAsInt() == 4 && evidence.get("entity_count_after").getAsInt() == 0,
                    "inventory, the pickup event and the entity agree: " + evidence);
            need(f.itemCount(av, Items.OAK_LOG) == 4 && drop.isRemoved(), "the logs are in the inventory and the entity is gone");
            UUID attempt = UUID.fromString(f.lastSent("reserve").get("attempt_id").getAsString());
            C1InventoryData.Record record = C1InventoryData.get(f.server).find(av.getUUID());
            need(record != null && record.journal.find(attempt) != null && "COMMITTED".equals(evidence.get("journal").getAsString()), "the C1 journal holds the attempt id");
            synchronized (f.sim) { need(f.sim.itemClaims.isEmpty(), "item claim released"); }
            f.finish();
        });
    }

    @GameTest(batch = "nat_p02", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void pickupPartialWhenTheInventoryIsAlmostFull(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        for (int i = 0; i < av.getInventory().items.size(); i++) av.getInventory().items.set(i, new ItemStack(Items.DIAMOND_PICKAXE));
        av.getInventory().offhand.set(0, new ItemStack(Items.DIAMOND_PICKAXE));
        av.getInventory().items.set(5, new ItemStack(Items.OAK_LOG, 62));                  // room for exactly two more
        ItemEntity drop = drop(f, 6, 6, 4, true);
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.PICKUP));
        helper.succeedWhen(() -> {
            need("EFFECT_PARTIAL".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            JsonObject evidence = f.lastSent("finalize").getAsJsonObject("evidence");
            need(evidence.get("delta").getAsInt() == 2 && evidence.get("entity_count_before").getAsInt() == 4 && evidence.get("entity_count_after").getAsInt() == 2, "two of four fit: " + evidence);
            need(evidence.get("event_count").getAsInt() == 0, "vanilla fires no pickup event for a partial add (observed): " + evidence);
            need(!drop.isRemoved() && drop.getItem().getCount() == 2 && f.itemCount(av, Items.OAK_LOG) == 64, "the rest stays on the ground; nothing is invented");
            f.finish();
        });
    }

    @GameTest(batch = "nat_p03", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void pickupWithAFullInventoryIsATypedNoTransfer(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        for (int i = 0; i < av.getInventory().items.size(); i++) av.getInventory().items.set(i, new ItemStack(Items.DIAMOND_PICKAXE));
        av.getInventory().offhand.set(0, new ItemStack(Items.DIAMOND_PICKAXE));
        ItemEntity drop = drop(f, 6, 6, 4, true);
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.PICKUP));
        helper.succeedWhen(() -> {
            need("FAILED_NO_EFFECT".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            JsonObject receipt = f.lastSent("finalize");
            need(receipt.get("result").getAsString().equals("NO_EFFECT") && receipt.get("reason").getAsString().equals("NO_TRANSFER") && receipt.get("executed").getAsBoolean(), "NO_TRANSFER with every witness quiet: " + receipt);
            need(!drop.isRemoved() && drop.getItem().getCount() == 4, "the item is untouched");
            f.finish();
        });
    }

    @GameTest(batch = "nat_p04", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void pickupNeedsGeometryDelayAndOwnership(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        f.npc("a", 6, 1, 6, null);
        drop(f, 12, 12, 4, true);                                  // 6+ blocks away
        f.bind("a", () -> {
            need(f.preview("a", NativeEffectFlow.Action.PICKUP).message().contains("pickup reach"), "an item out of reach is not offered");
            ItemEntity fresh = drop(f, 6, 6, 4, false);
            fresh.setDefaultPickUpDelay();
            need(f.preview("a", NativeEffectFlow.Action.PICKUP).kind() == NativeEffectFlow.Kind.REJECTED, "an item with a pickup delay is not offered");
            fresh.setNoPickUpDelay();
            fresh.setThrower(UUID.randomUUID());
            need(f.preview("a", NativeEffectFlow.Action.PICKUP).kind() == NativeEffectFlow.Kind.REJECTED, "an item thrown by a player is not offered");
            need(f.runs() == 0, "nothing reached the ledger");
            f.finish();
        });
    }

    @GameTest(batch = "nat_p05", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void pickupOfAnItemThatVanishesAfterTheMarkerDoesNotDispatch(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        ItemEntity drop = drop(f, 6, 6, 4, true);
        f.bind("a", () -> {
            f.onStart = () -> f.server.execute(drop::discard);       // removed by "someone else" while the marker is being written
            f.go("a", NativeEffectFlow.Action.PICKUP);
        });
        helper.succeedWhen(() -> {
            need("FAILED_NO_EFFECT".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            JsonObject receipt = f.lastSent("finalize");
            need(!receipt.get("executed").getAsBoolean() && receipt.get("reason").getAsString().equals("ENTITY_GONE"), "ENTITY_GONE with executed=false: " + receipt);
            need(f.itemCount(av, Items.OAK_LOG) == 0, "nothing was invented");
            f.finish();
        });
    }

    @GameTest(batch = "nat_p06", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void twoNpcsOneItemExactlyOneWins(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar a = f.npc("a", 6, 1, 6, null);
        C1PlayerAvatar b = f.npc("b", 6, 1, 8, null);
        drop(f, 6, 7, 4, true);                                    // one block from each of them: both can reach it
        CompletableFuture<NativeEffectFlow.Result>[] c = new CompletableFuture[2];
        f.bind("a", () -> {
            f.bind("b", () -> { });
            NativeEffectFlow.Result pa = f.preview("a", NativeEffectFlow.Action.PICKUP), pb = f.preview("b", NativeEffectFlow.Action.PICKUP);
            need(pa.kind() == NativeEffectFlow.Kind.OK && pb.kind() == NativeEffectFlow.Kind.OK, "both previews: " + pa + " / " + pb);
            c[0] = f.confirm("a", pa.nonce());
            c[1] = f.confirm("b", pb.nonce());
        });
        helper.succeedWhen(() -> {
            need(c[0] != null && c[0].isDone() && c[1] != null && c[1].isDone(), "both confirms answered");
            int ok = 0;
            for (var r : List.of(c[0].join(), c[1].join())) if (r.kind() == NativeEffectFlow.Kind.OK) ok++;
            need(ok == 1, "one owner for one item entity: " + ok);
            need("EFFECT_VERIFIED".equals(f.stateOfRun(0)) || "EFFECT_VERIFIED".equals(f.stateOfRun(1)), "the winner's transfer is verified");
            need(f.itemCount(a, Items.OAK_LOG) + f.itemCount(b, Items.OAK_LOG) == 4, "the four logs exist exactly once");
            f.finish();
        });
    }

    @GameTest(batch = "nat_p07", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 200)
    public static void vanillaMergesStacksSoAnEntityIdentityCanDisappear(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        ItemEntity one = drop(f, 6, 6, 2, true);
        ItemEntity two = drop(f, 6, 6, 2, true);
        two.setPos(one.getX() + 0.2, one.getY(), one.getZ());
        UUID a = one.getUUID(), b = two.getUUID();
        helper.succeedWhen(() -> {
            List<ItemEntity> alive = new ArrayList<>();
            for (UUID id : List.of(a, b)) if (f.level.getEntity(id) instanceof ItemEntity ie && !ie.isRemoved()) alive.add(ie);
            need(alive.size() == 1, "two neighbouring stacks merge into one entity (identity is not stable): " + alive.size());
            ItemEntity survivor = alive.get(0);
            need(survivor.getItem().getCount() == 4, "the survivor carries all four, not " + survivor.getItem().getCount());
            need(survivor.getUUID().equals(a) || survivor.getUUID().equals(b), "the survivor keeps one of the two identities");
            LOG.info("G4 merge: survivor {} out of {} and {}", survivor.getUUID(), a, b);
            f.finish();
        });
    }

    @GameTest(batch = "nat_p08", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void journalFailureAfterPickupHoldsAndPersistOnlyRepairRepeatsNothing(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        ItemEntity drop = drop(f, 6, 6, 4, true);
        NativeEffectRuntime.journalFault = new IllegalStateException("injected journal fault");
        boolean[] repaired = new boolean[1];
        CompletableFuture<NativeEffectFlow.Result>[] rec = new CompletableFuture[1];
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.PICKUP));
        helper.succeedWhen(() -> {
            need("HOLD_IN_DOUBT".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            need(f.flow().slotsUsed() == 0, "the confirmed HOLD released the completion slot (local evidence is in place)");
            if (!repaired[0]) {
                repaired[0] = true;
                need(f.lastSent("hold").get("reason").getAsString().equals("INVENTORY_COMMIT_FAILED"), "HOLD INVENTORY_COMMIT_FAILED");
                need(f.itemCount(av, Items.OAK_LOG) == 4 && drop.isRemoved(), "the transfer happened and was NOT rolled back");
                NativeEffectRuntime.journalFault = null;
                UUID attempt = UUID.fromString(f.lastSent("reserve").get("attempt_id").getAsString());
                C1InventoryData.Record record = C1InventoryData.get(f.server).find(av.getUUID());
                need(record == null || record.journal.find(attempt) == null, "no journal receipt exists yet");
                rec[0] = NativeEffectRuntime.reconcile(f.server, f.owner, f.prefix + "a");
            }
            need(rec[0] != null && rec[0].isDone(), "repair answered");
            need(rec[0].join().kind() == NativeEffectFlow.Kind.OK, "persist-only repair: " + rec[0].join());
            need("EFFECT_VERIFIED".equals(f.stateOfRun(0)), "terminal from the stored intended receipt: " + f.stateOfRun(0));
            UUID attempt = UUID.fromString(f.lastSent("reserve").get("attempt_id").getAsString());
            need(C1InventoryData.get(f.server).find(av.getUUID()).journal.find(attempt) != null, "the journal now holds the attempt");
            need(f.itemCount(av, Items.OAK_LOG) == 4, "no second pickup: still exactly four");
            f.finish();
        });
    }

    @GameTest(batch = "nat_p09", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void repairIsRefusedWhenTheInventoryChangedAndTheAttemptStaysHeld(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        C1PlayerAvatar av = f.npc("a", 6, 1, 6, null);
        drop(f, 6, 6, 4, true);
        NativeEffectRuntime.journalFault = new IllegalStateException("injected journal fault");
        boolean[] asked = new boolean[1];
        CompletableFuture<NativeEffectFlow.Result>[] rec = new CompletableFuture[1];
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.PICKUP));
        helper.succeedWhen(() -> {
            need("HOLD_IN_DOUBT".equals(f.stateOfRun(0)), "terminal " + f.stateOfRun(0));
            need(f.flow().slotsUsed() == 0, "the confirmed HOLD released the completion slot (local evidence is in place)");
            if (!asked[0]) {
                asked[0] = true;
                NativeEffectRuntime.journalFault = null;
                av.getInventory().add(new ItemStack(Items.STICK, 3));                  // an unrelated change after the effect
                rec[0] = NativeEffectRuntime.reconcile(f.server, f.owner, f.prefix + "a");
            }
            need(rec[0] != null && rec[0].isDone(), "answered");
            need(rec[0].join().kind() == NativeEffectFlow.Kind.REJECTED && "HOLD_IN_DOUBT".equals(f.stateOfRun(0)), "a changed inventory refuses the repair: " + rec[0].join());
            UUID attempt = UUID.fromString(f.lastSent("reserve").get("attempt_id").getAsString());
            C1InventoryData.Record record = C1InventoryData.get(f.server).find(av.getUUID());
            need(record == null || record.journal.find(attempt) == null, "no receipt was written");
            f.finish();
        });
    }

    @GameTest(batch = "nat_r01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 400)
    public static void holdSurvivesARestartAndIsReconciledFromObservedState(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        java.util.function.Consumer<BlockEvent.BreakEvent> boom = e -> { if (e.getPos().equals(target)) throw new IllegalStateException("simulated mod fault"); };
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, boom);
        boolean[] phase = new boolean[2];
        CompletableFuture<NativeEffectFlow.Result>[] rec = new CompletableFuture[1];
        f.bind("a", () -> f.go("a", NativeEffectFlow.Action.BREAK));
        helper.succeedWhen(() -> {
            if (!phase[0]) need("HOLD_IN_DOUBT".equals(f.stateOfRun(0)), "waiting for the HOLD: " + f.stateOfRun(0));
            if (!phase[0]) {
                phase[0] = true;
                MinecraftForge.EVENT_BUS.unregister(boom);
                NativeEffectRuntime.forgetFlow(f.server);                          // the game restarted: only the ledger remembers
            }
            if (!phase[1]) {
                phase[1] = true;
                f.bind("a", () -> { });
                return_wait(f);
            }
            need(f.flow().boundSessionOrNull() != null, "the new run is bound again");
            if (rec[0] == null) rec[0] = NativeEffectRuntime.reconcile(f.server, f.owner, f.prefix + "a");
            need(rec[0].isDone(), "reconcile answered");
            need(rec[0].join().kind() == NativeEffectFlow.Kind.OK, "reconcile: " + rec[0].join());
            String state = f.stateOfRun(0);
            need(state.equals("RECONCILED_PERSISTED_STATE_UNCHANGED") || state.equals("RECONCILED_EFFECT_OBSERVED"), "judged from the observed block, never NO_EFFECT: " + state);
            need(f.level.getBlockState(target).isAir() == state.equals("RECONCILED_EFFECT_OBSERVED"), "the class matches what the world shows");
            f.finish();
        });
    }

    /** Regression: a full NON-critical lane means "nothing was sent": REJECTED, no ambiguous reserve, no compensating cancel, no run. */
    @GameTest(batch = "nat_l01", template = ARENA, templateNamespace = "npcortex", timeoutTicks = 200)
    public static void aFullNormalLaneRejectsTheReserveWithoutAnAmbiguousCancel(GameTestHelper helper) {
        Fx f = Fx.logs(helper);
        BlockPos target = standingLog(f, "a", 6, 6, new ItemStack(Items.DIAMOND_AXE));
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        int[] before = new int[1];
        CompletableFuture<NativeEffectFlow.Result>[] answer = new CompletableFuture[1];
        f.bind("a", () -> {
            var lane = NativeEffectRuntime.normalLaneForTest(f.server);
            Runnable blocker = () -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
            helper.runAfterDelay(150, gate::countDown);                                   // never leave the shared lane blocked
            boolean full = false;
            try { for (int i = 0; i < 100; i++) lane.execute(blocker); } catch (java.util.concurrent.RejectedExecutionException saturated) { full = true; }
            need(full, "the lane was driven to rejection");
            // The worker may still be finishing an earlier task and pull one blocker, freeing one slot: top it up to full again.
            try { for (int i = 0; i < 4; i++) lane.execute(blocker); } catch (java.util.concurrent.RejectedExecutionException saturated) { /* still full */ }
            int reserves = (int) f.routes.stream().filter("reserve"::equals).count();
            before[0] = reserves;
            answer[0] = f.go("a", NativeEffectFlow.Action.BREAK);
        });
        helper.succeedWhen(() -> {
            need(answer[0] != null && answer[0].isDone(), "the confirm was answered");
            gate.countDown();
            NativeEffectFlow.Result r = answer[0].join();
            need(r.kind() == NativeEffectFlow.Kind.REJECTED && r.message().contains("busy"), "a full lane is a clean refusal: " + r);
            need(f.routes.stream().filter("reserve"::equals).count() == before[0] && !f.routes.contains("cancel"), "nothing was sent and nothing was cancelled: " + f.routes);
            need(f.runs() == 0 && f.level.getBlockState(target).is(Blocks.OAK_LOG), "no run, the log is untouched");
            var entry = C1IdentityData.get(f.server).find(f.prefix + "a");
            need(entry != null && !f.flow().pending(entry.actorId()) && f.flow().slotsUsed() == 0, "the NPC and the slot are free again");
            f.finish();
        });
    }

    private static void return_wait(Fx f) {
        if (f.flow().boundSessionOrNull() == null) throw new GameTestAssertException("waiting for the binding");
    }
}
