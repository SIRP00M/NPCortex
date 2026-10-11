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
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

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
        root.add("npc", npcData);

        JsonObject blocks = new JsonObject();
        blocks.addProperty("below", blockName(world.getBlockState(pos.below())));
        blocks.addProperty("feet", blockName(world.getBlockState(pos)));
        blocks.addProperty("ahead", blockName(world.getBlockState(pos.relative(npc.getDirection()))));
        root.add("blocks", blocks);

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
