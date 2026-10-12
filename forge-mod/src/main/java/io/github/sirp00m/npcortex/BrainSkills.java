package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Low-level guarded skills: decision order belongs to Qwen, physics belongs here. */
public final class BrainSkills {
    private BrainSkills() {}

    public static boolean authorized(ServerPlayer owner, CompanionEntity npc) {
        return owner != null && npc != null && owner.isAlive() && npc.isAlive()
                && !npc.isRemoved() && owner.level() == npc.level()
                && owner.getUUID().equals(npc.getOwnerUUID())
                && owner.distanceToSqr(npc) <= 1024.0D
                && NPCortexConfig.WORLD_EDITS_ENABLED.get()
                && owner.getServer().isSingleplayer() && owner.hasPermissions(2);
    }

    public static boolean validBlock(ServerPlayer owner, CompanionEntity npc, BlockPos pos, String item,
            CompoundTag task) {
        if (!authorized(owner, npc) || pos == null || !owner.serverLevel().isLoaded(pos)
                || !item.equals(BuiltInRegistries.BLOCK.getKey(
                        owner.serverLevel().getBlockState(pos).getBlock()).toString())
                || !WorldInteractionPolicy.breakable(item)
                || owner.serverLevel().getBlockEntity(pos) != null
                || pos.equals(npc.blockPosition().below())) return false;
        BlockPos home = new BlockPos(task.getInt("homeX"), task.getInt("homeY"), task.getInt("homeZ"));
        return pos.distSqr(home) <= 12.0D * 12.0D;
    }

    /** Low-level physical route planner. Qwen chooses the target/skill; Java searches
     *  only feasible paths to a safe standing position with actual line of sight.
     *  Failed candidate destinations do not consume another Qwen inference.
     */
    private static String moveToReachableStand(ServerPlayer owner, CompanionEntity npc,
            BlockPos anchor, CompoundTag task, Predicate<Vec3> canInteractFrom) {
        if (!owner.serverLevel().isLoaded(anchor))
            return "[Action blocked: target unloaded.]";
        BlockPos home = new BlockPos(task.getInt("homeX"), task.getInt("homeY"), task.getInt("homeZ"));
        List<BlockPos> stands = new ArrayList<>();
        for (int dy = -3; dy <= 3; dy++) for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                BlockPos stand = anchor.offset(dx, dy, dz);
                if (stand.distSqr(home) > 12.0D * 12.0D
                        || !owner.serverLevel().isLoaded(stand)
                        || !owner.serverLevel().isLoaded(stand.above())
                        || !owner.serverLevel().isLoaded(stand.below())) continue;
                // Tall grass, flowers and other non-colliding vegetation are
                // walk-through terrain, not navigation walls. isAir() discarded
                // these positions on forest floors and slopes.
                var feet = owner.serverLevel().getBlockState(stand);
                var head = owner.serverLevel().getBlockState(stand.above());
                var support = owner.serverLevel().getBlockState(stand.below());
                if (!feet.getCollisionShape(owner.serverLevel(), stand).isEmpty()
                        || !head.getCollisionShape(owner.serverLevel(), stand.above()).isEmpty()
                        || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()
                        || !support.isFaceSturdy(owner.serverLevel(), stand.below(), Direction.UP)) continue;
                Vec3 eye = new Vec3(stand.getX() + 0.5D,
                        stand.getY() + npc.getEyeHeight(), stand.getZ() + 0.5D);
                if (canInteractFrom.test(eye)) stands.add(stand.immutable());
            }
        stands.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atCenterOf(p))));
        if (stands.isEmpty())
            return "[Action failed: no safe visible standing location for target.]";
        // Avoid expensive pathfinding over every point in a dense forest in one server tick.
        int attempted = 0;
        for (BlockPos stand : stands) {
            if (++attempted > 24) break;
            var path = npc.getNavigation().createPath(stand, 0);
            if (path == null || !path.canReach()) continue;
            if (npc.getNavigation().moveTo(path, 1.1D))
                return "[Action started: navigating to a path-verified standing position.]";
        }
        return "[Action failed: pathfinder found no traversable route to "
                + attempted + " candidate standing positions; choose another target/skill.]";
    }

    public static String approach(ServerPlayer owner, CompanionEntity npc,
            BlockPos target, CompoundTag task) {
        if (target == null || !owner.serverLevel().isLoaded(target))
            return "[Action blocked: target unloaded.]";
        double reachSq = InteractionReach.squared(owner.isCreative());
        if (npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(target)) <= reachSq
                && WorldBlockActions.visible(npc, target))
            return "[Action unchanged: block already visible and in reach; no movement or task progress.]";
        return moveToReachableStand(owner, npc, target, task,
                eye -> eye.distanceToSqr(Vec3.atCenterOf(target)) <= reachSq
                        && WorldBlockActions.visibleFrom(npc, eye, target));
    }

    public static String approachDrop(ServerPlayer owner, CompanionEntity npc,
            ItemEntity drop, CompoundTag task) {
        if (drop == null || !drop.isAlive())
            return "[Action failed: selected dropped item is unavailable.]";
        if (WorldActions.reachableGatherDrop(npc, drop, owner.isCreative()))
            return "[Action unchanged: dropped item already reachable; no movement or task progress.]";
        Vec3 dropCenter = drop.getBoundingBox().getCenter();
        return moveToReachableStand(owner, npc, drop.blockPosition(), task, eye ->
                AgenticTaskPolicy.pickupNear(
                    eye.subtract(0.0D, npc.getEyeHeight(), 0.0D).distanceToSqr(drop.position()))
                && owner.serverLevel().clip(new ClipContext(eye, dropCenter,
                      ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, npc))
                      .getType() == HitResult.Type.MISS);
    }

    public static String mine(ServerPlayer owner, CompanionEntity npc, BlockPos pos, String item) {
        return WorldBlockActions.mineAt(owner, npc, item, pos);
    }

    public static String clearLeaf(ServerPlayer owner, CompanionEntity npc, BlockPos log) {
        BlockPos leaf = WorldBlockActions.blockingNaturalOakLeaf(npc, log);
        if (leaf == null) return "[Action failed: no verified natural occluding leaf.]";
        return WorldBlockActions.clearNaturalOakLeaf(owner, npc, leaf, log);
    }
}
