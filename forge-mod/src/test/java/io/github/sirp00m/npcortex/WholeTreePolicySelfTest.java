package io.github.sirp00m.npcortex;

/** Executable pure-Java acceptance tests; no Forge game dependencies. */
public final class WholeTreePolicySelfTest {
    private static int count;
    private static void check(boolean b, String name) {
        if (!b) throw new AssertionError(name);
        count++;
    }
    public static void main(String[] args) {
        check(WholeTreePolicy.canStartTree(0, 0, 0, 6), "first natural tree allowed");
        check(WholeTreePolicy.canStartTree(1, 7, 5, 6), "next tree when goal unmet");
        check(!WholeTreePolicy.canStartTree(1, 7, 6, 6), "no new tree after minimum");
        check(!WholeTreePolicy.canStartTree(4, 9, 0, 6), "tree-count cap");
        check(!WholeTreePolicy.canStartTree(0, WholeTreePolicy.MAX_TASK_LOGS, 0, 6), "block-count cap");
        check(WholeTreePolicy.canMine(6, 2), "finish committed tree past requested quota");
        check(!WholeTreePolicy.canMine(6, 0), "no empty tree mining");
        check(!WholeTreePolicy.canMine(WholeTreePolicy.MAX_TASK_LOGS, 4), "no unlimited overharvest");
        check(!WholeTreePolicy.complete(6, 8, 6, 0, 0), "all mined drops must be stored");
        check(!WholeTreePolicy.complete(8, 8, 6, 1, 0), "last log must be removed");
        check(!WholeTreePolicy.complete(8, 8, 6, 0, 1), "temporary terrain or scaffolds block finish");
        check(WholeTreePolicy.complete(8, 8, 6, 0, 0), "overharvested tree can finish");
        check(!WholeTreePolicy.complete(6, 0, 6, 0, 0), "cannot just pick up existing logs");
        check(!WholeTreePolicy.complete(4, 4, 6, 0, 0), "minimum not met");
        System.out.println("WholeTreePolicy: " + count + " assertions passed");
    }
}
