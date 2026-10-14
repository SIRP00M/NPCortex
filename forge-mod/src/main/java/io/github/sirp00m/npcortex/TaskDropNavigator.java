package io.github.sirp00m.npcortex;

import io.github.sirp00m.npcortex.agent.GoalState;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Ground-level retrieval of drops made by this NPC during this exact task.
 * The model decides to mine; this server-owned motor reflex moves into vanilla-like
 * pickup range. It does not teleport items, mine new blocks or place supports.
 * Unreachable drops are deferred so tree navigation can continue.
 */
final class TaskDropNavigator {
    private static final double SEARCH_SQ = 12.0D * 12.0D;
    // The body-level pickup reflex attracts an item within 2.25 blocks before
    // collecting at 1.5. A safe stand does not need to overlap the item itself.
    // Keep a small margin so arrival at the block center still triggers attraction.
    private static final double ATTRACT_SQ = 2.1D * 2.1D;
    private static final int MAX_CANDIDATES = 32;
    private static final int MAX_TRIES = 4;
    private static final long MAX_NAV_TICKS = 120;
    private static final long RETRY_DELAY = 200;
    private TaskDropNavigator() {}

    static int nearbyTracked(CompanionEntity npc, CompoundTag task) {
        return tracked(npc, task).size();
    }

