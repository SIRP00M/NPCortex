package io.github.sirp00m.npcortex;

/** Pure-Java R2 combat scheduling tests; no claims about Forge event behavior. */
public final class C1CombatTickPolicySelfTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    public static void main(String[] ignored) {
        for (int flags=0;flags<16;flags++) {
            boolean registered=(flags&1)!=0, alive=(flags&2)!=0;
            boolean removed=(flags&4)!=0, loaded=(flags&8)!=0;
            check(C1CombatTickPolicy.mayIntegrate(registered,alive,removed,loaded)
                    == (registered&&alive&&!removed&&loaded), "live/loaded gate "+flags);
        }
        check(C1CombatTickPolicy.MAX_COMBAT_TICKS >= 40, "fall recovery duration");
        check(C1CombatTickPolicy.MAX_COMBAT_TICKS <= 120, "bounded integration");
        check(C1CombatTickPolicy.MIN_COMBAT_TICKS >= 3, "minimum impulse integration window");
        check(C1CombatTickPolicy.MIN_COMBAT_TICKS < C1CombatTickPolicy.MAX_COMBAT_TICKS, "minimum below max");
        check(!C1CombatTickPolicy.actualImpulse(0,0,0,0,0,0), "resisted/no impulse is not combat");
        check(C1CombatTickPolicy.actualImpulse(0,0,0,0.3,0.1,0), "vanilla melee impulse");
        check(C1CombatTickPolicy.actualImpulse(0,0,0,0,0.5,0), "vertical blast");
        check(!C1CombatTickPolicy.actualImpulse(0,0,0,1e-6,0,0), "numeric jitter does not start combat");
        check(!C1CombatTickPolicy.actualImpulse(0,0,0,Double.NaN,0,0), "invalid vector rejected");
        check(!C1CombatTickPolicy.actualImpulse(0,0,0,Double.POSITIVE_INFINITY,0,0), "nonfinite rejected");
        check(!C1CombatTickPolicy.flaggedExternalImpulse(false,1,0,0), "no phantom attack when flag absent");
        check(C1CombatTickPolicy.flaggedExternalImpulse(true,1,0,0), "flagged explosion impulse");
        check(!C1CombatTickPolicy.flaggedExternalImpulse(true,0,0,0), "empty flag ignored");
        check(!C1CombatTickPolicy.flaggedExternalImpulse(true,Double.NaN,0,0), "nonfinite flag ignored");
        for (int age=0; age<C1CombatTickPolicy.MIN_COMBAT_TICKS; age++) {
            check(!C1CombatTickPolicy.shouldEndImpulse(age,true,0,0,0), "minimum physics age "+age);
        }
        for (int age=C1CombatTickPolicy.MIN_COMBAT_TICKS; age<C1CombatTickPolicy.MIN_COMBAT_TICKS+10; age++) {
            check(C1CombatTickPolicy.shouldEndImpulse(age,true,0,0,0), "resting landing "+age);
            check(!C1CombatTickPolicy.shouldEndImpulse(age,false,0,0,0), "airborne retention "+age);
            check(!C1CombatTickPolicy.shouldEndImpulse(age,true,0.35,0,0), "horizontal impulse remains "+age);
            check(!C1CombatTickPolicy.shouldEndImpulse(age,true,0,0.3,0), "vertical impulse remains "+age);
        }
        check(C1CombatTickPolicy.shouldEndImpulse(6, true, 0, -0.0784000015, 0),
                "grounded vanilla gravity does not keep combat alive forever");
        check(!C1CombatTickPolicy.shouldEndImpulse(6, true, 0, 0.4, 0),
                "positive launch momentum keeps combat active");
        for (int remaining=-3;remaining<=15;remaining++) {
            check(C1CombatTickPolicy.ownsTravelThisTick(remaining)==(remaining>0), "combat owns travel "+remaining);
            check(C1CombatTickPolicy.canClearHorizontalVelocity(remaining)==(remaining<=0), "stop guard "+remaining);
        }
        // R2 Fix #1: reproduce server velocity without authoritative position.
        // The fallback is allowed only if Vanilla travel DID NOT move on the
        // same axis. Movement already supplied by Vanilla is never repeated.
        check(C1CombatTickPolicy.needsHorizontalCollisionFallback(0.8, 0.9, 0, 0, false),
                "real sword knockback, no displacement, use Vanilla collision fallback");
        check(C1CombatTickPolicy.needsHorizontalCollisionFallback(-0.5, 0.3, 0, 0, false),
                "negative horizontal velocity is handled");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(0.8, 0.9, 0.8, 0.9, false),
                "travel already moved: never double-apply");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(0.8, 0.9, 0.005, 0, false),
                "some horizontal displacement already occurred");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(0.8, 0.9, 0, 0, true),
                "do not bypass a recorded wall collision");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(0, 0, 0, 0, false),
                "resting player must not move");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(0.001, 0, 0, 0, false),
                "floating point jitter must not move player");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(Double.NaN, 0.8, 0, 0, false),
                "NaN velocity must not move player");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(Double.POSITIVE_INFINITY, 0.8, 0, 0, false),
                "nonfinite velocity must not move player");
        check(!C1CombatTickPolicy.needsHorizontalCollisionFallback(0.8, 0.9, Double.NaN, 0, false),
                "nonfinite travel displacement must not trigger fallback");
        check(C1CombatTickPolicy.needsVerticalCollisionFallback(0.4, 0, true, false),
                "unconsumed upwards knockback must be simulated");
        check(C1CombatTickPolicy.needsVerticalCollisionFallback(-0.4, 0, false, false),
                "airborne downwards momentum may be simulated");
        check(!C1CombatTickPolicy.needsVerticalCollisionFallback(-0.08, 0, true, false),
                "grounded gravity must not introduce extra motion");
        check(!C1CombatTickPolicy.needsVerticalCollisionFallback(0.4, 0.4, true, false),
                "Vanilla vertical motion already advanced");
        check(!C1CombatTickPolicy.needsVerticalCollisionFallback(0.4, 0, true, true),
                "ceiling collision stops fallback");
        check(!C1CombatTickPolicy.needsVerticalCollisionFallback(Double.NaN, 0, false, false),
                "NaN vertical velocity must not move player");
        check(!C1CombatTickPolicy.needsVerticalCollisionFallback(0.001, 0, false, false),
                "vertical jitter must not move player");
        // Vanilla knockback events remain authoritative: do not create another
        // knockback impulse. This code only applies already-existing velocity.
        check(C1CombatTickPolicy.actualImpulse(0, 0, 0, 0.8, 0.4, 0.9),
                "original sword impulse is still recognized");
        check(!C1CombatTickPolicy.actualImpulse(0.8, 0.4, 0.9, 0.8, 0.4, 0.9),
                "no duplicate impulse without actual velocity change");
        System.out.println("PASS C1-R2 Combat Tick Policy: " + checks + " assertions");
    }
}
