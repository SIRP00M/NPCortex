package io.github.sirp00m.npcortex;

import java.util.Set;

/** Hard limits for temporary owner-authorized NPC pillars. No arbitrary AI coordinates. */
public final class ScaffoldPolicy {
    public static final int MAX_HEIGHT = Integer.MAX_VALUE; // compatibility; actual limit is world height
    public static final int MAX_TREE_HEIGHT = 16;
    public static final int JUMP_TIMEOUT_TICKS = 45;
    private static final Set<String> SAFE_BLOCKS = Set.of(
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks",
            "minecraft:stone", "minecraft:oak_log");

    private ScaffoldPolicy() {}

    public static boolean validMaterial(String id) { return SAFE_BLOCKS.contains(id) || TreeSpecies.supportedLog(id); }
    public static boolean sameTrunk(int x, int z, int anchorX, int anchorZ) {
        // Only straight 1x1 oak trunks in v0.8.4: avoids nearby/connected tree swaps.
        return x == anchorX && z == anchorZ;
    }
    public static boolean mayBuild(int placed, boolean onGround, boolean clearAbove) {
        return placed >= 0 && placed < MAX_HEIGHT && onGround && clearAbove;
    }
    public static boolean jumpClearance(double feetY, int baseFeetY, double horizontalOffset) {
        return feetY >= baseFeetY + 1.02D && horizontalOffset <= 0.40D;
    }
    public static boolean safeDisassembly(int topY, int npcFeetY, double horizontalOffset,
            boolean grounded, boolean correctBlock, boolean hasSupport) {
        return grounded && correctBlock && hasSupport && horizontalOffset <= 0.5D
                && npcFeetY == topY + 1;
    }
}
