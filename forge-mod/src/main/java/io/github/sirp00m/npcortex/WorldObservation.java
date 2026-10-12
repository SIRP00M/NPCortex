package io.github.sirp00m.npcortex;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.sirp00m.npcortex.entity.CompanionEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/** Snapshot computed on server game thread. Bounded; no free-form player names. */
public final class WorldObservation {
    private WorldObservation() {}

    public static JsonObject capture(ServerPlayer player, CompanionEntity npc) {
        ServerLevel world = player.serverLevel();
        BlockPos pos = npc.blockPosition();
        JsonObject root = new JsonObject();
        root.addProperty("dimension", world.dimension().location().toString());
        root.addProperty("biome", world.getBiome(pos).unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown"));
        long dayTime = world.getDayTime() % 24000L;
        root.addProperty("time", dayTime < 13000L ? "day" : "night");
        root.addProperty("weather", world.isThundering() ? "thunder"
                : world.isRainingAt(pos) ? "rain" : "clear");

        JsonObject playerData = new JsonObject();
        playerData.addProperty("distance", rounded(Math.sqrt(player.distanceToSqr(npc))));
        playerData.addProperty("health", rounded(player.getHealth()));
        playerData.addProperty("holding", BuiltInRegistries.ITEM.getKey(
                player.getMainHandItem().getItem()).toString());
        root.add("player", playerData);

        JsonObject npcData = new JsonObject();
        npcData.addProperty("following", npc.isFollowing());
        npcData.addProperty("health", rounded(npc.getHealth()));
        npcData.addProperty("holding", BuiltInRegistries.ITEM.getKey(npc.getMainHandItem().getItem()).toString());
        npcData.addProperty("stored_items", npc.storedSlots());
        // Actual item IDs and counts are essential. Slot count alone cannot tell the LLM
        // whether the NPC owns an emerald, a tool, or something else.
        JsonArray bagItems = new JsonArray();
        for (int slot = 0; slot < npc.bag().getContainerSize(); ++slot) {
            ItemStack stack = npc.bag().getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            entry.addProperty("count", stack.getCount());
            bagItems.add(entry);
        }
        npcData.add("inventory", bagItems);
        npcData.addProperty("holding_count", npc.getMainHandItem().getCount());
        root.add("npc", npcData);

        double reach = InteractionReach.blocks(player.isCreative());
        double reachSq = InteractionReach.squared(player.isCreative());
        // Visible item entities are independent of the NPC bag: thrown items are NOT
        // automatically owned and must first be collected using pickup_nearby.
        JsonArray groundItems = new JsonArray();
        world.getEntitiesOfClass(ItemEntity.class, npc.getBoundingBox().inflate(reach),
                item -> item.isAlive() && !item.getItem().isEmpty() && npc.distanceToSqr(item) <= reachSq
                        && world.clip(new ClipContext(npc.getEyePosition(),
                                item.getBoundingBox().getCenter(), ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE, npc)).getType() == HitResult.Type.MISS)
                .stream().sorted(Comparator.comparingDouble(npc::distanceToSqr))
                .limit(6).forEach(item -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
                    entry.addProperty("count", item.getItem().getCount());
                    entry.addProperty("distance", rounded(Math.sqrt(npc.distanceToSqr(item))));
                    groundItems.add(entry);
                });
        root.add("ground_items", groundItems);

        JsonObject blocks = new JsonObject();
        blocks.addProperty("below", blockName(world.getBlockState(pos.below())));
        blocks.addProperty("feet", blockName(world.getBlockState(pos)));
        blocks.addProperty("ahead", blockName(world.getBlockState(pos.relative(npc.getDirection()))));
        root.add("blocks", blocks);
        // The model may refer only to IDs observed here; the executor re-checks
        // physical reachability and visibility before editing anything.
        java.util.Set<String> visibleBlocks = new java.util.LinkedHashSet<>();
        int scan = InteractionReach.MAX_SCAN_OFFSET;
        for (int dx = -scan; dx <= scan; dx++) for (int dy = -1; dy <= scan; dy++)
            for (int dz = -scan; dz <= scan; dz++) {
                BlockPos near = pos.offset(dx, dy, dz);
                if (near.equals(pos.below()) || !world.isLoaded(near)
                        || !InteractionReach.within(npc.getEyePosition().distanceToSqr(
                                net.minecraft.world.phys.Vec3.atCenterOf(near)), player.isCreative())) continue;
                String id = blockName(world.getBlockState(near));
                if (WorldInteractionPolicy.breakable(id)) visibleBlocks.add(id);
            }
        JsonArray mineable = new JsonArray();
        visibleBlocks.stream().limit(12).forEach(mineable::add);
        root.add("mineable_blocks", mineable);
        JsonArray placeable = new JsonArray();
        WorldInteractionPolicy.placeableIds().stream().sorted().forEach(placeable::add);
        root.add("allowed_placeable_blocks", placeable);
        JsonArray recipes = new JsonArray();
        WorldInteractionPolicy.recipeOutputs().stream().sorted().forEach(recipes::add);
        root.add("supported_recipes", recipes);

        JsonArray nearby = new JsonArray();
        List<LivingEntity> entities = world.getEntitiesOfClass(LivingEntity.class,
                npc.getBoundingBox().inflate(12.0D), entity -> entity != npc && entity.isAlive());
        entities.stream().sorted(Comparator.comparingDouble(npc::distanceToSqr))
                .limit(12).forEach(entity -> {
                    JsonObject sighting = new JsonObject();
                    String kind = entity instanceof Monster ? "hostile"
                            : entity instanceof Animal ? "animal"
                            : entity instanceof Player ? "player" : "other";
                    sighting.addProperty("kind", kind);
                    sighting.addProperty("type", BuiltInRegistries.ENTITY_TYPE
                            .getKey(entity.getType()).toString());
                    sighting.addProperty("distance", rounded(Math.sqrt(npc.distanceToSqr(entity))));
                    nearby.add(sighting);
                });
        root.add("nearby", nearby);
        return root;
    }

