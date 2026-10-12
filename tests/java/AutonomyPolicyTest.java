import io.github.sirp00m.npcortex.AutonomyPolicy;

public final class AutonomyPolicyTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    private static AutonomyPolicy.Decision decide(boolean enabled, boolean owner, boolean visible,
            boolean following, boolean planBusy, boolean retreating, double distance,
            long tick, long nextAlertTick) {
        return AutonomyPolicy.decide(enabled, owner, visible, following, planBusy, retreating,
                distance, 10.0D, 4.0D, tick, nextAlertTick);
    }
    public static void main(String[] args) {
        var danger = decide(true, true, true, true, false, false, 3.0D, 100, 0);
        check(danger.warnOwner(), "warn for a close hostile");
        check(danger.tryRetreat(), "retreat when following and too close");
        check(!decide(false, true, true, true, false, false, 2.0D, 100, 0).warnOwner(), "opt-out no warning");
        check(!decide(false, true, true, true, false, false, 2.0D, 100, 0).tryRetreat(), "opt-out no movement");
        check(!decide(true, false, true, true, false, false, 2.0D, 100, 0).warnOwner(), "owner offline");
        check(!decide(true, true, false, true, false, false, 2.0D, 100, 0).warnOwner(), "no wall vision");
        check(!decide(true, true, true, true, false, false, 13.0D, 100, 0).warnOwner(), "out of sensor radius");
        check(decide(true, true, true, false, false, false, 2.0D, 100, 0).warnOwner(), "stay still warns");
        check(!decide(true, true, true, false, false, false, 2.0D, 100, 0).tryRetreat(), "stay does not move");
        check(!decide(true, true, true, true, true, false, 2.0D, 100, 0).tryRetreat(), "plan cannot be interrupted");
        check(!decide(true, true, true, true, false, true, 2.0D, 100, 0).tryRetreat(), "no overlapping retreat");
        check(!decide(true, true, true, true, false, false, 5.0D, 100, 0).tryRetreat(), "not close enough to flee");
        check(decide(true, true, true, true, false, false, 5.0D, 100, 0).warnOwner(), "warn but not flee");
        check(!decide(true, true, true, true, false, false, 5.0D, 100, 101).warnOwner(), "alert cooldown");
        check(decide(true, true, true, true, false, false, 5.0D, 101, 101).warnOwner(), "cooldown expiry");
        check(!decide(true, true, true, true, false, false, Double.NaN, 100, 0).warnOwner(), "reject NaN");
        check(!decide(true, true, true, true, false, false, -1, 100, 0).tryRetreat(), "reject negative");
        check(!decide(true, true, true, true, false, false, Double.POSITIVE_INFINITY, 100, 0).warnOwner(), "reject infinity");
        check(decide(true, true, true, true, true, false, 2.0D, 100, 0).warnOwner(), "warn even when plan busy");
        System.out.println("AutonomyPolicy: " + checks + " assertions passed.");
    }
}
