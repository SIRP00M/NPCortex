package io.github.sirp00m.npcortex;

/** Pure Java regression for C1-R2 Fix #2 movement lifecycle ownership. */
public final class C1VanillaMotionOwnershipSelfTest {
    private static int checks;
    private static void ok(boolean b, String label) {
        checks++;
        if (!b) throw new AssertionError(label);
    }
    private static C1VanillaMotionOwnership.Velocity v(double x, double y, double z) {
        return new C1VanillaMotionOwnership.Velocity(x, y, z);
    }
    private static void same(C1VanillaMotionOwnership.Velocity actual,
                             C1VanillaMotionOwnership.Velocity expected, String label) {
        ok(actual.equals(expected), label + ": actual=" + actual + " expected=" + expected);
    }
    public static void main(String[] ignored) {
        var owner = new C1VanillaMotionOwnership();
        // Exact user-reported failure: Vanilla produces two hits, then the
        // synthetic world tick leaves (0,0,0) or vertical gravity only.
        owner.captureVanilla(v(.3265, .4, .2311));
        owner.captureVanilla(v(.9254, .4, .7630));
        var step = owner.begin(v(0, 0, 0), true);
        same(step.velocity(), v(.9254, .4, .7630), "last vanilla impulse wins, no addition");
        ok(step.source().equals("VANILLA_HANDOFF"), "handoff owns tick boundary");
        owner.completed(v(.75,.33,.6), false, false, false);
        step = owner.begin(v(0,-.0784000015,0), false);
        same(step.velocity(), v(.75,.33,.6), "post-travel vanilla friction preserved over world gravity");
        ok(step.source().equals("OWNED_CONTINUATION"), "continuation status");
        owner.completed(v(.4,.26,.28), false, false, false);
        step = owner.begin(v(.21,.1,.13), false);
        same(step.velocity(), v(.21,.1,.13), "newer live world velocity wins");
        ok(step.source().equals("VANILLA_LIVE"), "live override status");
        owner.clear();
        same(owner.begin(v(0,-.0784,0), false).velocity(), v(0,-.0784,0),
                "clear does not restore after death, despawn, stop");
        // Important: grounded=false in the user's age=1 diagnostic, but
        // only downward gravity remains. We still hand off the horizontal hit.
        owner.captureVanilla(v(.6,.4,-.2));
        same(owner.begin(v(0,-.0784,0), false).velocity(), v(.6,.4,-.2),
                "un-grounded gravity must not mask actual horizontal knockback");
        owner.clear();
        owner.captureVanilla(v(0,.6,0));
        same(owner.begin(v(0,0,0), true).velocity(), v(0,.6,0),
                "pure vertical external impact");
        owner.clear();
        owner.captureVanilla(v(.7,.4,.2));
        same(owner.begin(v(.3,.4,.1), true).velocity(), v(.3,.4,.1),
                "live velocity (e.g. mod-processed impulse) takes precedence");
        owner.clear();
        owner.captureVanilla(v(.5,.4,.3));
        owner.begin(v(0,0,0), true);
        owner.completed(v(.4,-.0784,.2), true, true, true);
        same(owner.begin(v(0,-.0784,0), true).velocity(), v(0,-.0784,0),
                "collision clears stored horizontal and vertical momentum");
        owner.clear();
        owner.captureVanilla(v(.2,.3,.4));
        owner.completed(v(.2,.2,.4), false, false, false);
        owner.captureVanilla(v(.9,.4,.7));
        same(owner.begin(v(0,0,0), true).velocity(), v(.9,.4,.7),
                "new attack overrides prior owned continuation");
        owner.clear();
        owner.captureVanilla(v(Double.NaN,.3,.4));
        same(owner.begin(v(0,0,0), true).velocity(), v(0,0,0), "NaN rejected");
        owner.captureVanilla(v(Double.POSITIVE_INFINITY,.3,.4));
        same(owner.begin(v(0,0,0), true).velocity(), v(0,0,0), "infinite vector rejected");
        owner.captureVanilla(v(.4,.2,.1));
        same(owner.begin(v(Double.NaN,0,0), true).velocity(), v(0,0,0),
                "invalid live motion fail closed");
        // 3, 5 and 10 NPCs must never share an ownership snapshot.
        for (int count : new int[]{1,3,5,10}) {
            var owners = new C1VanillaMotionOwnership[count];
            for (int n=0; n<count; n++) {
                owners[n] = new C1VanillaMotionOwnership();
                owners[n].captureVanilla(v(n+.2,.4,-n-.3));
            }
            for (int n=0; n<count; n++) {
                same(owners[n].begin(v(0,0,0), true).velocity(), v(n+.2,.4,-n-.3),
                        "owner isolated " + count + ":" + n);
            }
        }
        System.out.println("PASS C1-R2 Fix #2 Velocity Ownership: " + checks + " assertions");
    }
}
