package io.github.sirp00m.npcortex;

import static io.github.sirp00m.npcortex.NativeGameTests.need;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * D4 engine contracts (C1PlayerActions): every test asserts a vanilla CONSEQUENCE (damage numbers, durability, events, food, potion effect,
 * bed/sleep state, menu slots) and refusals made by vanilla rules, never an outcome the test or facade wrote. Headless avatar, no connected
 * human oracle; the durable PAP/chat wiring of these actions is NOT part of this class (see C1PlayerActions).
 */
@GameTestHolder("npcortexparity")
@PrefixGameTestTemplate(false)
public final class ParityActionGameTests {
    private static final String ARENA = "arena", NS = "npcortexparity";
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private ParityActionGameTests() {}

    public static final class Events {
        volatile int attack, hurt, rightClickItem;
        @SubscribeEvent public void on(net.minecraftforge.event.entity.player.AttackEntityEvent e) { attack++; }
        @SubscribeEvent public void on(net.minecraftforge.event.entity.living.LivingHurtEvent e) { if (e.getSource().getEntity() instanceof C1PlayerAvatar) hurt++; }
        @SubscribeEvent public void on(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem e) { if (e.getEntity() instanceof C1PlayerAvatar) rightClickItem++; }
    }

    /** D4-attack: sword damage, durability, cooldown scaling on the immediate second swing, Forge events, out-of-reach refusal. */
    @GameTest(batch = "parity_d4a", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void d4AttackUsesVanillaCombat(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("atk", 4, 1, 4, new ItemStack(Items.IRON_SWORD));
        Zombie z = EntityType.ZOMBIE.create(f.level);
        z.moveTo(f.abs(4, 1, 6).getX() + 0.5, f.abs(4, 1, 6).getY(), f.abs(4, 1, 6).getZ() + 0.5);
        z.setNoAi(true);
        f.level.addFreshEntity(z);
        Events ev = new Events();
        MinecraftForge.EVENT_BUS.register(ev);
        helper.runAfterDelay(70, () -> {   // spawn protection does not affect OUR attack; the wait lets the attack-strength ticker fill (full-charge swing)
            try {
                float hp0 = z.getHealth();
                int dur0 = av.getMainHandItem().getDamageValue();
                var r1 = C1PlayerActions.attack(av, z);
                need(r1.ok(), "attack accepted: " + r1.detail());
                float hit1 = hp0 - z.getHealth();
                // Iron sword = 6 attack damage; the zombie's natural armour (2) and the attack-strength scale (0.2 + 0.8 s^2) shave a little: 5.9 observed.
                need(hit1 >= 5.0f && hit1 <= 6.5f, "iron sword full charge deals its vanilla damage (~6, after armour/strength scale): " + hit1);
                need(av.getMainHandItem().getDamageValue() == dur0 + 1, "sword durability -1: " + av.getMainHandItem().getDamageValue());
                z.invulnerableTime = 0;
                float hp1 = z.getHealth();
                var r2 = C1PlayerActions.attack(av, z);
                need(r2.ok(), "second attack accepted");
                float hit2 = hp1 - z.getHealth();
                need(hit2 < hit1, "immediate second swing is cooldown-scaled by vanilla: " + hit2 + " < " + hit1);
                need(ev.attack == 2 && ev.hurt >= 1, "Forge AttackEntityEvent x2 / LivingHurtEvent fired: " + ev.attack + "/" + ev.hurt);
                z.moveTo(z.getX(), z.getY(), z.getZ() + 8.0);
                var r3 = C1PlayerActions.attack(av, z);
                need(!r3.ok() && r3.detail().contains("reach"), "out of reach is refused by the handler rule: " + r3.detail());
                LOG.info("PARITY|D4|attack|hit1={}|hit2={}|events={}/{}", hit1, hit2, ev.attack, ev.hurt);
            } finally {
                MinecraftForge.EVENT_BUS.unregister(ev);
            }
            f.finish();
        });
    }

    /** D4-use_item: bread is eaten over the vanilla use duration (+food, -1 stack); a healing potion applies its effect and returns the bottle. */
    @GameTest(batch = "parity_d4b", template = ARENA, templateNamespace = NS, timeoutTicks = 300)
    public static void d4UseItemFoodAndPotion(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("use", 4, 1, 4, new ItemStack(Items.BREAD, 3));
        Events ev = new Events();
        MinecraftForge.EVENT_BUS.register(ev);
        av.getFoodData().setFoodLevel(10);
        av.getFoodData().setSaturation(0f);
        av.setHealth(8.0f);
        boolean[] drank = new boolean[1];
        var r = C1PlayerActions.useItem(av, InteractionHand.MAIN_HAND);
        need(r.ok(), "use accepted: " + r.detail());
        need(av.isUsingItem(), "vanilla began the item-use phase");
        helper.runAfterDelay(50, () -> {
            need(av.getMainHandItem().getCount() == 2 && av.getFoodData().getFoodLevel() >= 15, "bread eaten: stack=" + av.getMainHandItem().getCount() + " food=" + av.getFoodData().getFoodLevel());
            need(ev.rightClickItem == 1, "RightClickItem fired once: " + ev.rightClickItem);
            av.getInventory().setItem(av.getInventory().selected, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRONG_HEALING));
            float before = av.getHealth();
            need(C1PlayerActions.useItem(av, InteractionHand.MAIN_HAND).ok(), "drink accepted");
            helper.runAfterDelay(50, () -> {
                need(av.getHealth() >= before + 6.0f || av.getHealth() >= 19.9f, "healing II potion applied by vanilla: " + before + " -> " + av.getHealth());
                need(av.getMainHandItem().is(Items.GLASS_BOTTLE), "empty bottle returned: " + av.getMainHandItem());
                drank[0] = true;
                MinecraftForge.EVENT_BUS.unregister(ev);
                LOG.info("PARITY|D4|use_item|bread+potion|ok");
                f.finish();
            });
        });
    }

