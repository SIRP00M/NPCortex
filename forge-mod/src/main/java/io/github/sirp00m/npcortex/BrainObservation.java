package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Bounded ephemeral target refs; model never supplies positions to an executor. */
public final class BrainObservation {
    private BrainObservation() {}

    public static JsonObject capture(ServerPlayer owner, CompanionEntity npc, String item,
            CompoundTag task) {
        JsonObject root = new JsonObject();
        JsonArray blocks = new JsonArray();
        JsonArray drops = new JsonArray();
        BlockPos center = npc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        // Loaded blocks only, task resource only. No chunk loading, no creative flight.
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++)
            for (int dy = -2; dy <= 9; dy++) {
                BlockPos target = center.offset(dx, dy, dz);
                if (!owner.serverLevel().isLoaded(target) || target.equals(center.below())) continue;
                if (owner.serverLevel().getBlockEntity(target) != null) continue;
                String id = BuiltInRegistries.BLOCK.getKey(
                        owner.serverLevel().getBlockState(target).getBlock()).toString();
                if (id.equals(item) && WorldInteractionPolicy.breakable(id))
                    candidates.add(target.immutable());
            }
        // A committed tree is an immutable server-verified set of logs captured
        // before the first block was mined. Never offer other trees until it is done.
        boolean committed = WholeTreeTracker.isCommitted(task);
        if (committed) {
            candidates.clear();
            candidates.addAll(WholeTreeTracker.remaining(owner.serverLevel(), task));
            // Committed high/branch logs remain observable even beyond local scan.
            // Never load a chunk or provide Qwen with absolute coordinates.
        } else if (!WholeTreePolicy.canStartTree(task.getInt("completedTrees"),
                task.getInt("mined"), Math.max(0, countInBag(npc, item) - task.getInt("initial")),
                task.getInt("goal"))) {
            candidates.clear();
        }
        candidates.removeIf(p -> !owner.serverLevel().isLoaded(p));
        candidates.sort(Comparator.comparingDouble(p -> npc.distanceToSqr(Vec3.atCenterOf(p))));
        for (int i = 0; i < Math.min(16, candidates.size()); i++) {
            BlockPos p = candidates.get(i);
            JsonObject obj = new JsonObject();
            obj.addProperty("ref", "b" + i);
            obj.addProperty("id", item);
            obj.addProperty("dx", p.getX() - center.getX());
            obj.addProperty("dy", p.getY() - center.getY());
            obj.addProperty("dz", p.getZ() - center.getZ());
            boolean inReach = npc.getEyePosition().distanceToSqr(Vec3.atCenterOf(p))
                    <= InteractionReach.squared(owner.isCreative());
            obj.addProperty("reachable", inReach);
            obj.addProperty("visible", inReach && WorldBlockActions.visible(npc, p));
            // Physical 3D access affordance, calculated by the same bounded
            // standing-point rules used by approach(). Never claim path success.
            obj.addProperty("ground_stand_possible",
                    BrainSkills.groundStandPossible(owner, npc, p, task));
            // Qwen sees only classified physical affordances. Never send leaf
            // positions or imply a leaf may be broken without server validation.
            var obstruction = WorldBlockActions.oakObstruction(npc, p, owner.isCreative());
            obj.addProperty("obstruction", obstruction.kind());
            obj.addProperty("leaf_clearable", obstruction.leafClearable());
            obj.addProperty("soft_clearable", committed &&
                    WorldTerrainEdits.eligible(owner, npc, p, task) != null);
            blocks.add(obj);
        }
        root.add("targets", blocks);
        JsonArray resources = new JsonArray();
        if (BuildMaterials.available(npc,task)<32) {
            List<BlockPos> soil = new ArrayList<>();
            for (int dx=-10;dx<=10;dx++) for (int dz=-10;dz<=10;dz++) for (int dy=-3;dy<=3;dy++) {
                BlockPos candidate=center.offset(dx,dy,dz);
                if (MaterialSource.eligible(owner,npc,task,candidate)) soil.add(candidate.immutable());
            }
            soil.sort(Comparator.comparingDouble(p -> (MaterialSource.reachable(owner,npc,p)?0:1000) + npc.distanceToSqr(Vec3.atCenterOf(p))));
            for (int i=0;i<Math.min(6,soil.size());i++) {
                BlockPos candidate=soil.get(i);
                JsonObject obj=new JsonObject();
                obj.addProperty("ref","m"+i);
                obj.addProperty("id",BuiltInRegistries.BLOCK.getKey(owner.serverLevel().getBlockState(candidate).getBlock()).toString());
                obj.addProperty("dx",candidate.getX()-center.getX());
                obj.addProperty("dy",candidate.getY()-center.getY());
                obj.addProperty("dz",candidate.getZ()-center.getZ());
                obj.addProperty("reachable",MaterialSource.reachable(owner,npc,candidate));
                obj.addProperty("ground_stand_possible",BrainSkills.materialStandPossible(owner,npc,candidate,task));
                resources.add(obj);
            }
        }
        root.add("material_targets",resources);
        root.add("frontiers",ExplorationSkills.observe(owner,npc,task));
        JsonObject access=new JsonObject();
        access.addProperty("temporary_blocks",Navigation3DAccess.size(task)+TreeScaffold.size(task));
        access.addProperty("borrowed_goal_blocks",Navigation3DAccess.borrowed(task,item));
        access.addProperty("last_search",task.getString("accessSearch"));
        access.addProperty("explored_regions",task.getInt("exploredSegments"));
        root.add("access",access);
        root.add("task_context",TaskContext.observe(npc,task));
        List<ItemEntity> found = owner.serverLevel().getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(32.0D), drop -> drop.isAlive()
                    && !CompanionItemPickup.isDeliberatelyDroppedBy(npc, drop)
                    && !drop.getItem().isEmpty() && BuiltInRegistries.ITEM.getKey(
                            drop.getItem().getItem()).toString().equals(item));
        if(committed) {
            BlockPos rootPos=new BlockPos(task.getInt("wholeTreeRootX"),task.getInt("wholeTreeRootY"),task.getInt("wholeTreeRootZ"));
            if(TaskWorldBounds.allows(owner,rootPos)) for(ItemEntity drop:owner.serverLevel().getEntitiesOfClass(
                    ItemEntity.class,new net.minecraft.world.phys.AABB(rootPos).inflate(32.0D),
                    d -> d.isAlive() && !d.getItem().isEmpty() && item.equals(BuiltInRegistries.ITEM.getKey(d.getItem().getItem()).toString())
                         && CompanionMinedDrops.fromTask(npc,task.hasUUID("taskId")?task.getUUID("taskId"):null,d)))
                if(!found.contains(drop)) found.add(drop);
        }
        found.sort(Comparator.comparingDouble(npc::distanceToSqr));
        for (int i = 0; i < Math.min(16, found.size()); i++) {
            JsonObject obj = new JsonObject();
            obj.addProperty("ref", "d" + i);
            obj.addProperty("id", item);
            obj.addProperty("uuid", found.get(i).getUUID().toString());
            obj.addProperty("npc_mined", CompanionMinedDrops.fromTask(npc,
                    task.hasUUID("taskId") ? task.getUUID("taskId") : null, found.get(i)));
            obj.addProperty("distance", Math.round(Math.sqrt(npc.distanceToSqr(found.get(i))) * 10.0D) / 10.0D);
            // Relative elevation is physical perception, not a Qwen-authorized
            // coordinate. Without it an NPC on a bridge cannot distinguish
            // ground loot below from loot on the same level.
            obj.addProperty("dy", found.get(i).blockPosition().getY() - center.getY());
            obj.addProperty("pickup_reachable", WorldActions.reachableGatherDrop(
                    npc, found.get(i), owner.isCreative()));
            drops.add(obj);
        }
        root.add("drops", drops);
        JsonObject tree = new JsonObject();
        tree.addProperty("active", committed);
        tree.addProperty("remaining", WholeTreeTracker.pending(owner.serverLevel(), task));
        tree.addProperty("size", task.getInt("committedTreeSize"));
        tree.addProperty("completed", task.getInt("completedTrees"));
        tree.addProperty("terrain_edits", WorldTerrainEdits.size(task));
        root.add("tree", tree);
        // Compact perception: tools/materials in NPC bag and local obstruction.
        // This is observation data, NEVER an instruction or an editable model variable.
        JsonObject equipment = new JsonObject();
        equipment.addProperty("holding", BuiltInRegistries.ITEM.getKey(
                npc.getMainHandItem().getItem()).toString());
        JsonArray bag = new JsonArray();
        boolean scaffoldMaterial = false;
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            var stack = npc.bag().getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            JsonObject slot = new JsonObject();
            slot.addProperty("id", id);
            slot.addProperty("count", stack.getCount());
            bag.add(slot);
            scaffoldMaterial |= BuildMaterials.usable(task,stack);
        }
        equipment.add("bag", bag);
        equipment.addProperty("can_scaffold", scaffoldMaterial);
        root.add("equipment", equipment);
        JsonObject vicinity = new JsonObject();
        vicinity.addProperty("below", BuiltInRegistries.BLOCK.getKey(
                owner.serverLevel().getBlockState(center.below()).getBlock()).toString());
        vicinity.addProperty("feet", BuiltInRegistries.BLOCK.getKey(
                owner.serverLevel().getBlockState(center).getBlock()).toString());
        vicinity.addProperty("ahead", BuiltInRegistries.BLOCK.getKey(
                owner.serverLevel().getBlockState(center.relative(npc.getDirection())).getBlock()).toString());
        root.add("surroundings", vicinity);
        root.addProperty("owner_distance", Math.round(Math.sqrt(owner.distanceToSqr(npc)) * 10.0D) / 10.0D);
        return root;
    }

    private static int countInBag(CompanionEntity npc, String item) {
        int sum = 0;
        for (int i = 0; i < npc.bag().getContainerSize(); i++) {
            var stack = npc.bag().getItem(i);
            if (!stack.isEmpty() && item.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()))
                sum += stack.getCount();
        }
        return sum;
    }

    /** Ref resolves against the EXACT frozen observation used for the model call. */
    public static BlockPos resolveBlock(JsonObject observation, String ref, BlockPos origin, String item) {
        if (ref == null || !ref.matches("b(?:[0-9]|1[0-5])")) return null;
        JsonArray array = observation.getAsJsonArray("targets");
        int index = Integer.parseInt(ref.substring(1));
        if (array == null || index >= array.size()) return null;
        JsonObject entry = array.get(index).getAsJsonObject();
        if (!ref.equals(entry.get("ref").getAsString()) || !item.equals(entry.get("id").getAsString()))
            return null;
        return origin.offset(entry.get("dx").getAsInt(), entry.get("dy").getAsInt(),
                entry.get("dz").getAsInt());
    }

    /** Resolve only frozen material refs; executor must recheck soil identity/safety. */
    public static BlockPos resolveMaterial(JsonObject observation,String ref,BlockPos origin) {
        if(ref==null || !ref.matches("m[0-5]") || observation==null || !observation.has("material_targets")) return null;
        JsonArray arr=observation.getAsJsonArray("material_targets");
        int index=Integer.parseInt(ref.substring(1));
        if(index>=arr.size()) return null;
        JsonObject row=arr.get(index).getAsJsonObject();
        if(!ref.equals(row.get("ref").getAsString())) return null;
        return origin.offset(row.get("dx").getAsInt(),row.get("dy").getAsInt(),row.get("dz").getAsInt());
    }

    public static ItemEntity resolveDrop(ServerPlayer owner, JsonObject snapshot,
            String ref, String item) {
        if (ref == null || !ref.matches("d(?:[0-9]|1[0-5])")) return null;
        int index = Integer.parseInt(ref.substring(1));
        JsonArray arr = snapshot.getAsJsonArray("drops");
        if (arr == null || index >= arr.size()) return null;
        JsonObject entry = arr.get(index).getAsJsonObject();
        if (!ref.equals(entry.get("ref").getAsString()) || !item.equals(entry.get("id").getAsString()))
            return null;
        try {
            var entity = owner.serverLevel().getEntity(java.util.UUID.fromString(entry.get("uuid").getAsString()));
            if (entity instanceof ItemEntity drop && drop.isAlive() && !drop.getItem().isEmpty()
                    && item.equals(BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString()))
                return drop;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
