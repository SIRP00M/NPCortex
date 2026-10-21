package io.github.sirp00m.npcortex;

import java.util.Objects;

/** Sprint 1: immutable, typed evidence for one server-observed candidate support block.
 * Evidence is not an executable movement edge and is not cached across world changes.
 */
record C2TerrainObservation(C2NavGrid.Cell cell, C2TerrainGeometry.Support support,
                            Clearance clearance, Risk risk, Reason reason,
                            double supportedFraction, int collisionBoxes) {
    enum Clearance { CLEAR, BLOCKED, NOT_CHECKED }
    enum Risk { NONE, AVOIDABLE, HAZARD, UNKNOWN }
    enum Reason {
        STANDING_SURFACE, NO_SUPPORT, PARTIAL_FOOTPRINT, UNSUPPORTED_SURFACE_HEIGHT,
        COLLISION_BLOCKED, HEADROOM_BLOCKED, HAZARDOUS_BLOCK,
        UNKNOWN_CHUNK, OUT_OF_BUILD_HEIGHT
    }
    C2TerrainObservation {
        Objects.requireNonNull(cell);
        Objects.requireNonNull(support);
        Objects.requireNonNull(clearance);
        Objects.requireNonNull(risk);
        Objects.requireNonNull(reason);
        if (!Double.isFinite(supportedFraction) || supportedFraction < 0 || supportedFraction > 1
                || collisionBoxes < 0) throw new IllegalArgumentException("Invalid terrain evidence");
        if (cell.condition() == C2NavGrid.Condition.WALKABLE && (support != C2TerrainGeometry.Support.FULL
                || clearance != Clearance.CLEAR
                || (risk != Risk.NONE && risk != Risk.AVOIDABLE)
                || (risk == Risk.AVOIDABLE) != (cell.riskPenalty() > 0)
                || reason != Reason.STANDING_SURFACE))
            throw new IllegalArgumentException("Unsafe terrain cannot be WALKABLE");
        if (cell.condition()==C2NavGrid.Condition.STAIR_TRANSITION
                && (support!=C2TerrainGeometry.Support.PARTIAL
                || supportedFraction < .5 || collisionBoxes < 2
                || clearance!=Clearance.CLEAR
                || (risk!=Risk.NONE && risk!=Risk.AVOIDABLE)
                || (risk==Risk.AVOIDABLE)!=(cell.riskPenalty()>0)
                || reason!=Reason.PARTIAL_FOOTPRINT))
            throw new IllegalArgumentException("Unverified stair may not be traversable");
        if (risk == Risk.UNKNOWN && cell.condition() != C2NavGrid.Condition.UNLOADED)
            throw new IllegalArgumentException("Unknown is not a loaded safe surface");
    }
}
