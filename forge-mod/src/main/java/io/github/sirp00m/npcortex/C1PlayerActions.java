package io.github.sirp00m.npcortex;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * D4: player actions by the avatar through the SAME vanilla services a connected client's packets reach, with the SAME validation the
 * packet handlers apply. Source evidence (javap -c of ServerGamePacketListenerImpl, Forge 47.4.10): the handlers are validation + a
 * call into the service, and nothing in between depends on the Connection:
 *  - handleInteract(ATTACK): entity resolved in the level, world border check, ServerPlayer.canReachRaw(entity, 3.0), then Player.attack(entity)
 *  - handleUseItemOn: sequence ack, canReach(pos, 1.0), hit location within 1.0000001 of the block centre, build height,
 *    ServerLevel.mayInteract, then ServerPlayerGameMode.useItemOn, then swing
 *  - handleUseItem: ServerPlayerGameMode.useItem
 *  - handleContainerClick: container id match, not spectator, stillValid, isValidSlotIndex, AbstractContainerMenu.clicked, broadcastChanges
 *  - sleeping/opening a container is the block's own use() (BedBlock -> startSleepInBed, chests -> openMenu) through useItemOn
 * so no virtual Connection is needed for these. NOT done here: the durable PAP ledger / chat / Manager wiring (these are the engine
 * contracts only; an action that must survive a crash still needs its ledger entry), and client-only effects (animation, sound sync).
 * The facade never edits an outcome value: it validates, aims, and calls vanilla.
 */
final class C1PlayerActions {
    record Result(boolean ok, String detail) {
        static Result ok(String d) { return new Result(true, d); }
        static Result no(String d) { return new Result(false, d); }
    }

    private C1PlayerActions() {}

    private static Result gate(C1PlayerAvatar av) {
        if (av == null || av.isRemoved() || !av.isAlive()) return Result.no("the NPC is not alive");
        if (av.getServer() == null) return Result.no("no server");
        if (av.isSpectator()) return Result.no("spectators cannot do this");
        if (C1SurvivalParity.claimed(av)) return Result.no("a native/PAP action owns this NPC right now");
        return null;
    }

    private static void face(C1PlayerAvatar av, Vec3 at) { av.lookAt(EntityAnchorArgument.Anchor.EYES, at); }

    /** Left-click on an entity: vanilla Player.attack (cooldown scale, enchantments, crits, knockback, sweeping, XP/stat/loot on kill, Forge events). */
    static Result attack(C1PlayerAvatar av, Entity target) {
        Result g = gate(av);
        if (g != null) return g;
        if (target == null || target.isRemoved() || target.level() != av.level()) return Result.no("no such target in this level");
        if (target == av || target instanceof ItemEntity || target instanceof ExperienceOrb || target instanceof AbstractArrow || target instanceof net.minecraft.world.entity.player.Player && !av.serverLevel().getServer().isPvpAllowed())
            return Result.no("not an attackable target (vanilla disconnects a client that sends this)");
        if (!av.serverLevel().getWorldBorder().isWithinBounds(target.blockPosition())) return Result.no("target outside the world border");
        if (!av.canReachRaw(target, 3.0D)) return Result.no("target out of reach");
        face(av, target.getEyePosition());
        av.resetLastActionTime();
        av.attack(target);
        av.swing(InteractionHand.MAIN_HAND, true);
        return Result.ok("attacked");
    }

    /** ServerboundSetCarriedItemPacket: choose the hotbar slot (0-8), as the mouse wheel / number keys do. */
    static Result select(C1PlayerAvatar av, int slot) {
        Result g = gate(av);
        if (g != null) return g;
        if (slot < 0 || slot >= net.minecraft.world.entity.player.Inventory.getSelectionSize()) return Result.no("not a hotbar slot");
        av.getInventory().selected = slot;
        av.resetLastActionTime();
        return Result.ok("selected hotbar slot " + slot);
    }

