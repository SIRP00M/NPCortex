import io.github.sirp00m.npcortex.InteractionReach;

public final class InteractionReachTest {
    static int assertions = 0;
    private static void check(boolean actual, String label) {
        if (!actual) throw new AssertionError(label);
        assertions++;
    }
    public static void main(String[] args) {
        check(InteractionReach.blocks(false) == 4.5D, "survival reach");
        check(InteractionReach.blocks(true) == 5.0D, "creative reach");
        check(InteractionReach.squared(false) == 20.25D, "survival squared");
        check(InteractionReach.squared(true) == 25.0D, "creative squared");
        check(InteractionReach.within(20.25D, false), "survival inclusive edge");
        check(!InteractionReach.within(20.26D, false), "survival outside");
        check(InteractionReach.within(25.0D, true), "creative inclusive edge");
        check(!InteractionReach.within(25.01D, true), "creative outside");
        check(!InteractionReach.within(-1.0D, true), "invalid negative squared distance");
        check(InteractionReach.MAX_SCAN_OFFSET == 5, "bounded scan");
        System.out.println("InteractionReach: " + assertions + " assertions passed");
    }
}
