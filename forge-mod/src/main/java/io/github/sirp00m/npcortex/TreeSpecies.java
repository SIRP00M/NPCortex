package io.github.sirp00m.npcortex;

import java.util.Map;
import java.util.Set;

/** Data-driven vanilla overworld wood profiles (Minecraft 1.20.1).
 * This registry does NOT grant block-edit permission or prove a natural tree.
 * Nether giant fungi require a separate fungus/cap topology and are excluded.
 */
public final class TreeSpecies {
    public record Profile(String log, Set<String> foliage, boolean permitsWideRoot) {
        public boolean isFoliage(String id) { return foliage.contains(id); }
    }

    private static final Map<String, Profile> PROFILES = Map.ofEntries(
        Map.entry("minecraft:oak_log", new Profile("minecraft:oak_log",
                Set.of("minecraft:oak_leaves", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"), false)),
        Map.entry("minecraft:spruce_log", new Profile("minecraft:spruce_log",
                Set.of("minecraft:spruce_leaves"), true)),
        Map.entry("minecraft:birch_log", new Profile("minecraft:birch_log",
                Set.of("minecraft:birch_leaves"), false)),
        Map.entry("minecraft:jungle_log", new Profile("minecraft:jungle_log",
                Set.of("minecraft:jungle_leaves"), true)),
        Map.entry("minecraft:acacia_log", new Profile("minecraft:acacia_log",
                Set.of("minecraft:acacia_leaves"), false)),
        Map.entry("minecraft:dark_oak_log", new Profile("minecraft:dark_oak_log",
                Set.of("minecraft:dark_oak_leaves"), true)),
        Map.entry("minecraft:mangrove_log", new Profile("minecraft:mangrove_log",
                Set.of("minecraft:mangrove_leaves"), false)),
        Map.entry("minecraft:cherry_log", new Profile("minecraft:cherry_log",
                Set.of("minecraft:cherry_leaves"), false))
    );
    private TreeSpecies() {}
    public static Profile profile(String logId) { return PROFILES.get(logId); }
    public static boolean supportedLog(String blockId) { return PROFILES.containsKey(blockId); }
    public static Set<String> supportedLogs() { return PROFILES.keySet(); }
    public static boolean matchingFoliage(String logId, String leafId) {
        Profile p = profile(logId);
        return p != null && p.isFoliage(leafId);
    }
}
