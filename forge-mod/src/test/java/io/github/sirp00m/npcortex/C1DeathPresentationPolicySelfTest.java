package io.github.sirp00m.npcortex;

/** Dependency-free tests for the bounded R1 death window; not a Forge rendering test. */
public final class C1DeathPresentationPolicySelfTest {
    private static int assertions;
    private static void check(boolean condition, String text) {
        assertions++;
        if (!condition) throw new AssertionError(text);
    }
    public static void main(String[] ignored) {
        check(C1DeathPresentationPolicy.PRESENTATION_TICKS >= 20, "allow full Vanilla fallover");
        check(C1DeathPresentationPolicy.PRESENTATION_TICKS <= 40, "hard bounded window");
        for (int mask=0; mask<8; mask++) {
            boolean persistedDead=(mask&1)!=0, registered=(mask&2)!=0, removed=(mask&4)!=0;
            check(C1DeathPresentationPolicy.mayPresent(persistedDead,registered,removed)
                    == (persistedDead && registered && !removed), "dead presentation gate " + mask);
        }
        long start=203, deadline=start+C1DeathPresentationPolicy.PRESENTATION_TICKS;
        for (int t=0;t<=C1DeathPresentationPolicy.PRESENTATION_TICKS+2;t++) {
            long now=start+t;
            check(C1DeathPresentationPolicy.shouldDetach(now,deadline,true,false) == (now>=deadline),
                    "boundary t="+t);
        }
        check(C1DeathPresentationPolicy.shouldDetach(start,deadline,false,false), "avatar changed");
        check(C1DeathPresentationPolicy.shouldDetach(start,deadline,true,true), "avatar removed");
        check(!C1DeathPresentationPolicy.shouldDetach(start,deadline,true,false), "no premature detach");
        check(C1DeathPresentationPolicy.shouldDetach(deadline,deadline,true,false), "bounded teardown");
        check(C1DeathPresentationPolicy.VANISH_POOF_COUNT >= 20, "noticeable mob-like poof");
        check(C1DeathPresentationPolicy.VANISH_CLOUD_COUNT >= 6, "visible cloud accent");
        check(C1DeathPresentationPolicy.VANISH_POOF_COUNT + C1DeathPresentationPolicy.VANISH_CLOUD_COUNT <= 64,
                "bounded particle budget");
        for (int mask = 0; mask < 8; mask++) {
            boolean same = (mask & 1) != 0, removed = (mask & 2) != 0, dead = (mask & 4) != 0;
            check(C1DeathPresentationPolicy.shouldEmitVanishPoof(deadline, deadline, same, removed, dead)
                    == (same && !removed && dead), "vanish poof prerequisite mask=" + mask);
            check(!C1DeathPresentationPolicy.shouldEmitVanishPoof(deadline - 1, deadline, same, removed, dead),
                    "no poof before animation ends mask=" + mask);
        }
        for (int elapsed = 0; elapsed < C1DeathPresentationPolicy.PRESENTATION_TICKS + 3; elapsed++) {
            long now = start + elapsed;
            check(C1DeathPresentationPolicy.shouldEmitVanishPoof(now, deadline, true, false, true)
                    == (now >= deadline), "normal poof deadline tick=" + elapsed);
        }
        System.out.println("PASS R1/R1-A Death Presentation Policy: " + assertions + " assertions");
    }
}
