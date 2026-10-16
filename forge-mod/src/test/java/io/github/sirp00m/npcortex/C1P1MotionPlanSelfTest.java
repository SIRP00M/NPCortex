package io.github.sirp00m.npcortex;

/** Run directly with Java 17. Pure state-machine tests; NOT a Forge physics test. */
public final class C1P1MotionPlanSelfTest {
    private static int checks;
    private static void check(boolean ok, String description) {
        checks++;
        if (!ok) throw new AssertionError(description);
    }
    private static void illegal(Runnable action, String description) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(description);
    }
    private static void approx(double a, double b, String description) {
        check(Math.abs(a - b) < 0.0001, description + ": " + a + " expected " + b);
    }
    private static C1P1MotionPlan make(C1P1MotionPlan.Direction dir, double distance) {
        return new C1P1MotionPlan(dir, distance, 0, 0, 0);
    }
    public static void main(String[] args) {
        illegal(() -> make(null, 1), "null direction");
        illegal(() -> make(C1P1MotionPlan.Direction.FORWARD, Double.NaN), "nan rejected");
        illegal(() -> make(C1P1MotionPlan.Direction.FORWARD, Double.POSITIVE_INFINITY), "infinite rejected");
        illegal(() -> make(C1P1MotionPlan.Direction.FORWARD, 0.49), "below minimum rejected");
        C1P1MotionPlan tiny = new C1P1MotionPlan(C1P1MotionPlan.Direction.FORWARD,
                0.35, 0, 0, 0, true);
        check(tiny.moving(),"C2 precision uses normal C1 motion state machine");
        tiny.observe(0,0.24);
        check(tiny.phase==C1P1MotionPlan.Phase.COMPLETE,"C2 short progress completes normally");
        illegal(()->new C1P1MotionPlan(C1P1MotionPlan.Direction.FORWARD,
                0.09,0,0,0,true),"C2 below precision minimum rejected");
        illegal(()->new C1P1MotionPlan(C1P1MotionPlan.Direction.FORWARD,
                8.1,0,0,0,true),"C2 beyond C1 maximum rejected");
        illegal(() -> make(C1P1MotionPlan.Direction.FORWARD, 8.01), "above maximum rejected");
        for (C1P1MotionPlan.Direction direction : C1P1MotionPlan.Direction.values()) {
            C1P1MotionPlan plan = make(direction, 1);
            approx(Math.hypot(plan.axisX,plan.axisZ),1.0,"normalized direction " + direction);
            check(plan.moving(),"initially moving " + direction);
        }
        approx(make(C1P1MotionPlan.Direction.FORWARD, 1).axisZ, 1, "yaw 0 forward south");
        approx(make(C1P1MotionPlan.Direction.LEFT, 1).axisX, 1, "yaw 0 left east");
        C1P1MotionPlan rotated = new C1P1MotionPlan(C1P1MotionPlan.Direction.FORWARD,1,90,5,5);
        approx(rotated.axisX,-1,"yaw90 forward west");
        approx(rotated.axisZ,0,"yaw90 forward z zero");

        C1P1MotionPlan progress = make(C1P1MotionPlan.Direction.FORWARD, 1.5);
        progress.observe(0,0.5);
        check(progress.moving(),"partial still moving");
        approx(progress.remaining(),1.0,"one block remains");
        progress.observe(0.1,1.45);
        check(progress.phase==C1P1MotionPlan.Phase.COMPLETE,"true forward projection completes");
        check(progress.terminal(),"complete is terminal");

        C1P1MotionPlan reversal = make(C1P1MotionPlan.Direction.FORWARD, 2);
        reversal.observe(0,0.7);
        reversal.observe(0,0.3);
        approx(reversal.progress,0.3,"backward movement reduces net progress");
        check(reversal.moving(),"oscillation does not complete intent");

        C1P1MotionPlan blocked = make(C1P1MotionPlan.Direction.FORWARD, 2);
        for (int i=0;i<C1P1MotionPlan.STALL_LIMIT;i++) blocked.observe(2,0);
        check(blocked.phase==C1P1MotionPlan.Phase.BLOCKED,"sideways slides cannot complete");
        check(blocked.progress==0,"sideways progress zero");

        C1P1MotionPlan timeout = make(C1P1MotionPlan.Direction.FORWARD, 8);
        for (int i=0;i<C1P1MotionPlan.MAX_TICKS;i++) {
            timeout.observe(0, (i+1)*0.005);
        }
        check(timeout.phase==C1P1MotionPlan.Phase.TIMED_OUT,"bounded by 240 ticks");

        C1P1MotionPlan stopped = make(C1P1MotionPlan.Direction.BACK, 2);
        stopped.cancel();
        check(stopped.phase==C1P1MotionPlan.Phase.CANCELLED,"cancelled");
        stopped.observe(0,2);
        check(stopped.progress==0,"cancel cannot be completed by later movement");
        check(C1P1MotionPlan.MAX_SETTLE_TICKS==100,"gravity settle limit");
        System.out.println("PASS C1-P1 MotionPlan: " + checks + " assertions (pure JVM, Java 17)");
    }
}
