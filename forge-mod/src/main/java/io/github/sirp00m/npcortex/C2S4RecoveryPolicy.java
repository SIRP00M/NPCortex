package io.github.sirp00m.npcortex;

/**
 * Sprint 4: dependency-free 3D recovery safety contract. C1 owns ALL physics;
 * this policy only classifies an already-terminal, exactly-correlated receipt.
 * An allowed retry is not proof that an edge is safe: the server must wait for
 * confirmed loaded ground support and compute a new 3D graph before moving.
 */
final class C2S4RecoveryPolicy {
    static final int MAX_SETTLE_WAIT_TICKS = 100;
    static final int REQUIRED_STABLE_GROUND_TICKS = 3;

    enum Verdict { RETRY_3D, RETRY_FLAT, WAIT_FOR_C1, FAIL_CLOSED, FOREIGN_TICKET }

    /** Identity of the real C1 session creator, never guessed from velocity or phase. */
    enum MotionOrigin { NONE, C2_COMMAND, OPERATOR_COMMAND, AUTO_SETTLING, AUTO_COMBAT, SPAWN_SETTLING }
    enum ReplanOwnership { RELEASED, WAIT_PASSIVE_PHYSICS, FOREIGN_OWNER }

    /**
     * C2 must not interrupt its own recovery merely because Vanilla opened an
     * unsolicited, bounded gravity / combat physics session. An operator
     * command (including explicit settle/jump) is NEVER a permissible takeover.
     * The token comparison also detects foreign sessions that began and ended
     * entirely between two C2 END ticks.
     */
    static ReplanOwnership replanOwnership(boolean active, boolean combatActive,
                                           MotionOrigin origin, boolean tokenChanged) {
        if (origin == null) return ReplanOwnership.FOREIGN_OWNER;
        boolean passive = origin == MotionOrigin.AUTO_SETTLING || origin == MotionOrigin.AUTO_COMBAT;
        if (active) return passive ? ReplanOwnership.WAIT_PASSIVE_PHYSICS : ReplanOwnership.FOREIGN_OWNER;
        if (tokenChanged && !passive) return ReplanOwnership.FOREIGN_OWNER;
        return ReplanOwnership.RELEASED;
    }

    /** Only the precisely identified legacy interrupt from this regression may
     * be migrated to cancellation reconciliation. Not a generic journal reset.
     */
    static boolean legacyReplanInterrupted(String state, String reason, boolean hasGoal, boolean hasAction) {
        return "INTERRUPTED".equals(state) && hasGoal && hasAction && reason != null &&
                (reason.startsWith("P1-D C1 motion resumed during replanning; fail closed")
                 || reason.startsWith("P1-D movement owner changed during replanning")
                 || reason.startsWith("P1-D foreign/stale motion ticket"));
    }

    private C2S4RecoveryPolicy() {}

    static Verdict classify(C2TraversabilityGraph.MoveKind kind, boolean ticketMatches,
                            boolean motionActive, String phase, boolean reached) {
        if (!ticketMatches) return Verdict.FOREIGN_TICKET;
        if (motionActive) return Verdict.WAIT_FOR_C1;
        if (phase == null || kind == null) return Verdict.FAIL_CLOSED;
        if (kind == C2TraversabilityGraph.MoveKind.WALK)
            return C2WaypointExecution.recoverableTerminal(phase,reached)
                    ? Verdict.RETRY_FLAT : Verdict.FAIL_CLOSED;
        // A typed C1 receipt may be successful but the outer waypoint center
        // tolerance can still fail. This MUST NOT verify the C0 goal; replan.
        if (phase.equals("TRAVERSAL_COMPLETE_" + kind.name()) && !reached)
            return Verdict.RETRY_3D;
        return switch (phase) {
            // C1 has already completed its single authoritative Vanilla physics
            // owner; recovery MUST additionally wait for a safe grounded pose.
            case "TRAVERSAL_BLOCKED", "TRAVERSAL_TIMEOUT",
                 "TRAVERSAL_LANDING_MISMATCH", "TRAVERSAL_HORIZONTAL_MISMATCH",
                 "TRAVERSAL_LANDING_CHANGED_OR_UNSAFE", "TRAVERSAL_JUMP_NOT_OBSERVED",
                 "TRAVERSAL_DESCENT_NOT_OBSERVED", "TRAVERSAL_INTERRUPTED_COMBAT",
                 "TRAVERSAL_NOT_GROUNDED" -> Verdict.RETRY_3D;
            // Deliberate cancellation, lost/invalid physics evidence and unloaded
            // chunks MUST NOT be promoted to autonomous motion.
            default -> Verdict.FAIL_CLOSED;
        };
    }

    static boolean recoverableStartRefusal(String detail) {
        if (detail == null || !detail.startsWith("S3 C1 traversal refused: S2 ")) return false;
        return detail.startsWith("S3 C1 traversal refused: S2 LANDING_COLLISION")
                || detail.startsWith("S3 C1 traversal refused: S2 LANDING_HEIGHT_MISMATCH")
                || detail.startsWith("S3 C1 traversal refused: S2 UNSAFE_LANDING")
                || detail.startsWith("S3 C1 traversal refused: S2 JUMP_HEADROOM_BLOCKED");
    }

    /** Only known C0 action-owning, nonterminal journals can reconcile on reload.
     * NEVER auto-run their previous movement or guess about HTTP registrations.
     */
    static boolean restartCandidate(String state, boolean hasGoal, boolean hasAction) {
        return hasGoal && hasAction && ("MOVING".equals(state)
                || "REPLANNING".equals(state) || "RESULT_PENDING".equals(state)
                || "DECIDED".equals(state));
    }

    /** Pure contract used before every attempted fresh plan. */
    static boolean safeToPlan(boolean hasLiveActor, boolean loaded, boolean grounded,
                              boolean c1Active, boolean combatActive, boolean standingSupported,
                              int stableGroundTicks) {
        return hasLiveActor && loaded && grounded && !c1Active && !combatActive
                && standingSupported && stableGroundTicks >= REQUIRED_STABLE_GROUND_TICKS;
    }
}
