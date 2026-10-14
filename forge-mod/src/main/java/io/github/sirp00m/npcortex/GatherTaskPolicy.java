package io.github.sirp00m.npcortex;

/** Small independent rules so task bounds are testable without Minecraft runtime. */
public final class GatherTaskPolicy {
    public static final int MAX_ITEMS = 16;
    public static final int SEARCH_RADIUS = 12;
    public static final int HOME_RADIUS = 20;
    public static final int OWNER_RADIUS = 32;
    public static final int MAX_WAIT_TICKS = 160;

    private GatherTaskPolicy() {}

    public static boolean valid(String itemId, int count) {
        // v0.8.1 supports direct block-to-item drops only. Stone, grass,
        // gravel, etc. can produce another item and cannot be counted safely.
        return count >= 1 && count <= MAX_ITEMS && (TreeSpecies.supportedLog(itemId) || java.util.Set.of(
                "minecraft:oak_log", "minecraft:dirt", "minecraft:sand",
                "minecraft:cobblestone", "minecraft:oak_planks").contains(itemId));
    }

    public static int remaining(int goal, int collected) { return Math.max(0, goal - collected); }

    public static boolean withinHome(double distanceSquared) {
        return distanceSquared <= HOME_RADIUS * HOME_RADIUS;
    }

    public static boolean ownerPresent(double distanceSquared) {
        return distanceSquared <= OWNER_RADIUS * OWNER_RADIUS;
    }
}
