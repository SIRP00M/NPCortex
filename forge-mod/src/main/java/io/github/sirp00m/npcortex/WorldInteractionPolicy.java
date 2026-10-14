package io.github.sirp00m.npcortex;

import java.util.Map;
import java.util.Set;

/** Auditable hard bounds for alpha world edits. No LLM-supplied coordinates. */
public final class WorldInteractionPolicy {
    private static final Set<String> BREAKABLE = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:sand",
            "minecraft:gravel", "minecraft:stone", "minecraft:cobblestone",
            "minecraft:oak_planks");
    private static final Set<String> PLACEABLE = Set.of(
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks",
            "minecraft:crafting_table", "minecraft:stone", "minecraft:sand");
    // Self-contained, verified 2x2 crafting recipes, not the complete recipe manager.
    public record Recipe(Map<String, Integer> ingredients, int output) {}
    private static final Map<String, Recipe> RECIPES = Map.of(
            "minecraft:oak_planks", new Recipe(Map.of("minecraft:oak_log", 1), 4),
            "minecraft:stick", new Recipe(Map.of("minecraft:oak_planks", 2), 4),
            "minecraft:crafting_table", new Recipe(Map.of("minecraft:oak_planks", 4), 1),
            "minecraft:torch", new Recipe(Map.of("minecraft:coal", 1, "minecraft:stick", 1), 4));

    private WorldInteractionPolicy() {}
    public static boolean breakable(String id) { return BREAKABLE.contains(id) || TreeSpecies.supportedLog(id); }
    public static boolean placeable(String id) { return PLACEABLE.contains(id); }
    public static Recipe recipe(String id) { return RECIPES.get(id); }
    public static Set<String> recipeOutputs() { return RECIPES.keySet(); }
    public static Set<String> breakableIds() { java.util.Set<String> all = new java.util.HashSet<>(BREAKABLE); all.addAll(TreeSpecies.supportedLogs()); return java.util.Set.copyOf(all); }
    public static Set<String> placeableIds() { return PLACEABLE; }
}
