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
        return TaskWorldBounds.allows(owner,pos)
                && !TreeScaffold.ownsPlacedBlock(task,pos)
                && !Navigation3DAccess.ownsPlacedBlock(task,pos);
    }

    /** Low-level physical route planner. Qwen chooses the target/skill; Java searches
     *  only feasible paths to a safe standing position with actual line of sight.
     *  Failed candidate destinations do not consume another Qwen inference.
     */
    /** Probe geometric standing affordances with the SAME checks the navigation
     *  executor uses. This does not claim a path exists: vanilla Pathfinder must
     *  still verify the route at execution time. No world mutation or chunk loads.
     */
    private static List<BlockPos> safeVisibleStands(ServerPlayer owner, CompanionEntity npc,
            BlockPos anchor, CompoundTag task, Predicate<Vec3> canInteractFrom, boolean firstOnly) {
        List<BlockPos> stands = new ArrayList<>();
        if (!owner.serverLevel().isLoaded(anchor)) return stands;
        BlockPos home = new BlockPos(task.getInt("homeX"), task.getInt("homeY"), task.getInt("homeZ"));
        for (int dy = -3; dy <= 3; dy++) for (int dx = -5; dx <= 5; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                BlockPos stand = anchor.offset(dx, dy, dz);
                if (!TaskWorldBounds.allows(owner,stand)
                        || !TaskWorldBounds.allows(owner,stand.above())
                        || !TaskWorldBounds.allows(owner,stand.below())) continue;
                // Non-colliding plants are traversable, water and obstructing
                // solid blocks are not. Require a full support face below.
                var feet = owner.serverLevel().getBlockState(stand);
                var head = owner.serverLevel().getBlockState(stand.above());
                var support = owner.serverLevel().getBlockState(stand.below());
                if (!feet.getCollisionShape(owner.serverLevel(), stand).isEmpty()
                        || !head.getCollisionShape(owner.serverLevel(), stand.above()).isEmpty()
                        || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()
                        || !support.isFaceSturdy(owner.serverLevel(), stand.below(), Direction.UP)) continue;
                Vec3 eye = new Vec3(stand.getX() + 0.5D,
                        stand.getY() + npc.getEyeHeight(), stand.getZ() + 0.5D);
                if (canInteractFrom.test(eye)) {
                    stands.add(stand.immutable());
                    if (firstOnly) return stands; // Observation needs existence, not all positions.
                }
            }
        return stands;
    }

    /** True iff a grounded, collision-safe, in-range and visible standing point
     *  exists for the current target. Does NOT predict Pathfinder route success.
     *  Allows the AI to avoid pointless ground approaches to floating branches.
     */
    public static boolean groundStandPossible(ServerPlayer owner, CompanionEntity npc,
            BlockPos target, CompoundTag task) {
        if (target == null || !owner.serverLevel().isLoaded(target)) return false;
        double reachSq = InteractionReach.squared(owner.isCreative());
        return !safeVisibleStands(owner, npc, target, task,
                eye -> eye.distanceToSqr(Vec3.atCenterOf(target)) <= reachSq
                        && WorldBlockActions.visibleFrom(npc, eye, target), true).isEmpty();
    }

    private static String moveToReachableStand(ServerPlayer owner, CompanionEntity npc,
            BlockPos anchor, CompoundTag task, Predicate<Vec3> canInteractFrom) {
        if (!owner.serverLevel().isLoaded(anchor))
            return "[Action blocked: target unloaded.]";
        List<BlockPos> stands = safeVisibleStands(owner, npc, anchor, task, canInteractFrom, false);
        stands.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atCenterOf(p))));
        if (stands.isEmpty())
            return "[Action failed: no collision-safe ground standing point with reach and line of sight; consider scaffold or another target.]";
        // Bound pathfinder work to 24 candidate destinations per skill call.
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

    /** Approach an observed material node closely enough that the mined item
     * will enter the NPC's actual physical pickup range (not just block reach). */
    public static String approachMaterial(ServerPlayer owner, CompanionEntity npc,
            BlockPos target, CompoundTag task) {
        if(target==null || !owner.serverLevel().isLoaded(target)) return "[Action blocked: material target unloaded.]";
        if(MaterialSource.reachable(owner,npc,target))
            return "[Action unchanged: already within material mining/pickup range.]";
        return moveToReachableStand(owner,npc,target,task,
                eye -> MaterialSource.reachableFrom(owner,npc,eye,target));
    }

    public static boolean materialStandPossible(ServerPlayer owner, CompanionEntity npc,
            BlockPos target, CompoundTag task) {
        return target!=null && !safeVisibleStands(owner,npc,target,task,
                eye -> MaterialSource.reachableFrom(owner,npc,eye,target),true).isEmpty();
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

    /** One high-level AI decision can clear a SMALL bounded cluster of verified
     *  leaf obstructions. Every leaf is raycast and re-authorized individually.
     *  Never prune unrelated foliage, never remove all leaves around a tree.
     */
    public static String clearLeaf(ServerPlayer owner, CompanionEntity npc, BlockPos log) {
        int removed = 0;
        if (npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(log))
                <= InteractionReach.squared(owner.isCreative())
                && WorldBlockActions.visible(npc, log))
            return "[Action unchanged: target log already visible and reachable; no leaf clearing needed.]";
        String last = "[Action failed: no verified natural occluding leaf on current sightline.]";
        for (int attempt = 0; attempt < 3; attempt++) {
            // As soon as the log is physically accessible, the skill has done its job.
            if (npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(log))
                    <= InteractionReach.squared(owner.isCreative())
                    && WorldBlockActions.visible(npc, log)) break;
            BlockPos leaf = WorldBlockActions.blockingNaturalOakLeaf(npc, log, owner.isCreative());
            if (leaf == null) break;
            last = WorldBlockActions.clearNaturalOakLeaf(owner, npc, leaf, log);
            if (!last.startsWith("[Action confirmed:")) break;
            removed++;
        }
        return removed > 0
                ? "[Action confirmed: cleared " + removed + " individually verified occluding leaves.]"
                : last;
    }
}
