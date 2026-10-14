package io.github.sirp00m.npcortex;

/** Pure-Java invariants for the new agent loop; no game/server/model dependencies.
 * The model may choose HOW but can never override these invariants.
 */
public final class AgenticTaskPolicy {
    public static final int MAX_CALLS = 80;
    public static final int MAX_FAILURES = 8;
    public static final int MAX_SAME_FAILURES = 3;
    public static final int MAX_STALLED = 5;
    // Picking up a ground item requires walking close to it. Server-owned,
    // never expanded by an LLM, creative reach or dialogue permissions.
    public static final double PICKUP_RADIUS_SQUARED = 2.25D;
    public static boolean pickupNear(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0
                && distanceSquared <= PICKUP_RADIUS_SQUARED;
    }
    public static boolean canContinue(int calls, int failures, int same, int stalled) {
        return calls >= 0 && failures >= 0 && failures < MAX_FAILURES
                && same >= 0 && same < MAX_SAME_FAILURES && stalled >= 0 && stalled < MAX_STALLED;
    }
    private AgenticTaskPolicy() {}

    public static boolean canPlan(int calls, int failures, int consecutiveSame) {
        return calls >= 0 && calls < MAX_CALLS && failures >= 0
                && failures < MAX_FAILURES && consecutiveSame >= 0
                && consecutiveSame < MAX_SAME_FAILURES;
    }
    public static boolean canPlan(int calls, int failures, int consecutiveSame, int stalled) {
        return canPlan(calls, failures, consecutiveSame)
                && stalled >= 0 && stalled < MAX_STALLED;
    }
    /** Count consecutive tool executions without a verified change to the world,
     * task inventory, scaffold, or the NPC's position. Not an AI behavior policy. */
    public static int nextStalledSteps(int previous, boolean actualProgress) {
        return actualProgress ? 0 : Math.min(MAX_STALLED, Math.max(0, previous) + 1);
    }
    /** Failed execution is charged to MAX_FAILURES rather than also exhausting
     * the no-op watchdog before the brain sees a refreshed skill selection.
     * The original watchdog still applies to successful but ineffective work.
     * Neither counter can be reset without verified physical progress. */
    public static int nextStalledSteps(int previous, boolean actualProgress, boolean executionFailed) {
        if (actualProgress) return 0;
        return executionFailed ? Math.min(MAX_STALLED, Math.max(0, previous))
                : nextStalledSteps(previous,false);
    }
    public static boolean verifiedComplete(int bag, int initial, int goal, int scaffold) {
        return initial >= 0 && goal > 0 && goal <= 16 && bag >= initial + goal
                && scaffold == 0;
    }
    public static int nextSameFailures(String previous, String next, boolean failed, int previousCount) {
        if (!failed) return 0;
        return next != null && next.equals(previous) ? previousCount + 1 : 1;
    }
    public static boolean mustCleanBeforeCancel(int scaffold) {
        return scaffold > 0;
    }
}