    /** Right-click in the air: vanilla ServerPlayerGameMode.useItem (food/drink start use, throwables, bows, cooldowns, RightClickItem event). */
    static Result useItem(C1PlayerAvatar av, InteractionHand hand) {
        Result g = gate(av);
        if (g != null) return g;
        ItemStack stack = av.getItemInHand(hand);
        if (stack.isEmpty()) return Result.no("empty hand");
        if (!stack.isItemEnabled(av.serverLevel().enabledFeatures())) return Result.no("item disabled by the world's feature flags");
        av.resetLastActionTime();
        InteractionResult r = av.gameMode.useItem(av, av.serverLevel(), stack, hand);
        if (r.shouldSwing()) av.swing(hand, true);
        return r == InteractionResult.PASS || r == InteractionResult.FAIL ? Result.no("use refused: " + r + " (hand=" + stack.getItem() + " x" + stack.getCount() + ", food=" + av.getFoodData().getFoodLevel() + ")") : Result.ok("used: " + r);
    }

    /** Right-click on a block face: beds, chests, levers, doors, placement... exactly ServerPlayerGameMode.useItemOn after the handler's checks. */
    static Result useItemOn(C1PlayerAvatar av, BlockPos pos, Direction face, InteractionHand hand) {
        Result g = gate(av);
        if (g != null) return g;
        if (!av.serverLevel().isLoaded(pos)) return Result.no("block not loaded");
        if (!av.canReach(pos, 1.0D)) return Result.no("block out of reach");
        if (pos.getY() >= av.level().getMaxBuildHeight()) return Result.no("above build height");
        if (!av.serverLevel().mayInteract(av, pos)) return Result.no("spawn protection / world border: not allowed to interact here");
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        face(av, hit);
        av.resetLastActionTime();
        InteractionResult r = av.gameMode.useItemOn(av, av.serverLevel(), av.getItemInHand(hand), hand, new BlockHitResult(hit, face, pos, false));
        if (r.shouldSwing()) av.swing(hand, true);
        return r.consumesAction() ? Result.ok("used block: " + r) : Result.no("block use did nothing: " + r);
    }

    /** Sleeping IS right-clicking a bed (BedBlock.use -> startSleepInBed with its time-of-day / monsters / distance / occupied checks). */
    static Result sleep(C1PlayerAvatar av, BlockPos bed) {
        if (av != null) av.lastSystemMessage = "";
        Result r = useItemOn(av, bed, Direction.UP, InteractionHand.MAIN_HAND);
        return r.ok() && !av.isSleeping() ? Result.no("bed used but not sleeping (vanilla says: \"" + av.lastSystemMessage + "\")") : r;
    }

    /** What a client does on STOP_SLEEPING: leave the bed, update the sleeping list, no spawn-point reset. */
    static Result wake(C1PlayerAvatar av) {
        if (av == null || !av.isSleeping()) return Result.no("not sleeping");
        av.stopSleepInBed(false, true);
        return Result.ok("woke up");
    }

    /** Opening a container is right-clicking it. Returns ok only when a real container menu is open afterwards. */
    static Result openContainer(C1PlayerAvatar av, BlockPos pos) {
        Result r = useItemOn(av, pos, Direction.UP, InteractionHand.MAIN_HAND);
        if (!r.ok()) return r;
        return av.containerMenu == av.inventoryMenu ? Result.no("block used, but no container opened") : Result.ok("opened " + av.containerMenu.getClass().getSimpleName());
    }

    /** handleContainerClick: same checks, then AbstractContainerMenu.clicked (slot rules, mayPlace/mayPickup, quick-move, carried stack). */
    static Result click(C1PlayerAvatar av, int slot, int button, ClickType type) {
        Result g = gate(av);
        if (g != null) return g;
        var menu = av.containerMenu;
        if (!menu.stillValid(av)) return Result.no("the container is no longer valid (too far / gone)");
        if (!menu.isValidSlotIndex(slot)) return Result.no("invalid slot index " + slot);
        av.resetLastActionTime();
        menu.clicked(slot, button, type, av);
        menu.broadcastChanges();
        return Result.ok("clicked " + type + " slot " + slot);
    }

    static Result closeContainer(C1PlayerAvatar av) {
        if (av == null || av.containerMenu == av.inventoryMenu) return Result.no("no container open");
        av.closeContainer();
        return Result.ok("closed");
    }
}
