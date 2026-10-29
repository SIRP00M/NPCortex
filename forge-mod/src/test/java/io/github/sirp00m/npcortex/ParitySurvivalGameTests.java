package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Parity P1 (T01-T06): the REAL custom avatar must be damaged/healed/fed by the vanilla tick, not by NPCortex code. Each test sets only
 * the PRECONDITION (an effect, a fire timer, an air supply, a hunger level) and asserts vanilla's consequence. Lines "PARITY|..." are
 * evidence. Not covered: a connected human as oracle in the same run (see docs/parity/PARITY_CANDIDATE.md), dedicated server.
 */
@GameTestHolder("npcortexparity")
@PrefixGameTestTemplate(false)
public final class ParitySurvivalGameTests {
    private static final String ARENA = "arena", NS = "npcortexparity";
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private ParitySurvivalGameTests() {}

    /** GameTests of a namespace run in parallel and the parity switches are global: only T05 touches one (autoPickup), sequentially in one test. */
    private static void reset() {}

    /** T01a fire: a burning player takes vanilla fire damage and the timer counts down. */
    @GameTest(batch = "parity_01", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t01FireBurns(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("fire", 4, 1, 4, null);
        float before = av.getHealth();
        av.setSecondsOnFire(5);
        helper.succeedWhen(() -> {
            need(av.getHealth() < before, "fire damage applied by vanilla baseTick: " + av.getHealth());
            need(av.getRemainingFireTicks() < 100, "fire timer counts down: " + av.getRemainingFireTicks());
            LOG.info("PARITY|T01a|fire|health={}|fireTicks={}", av.getHealth(), av.getRemainingFireTicks());
            f.finish();
        });
    }

    /** T01b lava: standing in lava hurts and ignites. */
    @GameTest(batch = "parity_01", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void t01LavaHurts(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        f.set(8, 0, 8, Blocks.LAVA);
        f.set(8, 1, 8, Blocks.LAVA);
        C1PlayerAvatar av = f.npc("lava", 8, 1, 8, null);
        helper.succeedWhen(() -> {
            need(av.getHealth() < 20.0f || !av.isAlive(), "lava damage: " + av.getHealth());
            LOG.info("PARITY|T01b|lava|health={}|onFire={}", av.getHealth(), av.isOnFire());
            f.finish();
        });
    }

    /** T02 poison ticks damage and the effect duration runs down; regeneration heals. */
    @GameTest(batch = "parity_02", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t02PoisonAndRegeneration(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("eff", 4, 1, 4, null);
        av.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        int start = av.getEffect(MobEffects.POISON).getDuration();
        helper.succeedWhen(() -> {
            need(av.getHealth() < 20.0f, "poison damage: " + av.getHealth());
            MobEffectInstance e = av.getEffect(MobEffects.POISON);
            need(e != null && e.getDuration() < start, "poison duration decreases (effect ticking)");
            LOG.info("PARITY|T02a|poison|health={}|duration={}", av.getHealth(), e.getDuration());
            f.finish();
        });
    }

    @GameTest(batch = "parity_02", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t02Regeneration(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("reg", 4, 1, 4, null);
        av.setHealth(10.0f);
        av.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
        helper.succeedWhen(() -> {
            need(av.getHealth() > 10.0f, "regeneration heals: " + av.getHealth());
            LOG.info("PARITY|T02b|regeneration|health={}", av.getHealth());
            f.finish();
        });
    }

    /** T03 drowning: air supply falls in water; with no air, vanilla drowning damage follows. */
    @GameTest(batch = "parity_03", template = ARENA, templateNamespace = NS, timeoutTicks = 300)
    public static void t03Drowning(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        for (int y = 0; y <= 4; y++) f.set(8, y, 8, Blocks.WATER);
        C1PlayerAvatar av = f.npc("drown", 8, 1, 8, null);
        av.setAirSupply(1);
        helper.succeedWhen(() -> {
            need(av.getAirSupply() < 1 || av.getHealth() < 20.0f, "air consumed in water");
            need(av.getHealth() < 20.0f, "drowning damage: " + av.getHealth());
            LOG.info("PARITY|T03|drowning|air={}|health={}", av.getAirSupply(), av.getHealth());
            f.finish();
        });
    }

    /** T04 hunger: exhaustion costs food; with food and saturation the player regenerates. */
    @GameTest(batch = "parity_04", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t04Hunger(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("food", 4, 1, 4, null);
        av.getFoodData().setFoodLevel(10);
        av.getFoodData().setSaturation(0.0f);
        av.causeFoodExhaustion(5.0f);
        helper.succeedWhen(() -> {
            need(av.getFoodData().getFoodLevel() < 10, "exhaustion consumed food: " + av.getFoodData().getFoodLevel());
            LOG.info("PARITY|T04a|hunger|food={}|saturation={}", av.getFoodData().getFoodLevel(), av.getFoodData().getSaturationLevel());
            f.finish();
        });
    }

    @GameTest(batch = "parity_04", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t04NaturalRegeneration(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("nreg", 4, 1, 4, null);
        av.setHealth(10.0f);
        av.getFoodData().setFoodLevel(20);
        av.getFoodData().setSaturation(20.0f);
        helper.succeedWhen(() -> {
            need(av.getHealth() > 10.0f, "natural regeneration: " + av.getHealth());
            LOG.info("PARITY|T04b|natural-regeneration|health={}", av.getHealth());
            f.finish();
        });
    }

    /** T04c: the REAL C1 walk (P1-A controller) feeds vanilla's checkMovementStatistics: the walk statistic grows by about the distance walked,
     *  and walking costs no exhaustion (vanilla Player.checkMovementStatistics: walk 0.0/m). A sprint probe then calls the same vanilla method
     *  directly (the controller never sprints): sprint statistic and 0.1 exhaustion per metre. */
    @GameTest(batch = "parity_04", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t04cWalkingStatisticsAndExhaustion(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("walk", 4, 1, 4, null);
        var walkStat = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.WALK_ONE_CM);
        var sprintStat = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.SPRINT_ONE_CM);
        boolean[] started = new boolean[1];
        int[] walk0 = new int[1];
        f.whenSettled(() -> {
            walk0[0] = av.getStats().getValue(walkStat);
            String r = C1P1MotionRuntime.move(f.server, f.prefix + "walk", "forward", 3.0);
            need(!r.startsWith("BLOCKED") && !r.contains("refused"), "walk accepted: " + r);
            started[0] = true;
        });
        helper.succeedWhen(() -> {
            need(started[0], "walk not started yet");
            int walked = av.getStats().getValue(walkStat) - walk0[0];
            need(walked >= 250, "walk statistic follows the real walk (cm): " + walked);
            need(walked <= 400, "walk statistic is not inflated (double counting): " + walked);
            need(av.getFoodData().getExhaustionLevel() < 0.05f, "vanilla walking costs no exhaustion: " + av.getFoodData().getExhaustionLevel());
            int sprint0 = av.getStats().getValue(sprintStat);
            av.setSprinting(true);
            av.checkMovementStatistics(3.0, 0.0, 0.0);
            av.setSprinting(false);
            need(av.getStats().getValue(sprintStat) - sprint0 == 300, "sprint statistic (cm): " + (av.getStats().getValue(sprintStat) - sprint0));
            need(Math.abs(av.getFoodData().getExhaustionLevel() - 0.3f) < 0.05f, "sprint exhaustion 0.1/m: " + av.getFoodData().getExhaustionLevel());
            // LIMIT: walk statistic = real controller walk; the sprint numbers prove vanilla's method on this avatar, NOT actual NPC sprint movement (NOT TESTED).
            LOG.info("PARITY|T04c|walk-stats|walkCm={}|exhaustionAfterSprintProbe={}|sprint=DIRECT_METHOD_CALL_NOT_MOVEMENT", walked, av.getFoodData().getExhaustionLevel());
            f.finish();
        });
    }

    /** Counts Forge LivingDeathEvents of one UUID (an event, not a state peek: proves vanilla death hooks fire exactly once). */
    public static final class DeathCount {
        final java.util.UUID id; volatile int n;
        DeathCount(java.util.UUID id) { this.id = id; }
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void on(net.minecraftforge.event.entity.living.LivingDeathEvent e) { if (e.getEntity().getUUID().equals(id)) n++; }
    }

    /** T12-mid: a lethal vanilla tick (burning at 0.5 HP, inside the survival phase) must reach the C1 lifecycle exactly once, and the
     *  repeated hurt() calls of the dying tick must not queue a second death or leave the fail-closed TRANSITION phase stuck. */
    @GameTest(batch = "parity_12", template = ARENA, templateNamespace = NS, timeoutTicks = 800)
    public static void t12DeathDuringSurvivalPhase(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("die", 4, 1, 4, null);
        DeathCount deaths = new DeathCount(av.getUUID());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(deaths);
        long[] seen = {-1};
        av.setHealth(0.5f);
        av.setSecondsOnFire(15);
        helper.succeedWhen(() -> {
            need(deaths.n >= 1, "vanilla fire killed the avatar and the death event fired (health=" + av.getHealth() + " fireTicks=" + av.getRemainingFireTicks()
                    + " invulnTicks=" + av.invulnerableTime + " removed=" + av.isRemoved() + " phase=" + C1LifecycleData.get(f.server).state(av.getUUID()).phase() + ")");
            if (seen[0] < 0) seen[0] = helper.getTick();
            need(helper.getTick() - seen[0] >= 40, "settling after death");
            need(deaths.n == 1, "exactly one LivingDeathEvent: " + deaths.n);
            var phase = C1LifecycleData.get(f.server).state(av.getUUID()).phase();
            need(phase != C1LifecyclePhase.TRANSITION, "lifecycle left TRANSITION: " + phase);
            LOG.info("PARITY|T12m|death-in-survival-phase|deaths={}|phase={}|removed={}", deaths.n, phase, av.isRemoved());
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(deaths);
            f.finish();
        });
    }

    /** T05: (1) with the explicit-pickup contract (autoPickup=false) a drop at the avatar's feet stays on the ground; (2) with autoPickup=true
     *  vanilla pickup takes it through the same playerTouch path, only after the pickup delay. One test: the switch is global. */
    @GameTest(batch = "parity_05", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t05PickupContractAndVanillaDelay(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1SurvivalParity.autoPickup = false;
        C1PlayerAvatar av = f.npc("pk", 4, 1, 4, null);
        ItemEntity first = drop(f, av, 0);
        ItemEntity[] second = new ItemEntity[1];
        helper.runAfterDelay(60, () -> {
            need(!first.isRemoved() && first.getItem().getCount() == 3, "contract: the drop is still on the ground");
            LOG.info("PARITY|T05a|contract-kept|count={}", first.getItem().getCount());
            first.discard();
            C1SurvivalParity.autoPickup = true;
            second[0] = drop(f, av, 40);
        });
        helper.runAfterDelay(80, () -> need(second[0] != null && !second[0].isRemoved(), "still within the pickup delay: not picked up yet"));
        helper.succeedWhen(() -> {
            need(second[0] != null && second[0].isRemoved(), "picked up after the delay");
            need(PlayerActionGameTests.count(av, Items.OAK_LOG) == 3, "inventory has the logs: " + PlayerActionGameTests.count(av, Items.OAK_LOG));
            LOG.info("PARITY|T05b|auto-pickup|logs={}", PlayerActionGameTests.count(av, Items.OAK_LOG));
            C1SurvivalParity.autoPickup = true;
            f.finish();
        });
    }

    /** T05c (D1 arbiter): while a native break attempt owns the actor, an unclaimed vanilla pickup is refused (the verifier counts the drops);
     *  once the attempt is terminal, the same free drop is taken by vanilla touch pickup. Default autoPickup=true. */
    @GameTest(batch = "parity_05c", template = ARENA, templateNamespace = NS, timeoutTicks = 800)
    public static void t05cAutoPickupYieldsToAnActiveClaim(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1SurvivalParity.autoPickup = true;   // after the fixture (it pins the explicit contract); batches run one after another, so no race
        NativeGameTests.standingLog(f, "a", 6, 6, new ItemStack(Items.IRON_PICKAXE));   // slow on logs: the attempt stays active for a while
        C1PlayerAvatar av = f.avatars.get(0);
        ItemEntity[] free = new ItemEntity[1];
        boolean[] claimedChecked = new boolean[1];
        f.bind("a", () -> {
            f.go("a", NativeEffectFlow.Action.BREAK);
            helper.runAfterDelay(3, () -> {
                need(NativeEffectRuntime.pending(f.server, C1IdentityData.get(f.server).find(f.prefix + "a").actorId()), "the attempt owns the actor");
                free[0] = drop(f, av, 0);
                helper.runAfterDelay(10, () -> {
                    need(!free[0].isRemoved(), "claimed actor: vanilla pickup refused while the attempt is active");
                    claimedChecked[0] = true;
                });
            });
        });
        helper.succeedWhen(() -> {
            need(claimedChecked[0] && "EFFECT_VERIFIED".equals(f.stateOfRun(0)), "the attempt finishes: " + f.stateOfRun(0));
            need(free[0].isRemoved(), "after the terminal the free drop is taken by vanilla touch pickup");
            LOG.info("PARITY|T05c|auto-pickup-yields-to-claim|ok");
            f.finish();
        });
    }

    private static ItemEntity drop(NativeGameTests.Fx f, C1PlayerAvatar av, int delay) {
        ItemEntity ie = new ItemEntity(f.level, av.getX(), av.getY(), av.getZ(), new ItemStack(Items.OAK_LOG, 3));
        ie.setDeltaMovement(Vec3.ZERO);
        ie.setPickUpDelay(delay);
        f.level.addFreshEntity(ie);
        return ie;
    }

    /** T06 fall damage plus the double-physics guard: the avatar lands once, at floor level, and stays put.
     *  Vanilla spawn protection (60 ticks) rejects damage, so the avatar is lifted only after that has run out. */
    @GameTest(batch = "parity_06", template = ARENA, templateNamespace = NS, timeoutTicks = 400)
    public static void t06FallDamageAndNoDoublePhysics(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("fall", 4, 1, 4, null);
        double floorY = f.abs(4, 1, 4).getY();
        double[] settled = new double[3];
        boolean[] lifted = new boolean[1];
        helper.runAfterDelay(70, () -> {
            av.setPos(av.getX(), floorY + 5.0, av.getZ());
            av.setOnGround(false);
            lifted[0] = true;
            LOG.info("PARITY|T06|lifted y={} floor={}", av.getY(), floorY);
        });
        helper.succeedWhen(() -> {
            need(lifted[0], "waiting for the lift");
            need(av.onGround() && Math.abs(av.getY() - floorY) < 0.01, "landed on the floor: y=" + av.getY() + " floor=" + floorY);
            need(av.getHealth() < 20.0f, "fall damage from 5 blocks: " + av.getHealth());
            LOG.info("PARITY|T06|fall|health={}|y={}", av.getHealth(), av.getY());
            settled[0] = av.getX(); settled[1] = av.getY(); settled[2] = av.getZ();
            helper.runAfterDelay(40, () -> {
                need(av.getX() == settled[0] && av.getY() == settled[1] && av.getZ() == settled[2], "no drift after landing (single physics owner)");
                need(av.getDeltaMovement().horizontalDistanceSqr() < 1e-6, "no residual horizontal motion");
                f.finish();
            });
        });
    }

    /** Idle grounded avatar: 100 ticks of the vanilla phase must not move it at all (travel suppressed in the survival phase). */
    @GameTest(batch = "parity_06", template = ARENA, templateNamespace = NS, timeoutTicks = 300)
    public static void t06IdleAvatarDoesNotDrift(GameTestHelper helper) {
        reset();
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("idle", 4, 1, 4, null);
        helper.runAfterDelay(5, () -> {
            Vec3 start = av.position();
            helper.runAfterDelay(100, () -> {
                need(av.position().distanceToSqr(start) < 1e-9, "idle avatar position unchanged: " + start + " -> " + av.position());
                need(av.isAlive() && av.getHealth() == 20.0f, "healthy and alive");
                LOG.info("PARITY|T06b|idle|drift={}", av.position().distanceToSqr(start));
                f.finish();
                helper.succeed();
            });
        });
    }
}
