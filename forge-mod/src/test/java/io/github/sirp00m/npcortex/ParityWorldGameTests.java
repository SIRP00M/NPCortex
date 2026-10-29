package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Parity P2-P4 evidence against the REAL avatar (lines "PARITY|..."). The oracle for mining speed is a second real ServerPlayer
 * (GameTestHelper.makeMockServerPlayerInLevel). PROBE tests record what vanilla does with this avatar instead of asserting a guess; their
 * result is reported in the matrix as OBSERVED, never as PASS of a human-equivalence claim. Harness numbers are not dedicated-server numbers.
 */
@GameTestHolder("npcortexparity")
@PrefixGameTestTemplate(false)
public final class ParityWorldGameTests {
    private static final String ARENA = "arena", NS = "npcortexparity";
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private ParityWorldGameTests() {}

    /** Namespace tests run in parallel; only T19 touches a global switch (chunkTracking), sequentially inside one test. */
    private static void reset() {}

    // ------------------------------------------------------------------------------------------------ Phase 4: chunk ring
    /** T19: with tracking on the ring follows a 4-chunk move (a chunk 4 ahead of the new position loads); then the control with tracking off
     *  reproduces X0's stale ring in a fresh area. */
    @GameTest(batch = "parity_19", template = ARENA, templateNamespace = NS, timeoutTicks = 700)
    public static void t19RingFollowsSectionChangeAndControl(GameTestHelper helper) {
        C1SurvivalParity.chunkTracking = true;
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("ring", 4, 1, 4, null);
        int[] probe = new int[2];
        int[] stage = new int[1];
        helper.runAfterDelay(10, () -> {
            av.setPos(av.getX() + 64.0, av.getY(), av.getZ());           // controlled precondition: 4 chunks east, as in X0
            probe[0] = (av.blockPosition().getX() >> 4) + 4;
            probe[1] = av.blockPosition().getZ() >> 4;
            stage[0] = 1;
        });
        helper.succeedWhen(() -> {
            if (stage[0] == 1) {
                need(f.level.getChunkSource().getChunkNow(probe[0], probe[1]) != null, "chunk 4 ahead of the NEW position (8 from the old centre; the harness ring is smaller than 7) is loaded: the ring followed");
                LOG.info("PARITY|T19|ring-follows|tracking=true|probe={},{}", probe[0], probe[1]);
                C1SurvivalParity.chunkTracking = false;
                av.setPos(av.getX() - 64.0, av.getY(), av.getZ() + 160.0);   // fresh area 10 chunks south (outside the old ring), tracking OFF
                probe[0] = (av.blockPosition().getX() >> 4) + 4;
                probe[1] = av.blockPosition().getZ() >> 4;
                stage[0] = 2;
                helper.runAfterDelay(120, () -> stage[0] = 3);
            }
            need(stage[0] == 3, "waiting for the control");
            boolean stale = f.level.getChunkSource().getChunkNow(probe[0], probe[1]) == null;
            C1SurvivalParity.chunkTracking = true;
            LOG.info("PARITY|T19|ring-stale-control|tracking=false|stale={}", stale);
            need(stale, "control: with tracking off the ring stays where it was (X0)");
            f.finish();
        });
    }

