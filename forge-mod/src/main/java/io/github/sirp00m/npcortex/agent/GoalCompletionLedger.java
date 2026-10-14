package io.github.sirp00m.npcortex.agent;

/** Facts, not an AI action policy: separate physical tree destruction, borrowed
 * access materials, recovered inventory, and final verification. Never infer
 * that missing items are in the bag or award them by a counter adjustment. */
public final class GoalCompletionLedger {
    public enum Phase { FIND_TREE, HARVEST, RECLAIM_ACCESS, RECOVER_DROPS, VERIFY_TREE, READY_TO_FINISH }
    private GoalCompletionLedger() {}

    public static int missing(int mined, int collected, int borrowed) {
        return Math.max(0, mined - Math.max(0, collected) - Math.max(0, borrowed));
    }

    public static Phase phase(boolean committed, int remaining, int completed,
            int mined, int collected, int borrowed, int goal, int temporary) {
        if (committed && remaining > 0) return Phase.HARVEST;
        if (temporary > 0 && mined > 0) return Phase.RECLAIM_ACCESS;
        if (missing(mined, collected, borrowed) > 0) return Phase.RECOVER_DROPS;
        if (committed) return Phase.VERIFY_TREE;
        if (collected >= goal && completed > 0 && mined > 0) return Phase.READY_TO_FINISH;
        return Phase.FIND_TREE;
    }

    /** A bounded liveness guard applies only to post-harvest recovery. Walking
     * or mining soil elsewhere is not evidence that the missing goal item exists. */
    public static boolean recoveryExhausted(Phase phase, int callsWithoutGoalProgress) {
        return phase == Phase.RECOVER_DROPS && callsWithoutGoalProgress >= 18;
    }
}
