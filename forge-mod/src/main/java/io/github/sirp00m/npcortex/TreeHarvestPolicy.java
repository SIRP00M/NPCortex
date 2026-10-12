package io.github.sirp00m.npcortex;

/** Conservative, deterministic tree-harvest selection. Server evaluates real rays
 *  and path candidates; this class ranks feasible options only. */
public final class TreeHarvestPolicy {
    public static final int MAX_CANDIDATES = 40;
    public static final int MAX_LEAF_CLEAR = 8;
    private TreeHarvestPolicy() {}

    public static double priority(boolean visibleNow, boolean walkableView,
            boolean naturalLeafObstruction, int logY, int npcY, double travelSq) {
        if ((!visibleNow && !walkableView && !naturalLeafObstruction) || travelSq < 0)
            return Double.POSITIVE_INFINITY;
        // Reachable logs always beat foliage removal, and walking around is
        // always preferable to damaging natural foliage.
        double tier = visibleNow ? 0 : walkableView ? 1000 : 2000;
        // From ground start low and work up; when standing higher than a trunk,
        // prefer higher/easier logs first. Re-evaluated after every broken log.
        double height = npcY > logY + 1 ? -logY : logY;
        return tier + travelSq * 0.10D + height * 0.25D;
    }
}
