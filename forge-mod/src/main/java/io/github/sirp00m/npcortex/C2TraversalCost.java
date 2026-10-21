package io.github.sirp00m.npcortex;

/** Generalized player-passability policy: physical collision is a separate
 * mandatory proof; environmental risks never make a solid wall traversable.
 * Hard hazards and unknown samples fail closed. Soft risks add positive cost.
 */
final class C2TraversalCost {
    private C2TraversalCost() { }
    enum Risk { CLEAR, COSTLY, FORBIDDEN, UNKNOWN }
    static final double SLOWING_PENALTY = 5.0;
    static final double DAMAGING_PENALTY = 8.0;
    record Assessment(boolean passable, double penalty) {
        Assessment {
            if (!Double.isFinite(penalty) || penalty < 0 || (!passable && penalty != 0))
                throw new IllegalArgumentException("Invalid traversal assessment");
        }
        static Assessment blocked() { return new Assessment(false,0); }
        static Assessment clear() { return new Assessment(true,0); }
    }
    static Assessment fromRisk(Risk risk, double penalty) {
        if (risk == null) throw new IllegalArgumentException("Risk required");
        return switch (risk) {
            case CLEAR -> Assessment.clear();
            case COSTLY -> new Assessment(true,penalty > 0 ? penalty : SLOWING_PENALTY);
            case FORBIDDEN, UNKNOWN -> Assessment.blocked();
        };
    }
    static double edgeCost(double baseline, Assessment a) {
        if (a == null || !a.passable() || !Double.isFinite(baseline) || baseline <= 0)
            throw new IllegalArgumentException("Unsafe or invalid traversal edge");
        return baseline+a.penalty();
    }
}