    private static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static double rounded(double n) { return Math.round(n * 10.0D) / 10.0D; }

    /** Human-readable debug text; does not ask Ollama for a response. */
    public static String describe(JsonObject snapshot) {
        String time = snapshot.get("time").getAsString();
        String weather = snapshot.get("weather").getAsString();
        int count = snapshot.getAsJsonArray("nearby").size();
        StringBuilder text = new StringBuilder("World: ").append(time).append(", ")
                .append(weather).append(", biome=").append(snapshot.get("biome").getAsString())
                .append(" | nearby (12 blocks): ").append(count);
        JsonArray bag = snapshot.getAsJsonObject("npc").getAsJsonArray("inventory");
        JsonObject npcData = snapshot.getAsJsonObject("npc");
        text.append(" | NPC hand:").append(npcData.get("holding").getAsString());
        text.append(" | following:").append(npcData.get("following").getAsBoolean());
        text.append(" | NPC bag:");
        if (bag.size() == 0) text.append(" empty");
        for (int i = 0; i < bag.size(); ++i) {
            JsonObject entry = bag.get(i).getAsJsonObject();
            text.append(" ").append(entry.get("id").getAsString())
                .append(" x").append(entry.get("count").getAsInt());
        }
        JsonArray groundItems = snapshot.getAsJsonArray("ground_items");
        text.append(" | ground:").append(groundItems.size());
        text.append(" | mineable:").append(snapshot.getAsJsonArray("mineable_blocks"));
        text.append(" | crafting:").append(snapshot.getAsJsonArray("supported_recipes"));
        JsonArray nearby = snapshot.getAsJsonArray("nearby");
        for (int i = 0; i < Math.min(5, nearby.size()); ++i) {
            JsonObject entity = nearby.get(i).getAsJsonObject();
            text.append(" | ").append(entity.get("kind").getAsString()).append(":")
                    .append(entity.get("type").getAsString()).append("@")
                    .append(entity.get("distance").getAsDouble()).append("m");
        }
        return text.toString();
    }
}
