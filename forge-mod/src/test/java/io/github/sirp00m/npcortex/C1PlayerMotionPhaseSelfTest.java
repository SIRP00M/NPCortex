package io.github.sirp00m.npcortex;

/** Proves motion lifecycle does not schedule combat and input simultaneously. */
public final class C1PlayerMotionPhaseSelfTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] ignored) {
        for (int i = -2; i < 120; i++) {
            for (boolean plan : new boolean[]{false, true}) {
                C1PlayerMotionPhase expected = i > 0 ? C1PlayerMotionPhase.COMBAT
                        : plan ? C1PlayerMotionPhase.COMMANDED : C1PlayerMotionPhase.SETTLING;
                check(C1PlayerMotionPhase.choose(i, plan) == expected,
                        "exclusive movement owner ticks="+i+" plan="+plan);
            }
        }
        System.out.println("PASS C1-R2 Fix #2 Movement Lifecycle: " + checks + " assertions");
    }
}
