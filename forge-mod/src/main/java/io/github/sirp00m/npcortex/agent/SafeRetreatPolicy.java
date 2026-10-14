package io.github.sirp00m.npcortex.agent;

import java.util.List;

/** Bounded physical recovery: never leave an NPC stranded on temporary supports
 *  solely because the model selected pause instead of the offered safe descent.
 *  The Forge executor still validates every step and aborts on world changes.
 *  This policy never authors a path or allows unsafe world edits.
 */
public final class SafeRetreatPolicy {
    private SafeRetreatPolicy() {}
    public static boolean shouldDescend(boolean temporaryAccess, List<String> choices) {
        if (!temporaryAccess || choices == null || !choices.contains("descend")) return false;
        // Preserve genuine Qwen choices if an actionable mining/navigation
        // skill remains. The safety override applies ONLY to a physical dead end.
        return choices.stream().allMatch(c -> c.equals("pause") || c.equals("descend")
                || c.equals("restore") || c.equals("wait"));
    }
    /** A stuck harvest may undo OWNED access to obtain a different vantage.
     * Never descend merely because a target is high, or when no journal exists.
     * Permission, ownership and physical inverse steps remain executor-enforced.
     */
    public static boolean offerHarvestRetreat(boolean committed, int remaining,
            boolean ownedAccess, boolean failedHarvestRoute, String lastSearch, int retreatsWithoutMining) {
        return committed && remaining > 0 && ownedAccess && failedHarvestRoute
                && retreatsWithoutMining < 2
                && ("NO_PATH".equals(lastSearch) || "SEARCH_BUDGET".equals(lastSearch)
                    || "INVALID_START".equals(lastSearch));
    }
    public static boolean pauseAfterRetreat(int retreatsWithoutMining) {
        // The first safe descent leads to replanning from a fresh viewpoint.
        // The second unsuccessful cycle descends before requesting human help.
        return retreatsWithoutMining >= 2;
    }
}