    /** D4-sleep: bed use at night starts sleeping (spawn point set), by day vanilla refuses; waking leaves the bed. */
    @GameTest(batch = "parity_d4c", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void d4SleepThroughBedUse(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("slp", 4, 1, 4, null);
        BlockPos foot = f.abs(5, 1, 4);
        f.level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST), 3);
        f.level.setBlock(foot.east(), Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.EAST)
                .setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
        helper.runAfterDelay(5, () -> {
            f.level.setDayTime(1000);
            var day = C1PlayerActions.sleep(av, foot);
            need(!av.isSleeping(), "by day vanilla refuses: " + day);
            f.level.setDayTime(14000);
            helper.runAfterDelay(5, () -> afterNightFalls(helper, f, av, foot));   // the sky-darkness value (isDay) refreshes on the level's next ticks
        });
    }

    private static void afterNightFalls(GameTestHelper helper, NativeGameTests.Fx f, C1PlayerAvatar av, BlockPos foot) {
        {
            var night = C1PlayerActions.sleep(av, foot);
            need(night.ok() && av.isSleeping(), "at night the bed puts the NPC to sleep: " + night + " isDay=" + f.level.isDay() + " time=" + f.level.getDayTime());
            need(foot.equals(av.getSleepingPos().orElse(null)) || foot.east().equals(av.getSleepingPos().orElse(null)), "sleeping position is the bed");
            need(av.getRespawnPosition() != null, "bed set the spawn point (vanilla startSleepInBed)");
            need(C1PlayerActions.wake(av).ok() && !av.isSleeping(), "wake-up leaves the bed");
            f.level.setDayTime(1000);
            LOG.info("PARITY|D4|sleep|day-refused+night-slept+woke|ok");
            f.finish();
        }
    }

    /** D4-containers: chest opens by use, quick-move transfers a stack, mayPlace rules hold in a furnace, closing restores the inventory menu,
     *  and walking away invalidates the menu (vanilla ServerPlayer.tick closes it). */
    @GameTest(batch = "parity_d4d", template = ARENA, templateNamespace = NS, timeoutTicks = 200)
    public static void d4ContainerOpenTransferAndRules(GameTestHelper helper) {
        NativeGameTests.Fx f = NativeGameTests.Fx.logs(helper);
        C1PlayerAvatar av = f.npc("cnt", 4, 1, 4, null);
        BlockPos chest = f.abs(5, 1, 4);
        f.level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ((ChestBlockEntity) f.level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.STICK, 5));
        BlockPos furnace = f.abs(3, 1, 4);
        f.level.setBlock(furnace, Blocks.FURNACE.defaultBlockState(), 3);
        helper.runAfterDelay(5, () -> {
            var open = C1PlayerActions.openContainer(av, chest);
            need(open.ok() && av.containerMenu instanceof net.minecraft.world.inventory.ChestMenu, "chest opened as a ChestMenu: " + open);
            need(C1PlayerActions.click(av, 0, 0, ClickType.QUICK_MOVE).ok(), "quick-move accepted");
            need(f.itemCount(av, Items.STICK) == 5, "5 sticks moved into the NPC inventory: " + f.itemCount(av, Items.STICK));
            need(((ChestBlockEntity) f.level.getBlockEntity(chest)).getItem(0).isEmpty(), "the chest slot is empty");
            need(!C1PlayerActions.click(av, 999, 0, ClickType.PICKUP).ok(), "invalid slot index refused");
            need(C1PlayerActions.closeContainer(av).ok() && av.containerMenu == av.inventoryMenu, "closed: back to the inventory menu");

            av.getInventory().setItem(av.getInventory().selected, new ItemStack(Items.DIRT, 4));
            var fo = C1PlayerActions.openContainer(av, furnace);
            need(fo.ok() && av.containerMenu instanceof net.minecraft.world.inventory.FurnaceMenu, "furnace opened: " + fo);
            need(C1PlayerActions.click(av, 30 + av.getInventory().selected, 0, ClickType.PICKUP).ok(), "picked up the dirt stack");
            need(av.containerMenu.getCarried().is(Items.DIRT), "carrying dirt");
            C1PlayerActions.click(av, 1, 0, ClickType.PICKUP);   // fuel slot
            need(av.containerMenu.getSlot(1).getItem().isEmpty(), "dirt is not fuel: vanilla mayPlace refused it");
            need(av.containerMenu.getCarried().is(Items.DIRT), "the carried stack stays in hand");
            C1PlayerActions.closeContainer(av);

            C1PlayerActions.openContainer(av, chest);
            need(av.containerMenu != av.inventoryMenu, "chest open again");
            av.teleportTo(av.getX() + 20.0, av.getY(), av.getZ());
            var far = C1PlayerActions.click(av, 0, 0, ClickType.PICKUP);
            need(!far.ok() && far.detail().contains("no longer valid"), "walked away: the menu is invalid: " + far);
            LOG.info("PARITY|D4|containers|chest+furnace-rules+distance|ok");
            f.finish();
        });
    }
}
