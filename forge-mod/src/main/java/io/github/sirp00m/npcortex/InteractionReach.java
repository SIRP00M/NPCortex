package io.github.sirp00m.npcortex;

/** Vanilla-like block interaction reach for owner-controlled NPC actions.
 *  This is deliberately independent of any LLM-produced number or coordinates.
 *  Vanilla 1.20.1: 4.5 blocks in Survival, 5.0 blocks in Creative.
 */
public final class InteractionReach {
    public static final double SURVIVAL_BLOCKS = 4.5D;
    public static final double CREATIVE_BLOCKS = 5.0D;
    public static final int MAX_SCAN_OFFSET = 5;

    private InteractionReach() {}

    public static double blocks(boolean creative) {
        return creative ? CREATIVE_BLOCKS : SURVIVAL_BLOCKS;
    }

    public static double squared(boolean creative) {
        double reach = blocks(creative);
        return reach * reach;
    }

    public static boolean within(double squaredDistance, boolean creative) {
        return squaredDistance >= 0 && squaredDistance <= squared(creative);
    }
}
