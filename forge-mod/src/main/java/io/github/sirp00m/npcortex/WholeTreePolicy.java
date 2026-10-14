package io.github.sirp00m.npcortex;

/** Pure invariants for server-owned whole-tree harvesting. No AI action authority. */
public final class WholeTreePolicy {
    public static final int MAX_TREE_LOGS = 96;
    public static final int MAX_TASK_LOGS = 192;
    public static final int MAX_TREES = 4;
    public static final int MAX_EXTRA_SOFT_EDITS = 2;
    public static final int MAX_HOME_HORIZONTAL = 12;
    public static final int MAX_HOME_VERTICAL = 28;
    public static final int MAX_TREE_HORIZONTAL = 9;
    public static final int MAX_TREE_VERTICAL = 26;
    private WholeTreePolicy() {}

    public static boolean canStartTree(int completed, int mined, int collected, int goal) {
        return completed >= 0 && completed < MAX_TREES && mined >= 0
                && mined < MAX_TASK_LOGS && collected < goal;
    }
    public static boolean canMine(int mined, int remainingOnCommittedTree) {
        return mined >= 0 && mined < MAX_TASK_LOGS && remainingOnCommittedTree > 0;
    }
    public static boolean complete(int collected, int mined, int goal,
            int treeRemaining, int temporaryBlocks) {
        return goal > 0 && goal <= 16 && mined > 0 && mined <= MAX_TASK_LOGS
                && collected >= goal && collected >= mined
                && treeRemaining == 0 && temporaryBlocks == 0;
    }
}