    private static List<ItemEntity> tracked(CompanionEntity npc, CompoundTag task) {
        UUID taskId = task.hasUUID("taskId") ? task.getUUID("taskId") : null;
        if (taskId == null) return List.of();
        List<ItemEntity> drops = npc.level().getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(12.0D, 12.0D, 12.0D), item ->
                item.isAlive() && !item.getItem().isEmpty()
                && !CompanionItemPickup.isDeliberatelyDroppedBy(npc, item)
                && CompanionMinedDrops.fromTask(npc, taskId, item)
                && task.getString("item").equals(BuiltInRegistries.ITEM.getKey(
                        item.getItem().getItem()).toString())
                && npc.distanceToSqr(item) <= SEARCH_SQ);
        drops.sort(Comparator.comparingDouble(npc::distanceToSqr));
        return drops;
    }

    private static boolean safeStand(ServerPlayer owner, CompanionEntity npc,
            CompoundTag task, ItemEntity item, BlockPos stand) {
        var world = owner.serverLevel();
        BlockPos home = new BlockPos(task.getInt("homeX"), task.getInt("homeY"), task.getInt("homeZ"));
        if (!TaskWorldBounds.allows(owner,stand) || !TaskWorldBounds.allows(owner,stand.above())
                || !TaskWorldBounds.allows(owner,stand.below()))
            return false;
        var feet = world.getBlockState(stand);
        var head = world.getBlockState(stand.above());
        var floor = world.getBlockState(stand.below());
        if (!feet.getCollisionShape(world,stand).isEmpty()
                || !head.getCollisionShape(world,stand.above()).isEmpty()
                || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()
                || !floor.isFaceSturdy(world,stand.below(),Direction.UP)
                || !floor.getFluidState().isEmpty()) return false;
        Vec3 body = new Vec3(stand.getX()+0.5D,
                stand.getY()+npc.getBbHeight()*0.45D,stand.getZ()+0.5D);
        Vec3 target = item.getBoundingBox().getCenter();
        if (body.distanceToSqr(target) > ATTRACT_SQ) return false;
        // Match CompanionItemPickup's actual line-of-sight origin.
        Vec3 eye = new Vec3(stand.getX()+0.5D,
                stand.getY()+npc.getEyeHeight(),stand.getZ()+0.5D);
        return world.clip(new ClipContext(eye,target,ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,npc)).getType() == HitResult.Type.MISS;
    }

    private static List<BlockPos> candidates(ServerPlayer owner, CompanionEntity npc,
            CompoundTag task, ItemEntity item) {
        List<BlockPos> stands = new ArrayList<>();
        BlockPos anchor = item.blockPosition();
        for (int dy=-2;dy<=2;dy++) for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++) {
            BlockPos stand = anchor.offset(dx,dy,dz);
            if (safeStand(owner,npc,task,item,stand)) stands.add(stand.immutable());
        }
        stands.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atBottomCenterOf(p))));
        return stands;
    }

    private static boolean findRoute(ServerPlayer owner, CompanionEntity npc,
            CompoundTag task, ItemEntity item, int skip) {
        List<BlockPos> stands = candidates(owner,npc,task,item);
        int examined = 0;
        for (BlockPos stand : stands) {
            if (examined++ < skip) continue;
            if (examined > MAX_CANDIDATES) break;
            var path = npc.getNavigation().createPath(stand,0);
            if (path == null || !path.canReach()) continue;
            if (npc.getNavigation().moveTo(path,1.0D)) {
                task.putLong("lootStand",stand.asLong());
                return true;
            }
        }
        return false;
    }

    private static void defer(CompanionEntity npc, CompoundTag task, UUID uuid, String message) {
        npc.getNavigation().stop();
        if (uuid == null) {
            task.remove("lootTarget");
            task.putString("state", "ready");
            task.putString("lastResult", "[Loot interrupted: missing target identity.]");
            AgentRuntime.event(npc,task,"loot_deferred","missing target UUID");
            return;
        }
        CompoundTag until = task.getCompound("lootDeferUntil");
        until.putLong(uuid.toString(),npc.level().getGameTime()+RETRY_DELAY);
        task.put("lootDeferUntil",until);
        task.remove("lootTarget");
        task.putString("state","ready");
        task.putLong("nextAt",npc.level().getGameTime()+10);
        task.putString("lastResult",message);
        if (GoalState.EXECUTING.name().equals(task.getString("goalState"))) {
            AgentRuntime.transition(npc,task,GoalState.VERIFYING);
            AgentRuntime.event(npc,task,"loot_deferred",message);
            AgentRuntime.transition(npc,task,GoalState.READY);
        } else {
            AgentRuntime.event(npc,task,"loot_deferred",message);
        }
    }

    static boolean startIfAny(ServerPlayer owner, CompanionEntity npc, CompoundTag task) {
        if (!BrainSkills.authorized(owner,npc) || !npc.onGround()
                || TreeScaffold.size(task)>0 || Navigation3DAccess.needsCleanup(task)
                || !terrainRestored(task)) return false;
        var deferred = task.getCompound("lootDeferUntil");
        for (ItemEntity item : tracked(npc,task)) {
            if (deferred.getLong(item.getUUID().toString()) > npc.level().getGameTime()) continue;
            if (!CompanionItemPickup.canStore(npc,item.getItem())) {
                // No walking toward items that cannot enter the bag.
                continue;
            }
            task.putUUID("lootTarget",item.getUUID());
            task.putInt("lootBefore",AgenticTaskController.collected(npc,task));
            task.putInt("lootStackBefore",item.getItem().getCount());
            task.putInt("lootTries",0);
            task.putLong("lootStart",npc.level().getGameTime());
            if (!WorldActions.reachableGatherDrop(npc,item,owner.isCreative())
                    && !findRoute(owner,npc,task,item,0)) {
                defer(npc,task,item.getUUID(),"[Loot deferred: no reachable grounded stand near NPC-mined drop.]");
                continue;
            }
            task.putString("state","loot_navigate");
            task.putString("lastResult","[Loot: walking to collect own mined drop " + item.getUUID() + ".]");
            AgentRuntime.event(npc,task,"loot_started",item.getUUID().toString());
            return true;
        }
        return false;
    }

    private static boolean terrainRestored(CompoundTag task) {
        return WorldTerrainEdits.size(task)==0;
    }

    static void tick(ServerPlayer owner, CompanionEntity npc, CompoundTag task) {
        UUID uuid = task.hasUUID("lootTarget") ? task.getUUID("lootTarget") : null;
        var entity = uuid==null ? null : owner.serverLevel().getEntity(uuid);
        ItemEntity item = entity instanceof ItemEntity found ? found : null;
        boolean gained = AgenticTaskController.collected(npc,task)>task.getInt("lootBefore");
        // The bag delta alone can come from a different nearby stack. Verify that
        // the tracked entity was consumed (or shrank) before declaring its pickup.
        boolean sourceConsumed = item == null || !item.isAlive()
                || item.getItem().getCount() < task.getInt("lootStackBefore");
        if (gained && sourceConsumed) {
            npc.getNavigation().stop();
            task.remove("lootTarget");
            task.putString("state","ready");
            task.putInt("stalls",0);
            task.putLong("nextAt",npc.level().getGameTime()+2);
            task.putString("lastResult","[Loot confirmed: NPC-mined item physically picked up into bag.]");
            AgentRuntime.transition(npc,task,GoalState.VERIFYING);
            AgentRuntime.event(npc,task,"loot_collected","own mined item entered NPC bag");
            AgentRuntime.transition(npc,task,GoalState.READY);
            return;
        }
        if (item==null || !item.isAlive() || !CompanionMinedDrops.fromTask(npc,task.getUUID("taskId"),item)) {
            defer(npc,task,uuid,"[Loot deferred: tracked item vanished or ownership changed before pickup.]");
            return;
        }
        if (!CompanionItemPickup.canStore(npc,item.getItem())) {
            defer(npc,task,uuid,"[Loot deferred: NPC inventory is full.]");
            return;
        }
        long elapsed=npc.level().getGameTime()-task.getLong("lootStart");
        if (elapsed<12 && WorldActions.reachableGatherDrop(npc,item,owner.isCreative())) return;
        if (elapsed>MAX_NAV_TICKS || (elapsed>10 && npc.getNavigation().isDone()
                && !WorldActions.reachableGatherDrop(npc,item,owner.isCreative()))) {
            int attempts=task.getInt("lootTries")+1;
            task.putInt("lootTries",attempts);
            if (attempts<MAX_TRIES && findRoute(owner,npc,task,item,attempts)) {
                task.putLong("lootStart",npc.level().getGameTime());
                AgentRuntime.event(npc,task,"loot_repath","attempt " + attempts);
            } else {
                defer(npc,task,uuid,"[Loot deferred: no safe path into physical pickup range; will retry later.]");
            }
        }
    }
}
