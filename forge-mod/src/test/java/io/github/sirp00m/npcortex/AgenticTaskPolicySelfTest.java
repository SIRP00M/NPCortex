package io.github.sirp00m.npcortex;

/** Standalone Java 17+ policy test, no Forge dependencies. Run via scripts/test-agentic.ps1. */
public final class AgenticTaskPolicySelfTest {
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); }
    public static void main(String[] args) {
        check(AgenticTaskPolicy.canPlan(0, 0, 0), "initial budget");
        check(!AgenticTaskPolicy.canPlan(40, 0, 0), "LLM cap");
        check(!AgenticTaskPolicy.canPlan(0, 8, 0), "replan cap");
        check(!AgenticTaskPolicy.canPlan(0, 2, 3), "repeat cap");
        check(AgenticTaskPolicy.nextSameFailures("mine/b0", "approach/b1", true, 2) == 1,
                "different plan breaks identical-failure streak");
        check(AgenticTaskPolicy.nextSameFailures("mine/b0", "mine/b0", true, 2) == 3,
                "repeat failure bounded");
        check(AgenticTaskPolicy.nextSameFailures("mine/b0", "mine/b0", false, 2) == 0,
                "confirmed success clears failure streak");
        check(!AgenticTaskPolicy.verifiedComplete(5, 0, 6, 0), "not enough bag items");
        check(!AgenticTaskPolicy.verifiedComplete(6, 0, 6, 1), "unsafe scaffold completion");
        check(!AgenticTaskPolicy.verifiedComplete(10, 5, 6, 0), "preexisting bag items");
        check(AgenticTaskPolicy.verifiedComplete(11, 5, 6, 0), "verified success");
        check(AgenticTaskPolicy.mustCleanBeforeCancel(2), "cancel needs safe cleanup");
        check(!AgenticTaskPolicy.mustCleanBeforeCancel(0), "clean cancel");
        check(AgenticTaskPolicy.nextStalledSteps(0, false) == 1,
                "stalled step increments without progress");
        check(AgenticTaskPolicy.nextStalledSteps(4, false) == 5,
                "stalled step reaches cap");
        check(AgenticTaskPolicy.nextStalledSteps(5, false) == 5,
                "stalled count saturates");
        check(AgenticTaskPolicy.nextStalledSteps(4, true) == 0,
                "only actual progress resets stalled counter");
        check(AgenticTaskPolicy.canPlan(4, 0, 0, 4),
                "planner may recover before stalled cap");
        check(!AgenticTaskPolicy.canPlan(4, 0, 0, 5),
                "no-op loop pauses despite zero failures");
        check(AgenticTaskPolicy.pickupNear(0.0D), "same position item pickup");
        check(AgenticTaskPolicy.pickupNear(2.25D), "pickup boundary");
        check(!AgenticTaskPolicy.pickupNear(2.26D), "distant pickup requires movement");
        check(!AgenticTaskPolicy.pickupNear(Double.NaN), "NaN rejection");
        check(!AgenticTaskPolicy.pickupNear(-1.0D), "negative distance rejection");
        check(!AgenticTaskPolicy.pickupNear(Double.POSITIVE_INFINITY), "infinite distance rejection");
        System.out.println("AgenticTaskPolicy: 24 assertions passed");
    }
}