    /** Double-tick detection: the avatar is ticked by vanilla exactly once per server tick (LivingTick and PlayerTick each). */
    @GameTest(batch = "parity_22", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t22NoDoubleTick(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("tick", 4, 1, 4, null);
        AtomicInteger living = new AtomicInteger(), playerStart = new AtomicInteger(), playerEnd = new AtomicInteger();
        java.util.function.Consumer<LivingEvent.LivingTickEvent> l = e -> { if (e.getEntity() == av) living.incrementAndGet(); };
        java.util.function.Consumer<TickEvent.PlayerTickEvent> p = e -> {
            if (e.player == av) (e.phase == TickEvent.Phase.START ? playerStart : playerEnd).incrementAndGet();
        };
        helper.runAfterDelay(2, () -> {
            MinecraftForge.EVENT_BUS.addListener(l);
            MinecraftForge.EVENT_BUS.addListener(p);
        });
        helper.runAfterDelay(102, () -> {
            MinecraftForge.EVENT_BUS.unregister(l);
            MinecraftForge.EVENT_BUS.unregister(p);
            LOG.info("PARITY|T22|ticks over 100 server ticks|living={}|playerStart={}|playerEnd={}", living.get(), playerStart.get(), playerEnd.get());
            need(Math.abs(living.get() - 100) <= 1 && Math.abs(playerStart.get() - 100) <= 1 && Math.abs(playerEnd.get() - 100) <= 1,
                    "exactly one vanilla tick per server tick");
            f.finish();
        });
    }

    // ------------------------------------------------------------------------------------------------ Phase 2
    /** T10: vanilla item use. Starting the use is the intent; vanilla ticks it, finishes it, feeds the player and shrinks the stack. */
    @GameTest(batch = "parity_10", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t10EatBread(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("eat", 4, 1, 4, new ItemStack(Items.BREAD, 2));
        av.getFoodData().setFoodLevel(6);
        av.getFoodData().setSaturation(0.0f);
        av.startUsingItem(InteractionHand.MAIN_HAND);
        helper.succeedWhen(() -> {
            need(av.getFoodData().getFoodLevel() >= 11, "bread restored 5 food: " + av.getFoodData().getFoodLevel());
            need(PlayerActionGameTests.count(av, Items.BREAD) == 1, "one bread consumed: " + PlayerActionGameTests.count(av, Items.BREAD));
            need(!av.isUsingItem(), "use finished");
            LOG.info("PARITY|T10|eat|food={}", av.getFoodData().getFoodLevel());
            f.finish();
        });
    }

    /** T09: vanilla Player.attack (the method the interact packet calls) on a real zombie: damage, durability, XP, kill statistic. */
    @GameTest(batch = "parity_09", template = ARENA, templateNamespace = NS, timeoutTicks = 300)
    public static void t09VanillaAttack(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("atk", 4, 1, 4, new ItemStack(Items.DIAMOND_SWORD));
        Zombie z = EntityType.ZOMBIE.create(f.level);
        z.moveTo(f.abs(5, 1, 4).getX() + 0.5, f.abs(5, 1, 4).getY(), f.abs(5, 1, 4).getZ() + 0.5);
        z.setNoAi(true);
        z.setHealth(5.0f);
        f.level.addFreshEntity(z);
        int[] phase = new int[1];
        helper.runAfterDelay(70, () -> {                                    // spawn protection and the attack-strength ticker have run
            need(av.getAttackStrengthScale(0.5f) >= 1.0f, "attack strength recovered by the vanilla tick: " + av.getAttackStrengthScale(0.5f));
            av.attack(z);
            phase[0] = 1;
        });
        helper.succeedWhen(() -> {
            need(phase[0] == 1 && !z.isAlive(), "the zombie died to the vanilla attack");
            need(av.getMainHandItem().getDamageValue() == 1, "sword durability: " + av.getMainHandItem().getDamageValue());
            need(av.getStats().getValue(Stats.ENTITY_KILLED.get(EntityType.ZOMBIE)) == 1, "kill statistic awarded");
            boolean xp = !f.level.getEntitiesOfClass(ExperienceOrb.class, av.getBoundingBox().inflate(8.0)).isEmpty();
            need(xp, "experience dropped (killed by a player)");
            LOG.info("PARITY|T09|attack|killStat=1|durability=1|xpOrb={}", xp);
            f.finish();
        });
    }

    /** T07 oracle: dig speed/progress for the same tool on the same block must equal a second real ServerPlayer's. */
    @GameTest(batch = "parity_07", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t07MiningSpeedMatchesOracle(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("mine", 4, 1, 4, null);
        // A second, plain vanilla ServerPlayer (never added to the level): the oracle only needs its attributes and inventory.
        net.minecraft.server.level.ServerPlayer oracle = new net.minecraft.server.level.ServerPlayer(f.server, f.level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "oracle"));
        oracle.setOnGround(true);                                          // Player.getDigSpeed divides by 5 in the air
        net.minecraft.world.item.Item[] tools = {Items.AIR, Items.WOODEN_AXE, Items.IRON_AXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE};
        net.minecraft.world.level.block.Block[] blocks = {Blocks.OAK_LOG, Blocks.STONE, Blocks.DIRT};
        List<String> rows = new ArrayList<>();
        helper.runAfterDelay(5, () -> {
            for (var tool : tools) for (var block : blocks) {
                av.getInventory().setItem(av.getInventory().selected, new ItemStack(tool));
                oracle.getInventory().setItem(oracle.getInventory().selected, new ItemStack(tool));
                BlockState st = block.defaultBlockState();
                BlockPos pos = f.abs(8, 1, 8);
                float a = st.getDestroyProgress(av, f.level, pos), o = st.getDestroyProgress(oracle, f.level, pos);
                rows.add(tool + "/" + block + " npc=" + a + " oracle=" + o);
                need(a == o, "destroy progress differs: " + rows.get(rows.size() - 1));
            }
            LOG.info("PARITY|T07|destroy-progress equal to a real ServerPlayer for {} tool/block pairs", rows.size());
            f.finish();
        });
    }

    // ------------------------------------------------------------------------------------------------ Phase 3 probes
    /** T13 PROBE: what does vanilla startSleepInBed do for this avatar? Recorded, not guessed. */
    @GameTest(batch = "parity_13", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t13SleepProbe(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("bed", 4, 1, 4, null);
        BlockPos foot = f.abs(5, 1, 4);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST);
        f.level.setBlock(foot, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), 3);
        f.level.setBlock(foot.east(), bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        f.level.setDayTime(14000);
        helper.runAfterDelay(70, () -> {
            String result;
            try {
                result = av.startSleepInBed(foot).<String>map(p -> "PROBLEM:" + p, u -> "OK");
            } catch (Throwable t) {
                result = "THREW " + t;
            }
            boolean sleeping = av.isSleeping();
            helper.runAfterDelay(5, () -> {
                LOG.info("PARITY|T13|PROBE startSleepInBed={}|isSleeping={}|stillSleeping={}|inLevelPlayers={}", "see next", sleeping, av.isSleeping(), f.level.players().contains(av));
                if (av.isSleeping()) av.stopSleepInBed(true, false);
                f.level.setDayTime(1000);
                f.finish();
            });
            LOG.info("PARITY|T13|PROBE startSleepInBed result={}", result);
        });
    }

    /** T15 PROBE: vanilla statistics accumulate in the avatar's counter; are they persisted by the vanilla save path? */
    @GameTest(batch = "parity_15", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t15StatsProbe(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("stat", 4, 1, 4, null);
        av.awardStat(Stats.JUMP);
        int value = av.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
        need(value == 1, "statistic increments in the avatar's counter: " + value);
        File stats = new File(f.server.getWorldPath(LevelResource.PLAYER_STATS_DIR).toFile(), av.getUUID() + ".json");
        f.server.getPlayerList().saveAll();
        boolean persisted = stats.exists();
        LOG.info("PARITY|T15|PROBE stat=1|vanillaPlayerListSaveWroteStatsFile={}|sameCounterAsPlayerList={}", persisted,
                f.server.getPlayerList().getPlayerStats(av) == av.getStats());
        f.finish();
    }

    /** T15b / T14b (D2): statistics and an advancement survive in vanilla's own files: written by the world-save event for a live avatar and
     *  by despawn, and a FRESH vanilla ServerStatsCounter / PlayerAdvancements (what a restart constructs) reads the same values back. */
    @GameTest(batch = "parity_15b", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t15bStatsAndAdvancementsPersist(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("sp", 4, 1, 4, null);
        var server = f.server;
        var jump = Stats.CUSTOM.get(Stats.JUMP);
        av.awardStat(Stats.JUMP, 7);
        var adv = server.getAdvancements().getAdvancement(new net.minecraft.resources.ResourceLocation("minecraft:story/root"));
        need(adv != null, "advancement story/root is loaded");
        String criterion = adv.getCriteria().keySet().iterator().next();
        need(av.getAdvancements().award(adv, criterion), "advancement criterion awarded through the vanilla PlayerAdvancements");
        File statsFile = new File(server.getWorldPath(LevelResource.PLAYER_STATS_DIR).toFile(), av.getUUID() + ".json");
        java.nio.file.Path advFile = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(av.getUUID() + ".json");
        statsFile.delete();
        java.nio.file.Path advPath = advFile;
        try { java.nio.file.Files.deleteIfExists(advPath); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        server.getPlayerList().saveAll();   // since D3 the registered avatar is saved by vanilla itself (the world-save event writer defers to it)
        need(statsFile.exists() && java.nio.file.Files.exists(advPath), "PlayerList.saveAll wrote both vanilla files for the registered avatar");
        // Registry switched off (rollback path): the D2 world-save writer takes over again.
        C1PlayerListRegistry.unregister(server, av);
        statsFile.delete();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new net.minecraftforge.event.level.LevelEvent.Save(f.level));
        need(statsFile.exists(), "unregistered avatar: the D2 world-save writer saves it (rollback path)");
        String name = f.prefix + "sp";
        C1P0Runtime.despawn(server, name);   // second writer path: the detach hook
        var fresh = new net.minecraft.stats.ServerStatsCounter(server, statsFile);
        need(fresh.getValue(jump) == 7, "a fresh vanilla ServerStatsCounter reads the jump statistic back: " + fresh.getValue(jump));
        var reread = new net.minecraft.server.PlayerAdvancements(server.getFixerUpper(), server.getPlayerList(), server.getAdvancements(), advPath, av);
        try {
            need(reread.getOrStartProgress(adv).isDone(), "a fresh vanilla PlayerAdvancements reads the advancement back as done");
        } finally {
            reread.stopListening();
        }
        LOG.info("PARITY|T15b|stats-and-advancements-persist|jump={}|advancement=story/root", fresh.getValue(jump));
        f.finish();
    }

    /** Counts Forge login/logout events of one UUID. */
    public static final class LoginCount {
        final String tag; volatile int in, out;
        LoginCount(String tag) { this.tag = tag; }
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void on(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) { if (e.getEntity().getTags().contains(tag)) in++; }
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void on(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) { if (e.getEntity().getTags().contains(tag)) out++; }
    }

    /** T18 (D3): the avatar is a real PlayerList member, once: selectors @a/@p/@e[type=player], the player count, name/UUID lookups, the
     *  login/logout events, LEAVE_GAME, vanilla PlayerList.saveAll (stats + advancement files), and a clean exit so removeAll never meets it. */
    @GameTest(batch = "parity_18", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t18PlayerListMembership(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        var server = f.server;
        var list = server.getPlayerList();
        int before = list.getPlayerCount();
        LoginCount events = new LoginCount("npcortex_" + f.prefix + "pl");
        MinecraftForge.EVENT_BUS.register(events);   // before the spawn: PlayerLoggedIn fires inside it
        C1PlayerAvatar av = f.npc("pl", 4, 1, 4, null);
        try {
            need(list.getPlayers().contains(av) && list.getPlayer(av.getUUID()) == av, "registered in PlayerList.players and by UUID");
            need(list.getPlayers().stream().filter(p -> p == av).count() == 1, "exactly once (no duplicate member)");
            need(list.getPlayerCount() == before + 1, "player count includes the NPC: " + before + " -> " + list.getPlayerCount());
            need(list.getPlayerByName(av.getGameProfile().getName()) == av, "name lookup (profile name) finds it");
            var src = av.createCommandSourceStack().withPermission(4);
            String tag = "npcortex_" + f.prefix + "pl";
            for (String sel : new String[] {"@a[tag=" + tag + "]", "@p[tag=" + tag + "]", "@e[type=minecraft:player,tag=" + tag + "]", "@r[tag=" + tag + "]"})
                need(server.getCommands().performPrefixedCommand(src, "execute if entity " + sel) == 1, "selector finds the NPC: " + sel);
            need(av.getAdvancements() == list.getPlayerAdvancements(av) && av.getStats() == list.getPlayerStats(av), "PlayerList hands out the avatar's own counters");
            int leave0 = av.getStats().getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.LEAVE_GAME));
            av.awardStat(Stats.JUMP, 3);
            java.io.File statsFile = new java.io.File(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR).toFile(), av.getUUID() + ".json");
            statsFile.delete();
            list.saveAll();
            need(statsFile.exists(), "vanilla PlayerList.saveAll wrote the stats file for the registered NPC");
            C1P0Runtime.despawn(server, f.prefix + "pl");
            need(!list.getPlayers().contains(av) && list.getPlayer(av.getUUID()) == null, "removed from the PlayerList on despawn");
            need(events.in == 1 && events.out == 1, "PlayerLoggedIn/Out fired once each: " + events.in + "/" + events.out);
            var fresh = new net.minecraft.stats.ServerStatsCounter(server, statsFile);
            need(fresh.getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.LEAVE_GAME)) == leave0 + 1, "LEAVE_GAME recorded and saved on despawn");
            need(fresh.getValue(net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.JUMP)) == 3, "jump statistic saved");
            LOG.info("PARITY|T18|playerlist-membership|count={}->{}|login={}|logout={}|selectors=@a,@p,@r,@e[type=player]", before, before + 1, events.in, events.out);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(events);
        }
        f.finish();
    }

    /** T18b (D3): server stop order - unregisterAll leaves nobody for PlayerList.removeAll (which would call disconnect() on a Connection the
     *  avatar does not have). */
    @GameTest(batch = "parity_18b", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t18bShutdownLeavesNoNpcInPlayerList(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar a = f.npc("s1", 3, 1, 3, null), b = f.npc("s2", 6, 1, 6, null);
        var list = f.server.getPlayerList();
        need(list.getPlayers().contains(a) && list.getPlayers().contains(b), "both registered");
        C1PlayerListRegistry.unregisterAll(f.server);
        need(list.getPlayers().stream().noneMatch(p -> p instanceof C1PlayerAvatar), "no avatar left in PlayerList after unregisterAll");
        C1PlayerListRegistry.register(f.server, a);   // idempotent re-register keeps the single-member invariant for the fixture teardown
        C1PlayerListRegistry.register(f.server, a);
        need(list.getPlayers().stream().filter(p -> p == a).count() == 1, "register is idempotent");
        LOG.info("PARITY|T18b|shutdown-order|ok");
        f.finish();
    }

    /** T16: a portal/vanilla dimension change must be refused (fail-closed) instead of running ServerPlayer.changeDimension on a passive connection. */
    @GameTest(batch = "parity_16", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t16VanillaDimensionChangeRefused(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("dim", 4, 1, 4, null);
        var nether = f.server.getLevel(net.minecraft.world.level.Level.NETHER);
        need(nether != null, "nether loaded");
        var before = av.level().dimension();
        Object r = av.changeDimension(nether, nether.getPortalForcer());
        need(r == null && av.level().dimension() == before && !av.isRemoved(), "refused, avatar untouched");
        LOG.info("PARITY|T16|vanilla changeDimension refused=true");
        f.finish();
    }
}
