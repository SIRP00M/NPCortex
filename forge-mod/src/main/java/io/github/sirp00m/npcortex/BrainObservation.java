package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/** Bounded ephemeral target refs; model never supplies positions to an executor. */
public final class BrainObservation {
    private BrainObservation() {}

    public static JsonObject capture(ServerPlayer owner, CompanionEntity npc, String item) {
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
            blocks.add(obj);
        }
        root.add("targets", blocks);
        List<ItemEntity> found = owner.serverLevel().getEntitiesOfClass(ItemEntity.class,
                npc.getBoundingBox().inflate(12.0D), drop -> drop.isAlive()
                    && !drop.getItem().isEmpty() && BuiltInRegistries.ITEM.getKey(
                            drop.getItem().getItem()).toString().equals(item));
        found.sort(Comparator.comparingDouble(npc::distanceToSqr));
        for (int i = 0; i < Math.min(16, found.size()); i++) {
            JsonObject obj = new JsonObject();
            obj.addProperty("ref", "d" + i);
            obj.addProperty("id", item);
            obj.addProperty("uuid", found.get(i).getUUID().toString());
            obj.addProperty("distance", Math.round(Math.sqrt(npc.distanceToSqr(found.get(i))) * 10.0D) / 10.0D);
            obj.addProperty("pickup_reachable", WorldActions.reachableGatherDrop(
                    npc, found.get(i), owner.isCreative()));
            drops.add(obj);
        }
        root.add("drops", drops);
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
            scaffoldMaterial |= ScaffoldPolicy.validMaterial(id);
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
