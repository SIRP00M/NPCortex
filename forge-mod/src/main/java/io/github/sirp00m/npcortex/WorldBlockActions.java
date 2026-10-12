package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Bounded world edits. Off by default; no arbitrary block or position input. */
public final class WorldBlockActions {
    private WorldBlockActions() {}

    private static boolean permitted(ServerPlayer owner) {
        return NPCortexConfig.WORLD_EDITS_ENABLED.get()
                && owner.getServer() != null && owner.getServer().isSingleplayer()
                && owner.hasPermissions(2);
    }

    public static String mine(ServerPlayer owner, CompanionEntity npc, String targetBlock) {
        if (!permitted(owner)) return "[Action blocked: world edits disabled (requires singleplayer, cheats and explicit opt-in).]";
        if (!WorldInteractionPolicy.breakable(targetBlock))
            return "[Action blocked: block is not in safe mining allowlist.]";
        ServerLevel level = owner.serverLevel();
        BlockPos center = npc.blockPosition();
        BlockPos choice = null;
        double closest = Double.POSITIVE_INFINITY;
        double reachSq = InteractionReach.squared(owner.isCreative());
        int scan = InteractionReach.MAX_SCAN_OFFSET;
        // Server checks the eye-to-block distance and line of sight. Never mine the
        // supporting block immediately under the NPC to avoid self-inflicted falls.
        for (int dx = -scan; dx <= scan; dx++) for (int dy = -1; dy <= scan; dy++)
            for (int dz = -scan; dz <= scan; dz++) {
                BlockPos p = center.offset(dx, dy, dz);
                if (p.equals(center.below()) || !level.isLoaded(p) || !WorldInteractionPolicy.breakable(
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString())) continue;
                if (!BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock())
                        .toString().equals(targetBlock) || level.getBlockEntity(p) != null) continue;
                double dist = npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(p));
                if (dist > reachSq || dist >= closest || !visible(npc, p)) continue;
                choice = p;
                closest = dist;
            }
        if (choice == null) return "[Action failed: no reachable visible matching block within range.]";
        BlockState state = level.getBlockState(choice);
        ItemStack tool = npc.getMainHandItem();
        if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state))
            return "[Action failed: equip the correct mining tool first.]";
        if (state.getDestroySpeed(level, choice) < 0 || !level.destroyBlock(choice, true, npc))
            return "[Action failed: block could not be broken.]";
        if (!tool.isEmpty() && tool.isDamageableItem())
            tool.hurtAndBreak(1, npc, entity -> entity.broadcastBreakEvent(EquipmentSlot.MAINHAND));
        // Drops stay on the ground. Use pickup_nearby as a separate verified step.
        return "[Action confirmed: mined " + targetBlock + "; drops remain on the ground.]";
    }

    private static boolean visible(CompanionEntity npc, BlockPos pos) {
        Vec3 eye = npc.getEyePosition();
        BlockHitResult hit = npc.level().clip(new ClipContext(eye, Vec3.atCenterOf(pos),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    public static String place(ServerPlayer owner, CompanionEntity npc, String targetItem) {
        if (!permitted(owner)) return "[Action blocked: world edits disabled (requires singleplayer, cheats and explicit opt-in).]";
        if (!WorldInteractionPolicy.placeable(targetItem))
            return "[Action blocked: block is not in safe placement allowlist.]";
        int slot = -1;
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            ItemStack s = npc.bag().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(targetItem)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return "[Action failed: matching block item not found in NPC bag.]";
        ItemStack stack = npc.bag().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem blockItem))
            return "[Action blocked: selected item is not a block item.]";
        ServerLevel level = owner.serverLevel();
        // Place within vanilla-like reach *along the NPC's facing direction*.
        // Because this action has no target coordinates, use the farthest safe,
        // unobstructed, supported empty position on that straight line.
        // No arbitrary positions or placements through a wall are permitted.
        BlockPos center = npc.blockPosition();
        BlockPos placePos = null;
        BlockState desired = blockItem.getBlock().defaultBlockState();
        int scan = InteractionReach.MAX_SCAN_OFFSET;
        double reachSq = InteractionReach.squared(owner.isCreative());
        for (int distance = scan; distance >= 1; distance--) {
            BlockPos candidate = center.relative(npc.getDirection(), distance);
            if (!level.isLoaded(candidate)
                    || npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate)) > reachSq
                    || !level.getBlockState(candidate).isAir()
                    || level.getBlockState(candidate.below()).isAir()
                    || !desired.canSurvive(level, candidate)
                    || !level.getEntities((net.minecraft.world.entity.Entity) null,
                            new AABB(candidate), e -> e.isAlive()).isEmpty()) continue;
            BlockHitResult hit = level.clip(new ClipContext(npc.getEyePosition(),
                    Vec3.atCenterOf(candidate), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, npc));
            if (hit.getType() != HitResult.Type.MISS) continue;
            placePos = candidate;
            break;
        }
        if (placePos == null)
            return "[Action failed: no clear supported placement spot within "
                    + InteractionReach.blocks(owner.isCreative()) + " blocks ahead.]";
        if (!level.setBlock(placePos, desired, 3))
            return "[Action failed: Minecraft rejected block placement.]";
        npc.bag().removeItem(slot, 1);
        return "[Action confirmed: placed 1 " + targetItem + " "
                + Math.round(Math.sqrt(npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(placePos))) * 10.0D) / 10.0D
                + " blocks ahead of NPC.]";
    }
}
