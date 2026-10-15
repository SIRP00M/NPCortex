package io.github.sirp00m.npcortex;

/** Isolated Java 17 policy test; does not claim Forge/Vanilla gameplay success. */
public final class C1VanillaLifecyclePolicySelfTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] ignored) {
        check(C1VanillaLifecyclePolicy.owner(C1VanillaLifecyclePolicy.Phase.WORLD)
                == C1VanillaLifecyclePolicy.Owner.VANILLA, "world tick owned by vanilla");
        check(C1VanillaLifecyclePolicy.owner(C1VanillaLifecyclePolicy.Phase.CONNECTION)
                == C1VanillaLifecyclePolicy.Owner.DISABLED, "no synthetic connection tick");
        for (var phase : new C1VanillaLifecyclePolicy.Phase[] {
                C1VanillaLifecyclePolicy.Phase.MOTION,
                C1VanillaLifecyclePolicy.Phase.COMBAT,
                C1VanillaLifecyclePolicy.Phase.DEATH,
                C1VanillaLifecyclePolicy.Phase.TRACKING }) {
            check(C1VanillaLifecyclePolicy.owner(phase) == C1VanillaLifecyclePolicy.Owner.NPCORTEX,
                    "existing owner: " + phase);
        }
        for (int mask = 0; mask < 8; mask++) {
            boolean registered = (mask & 1) != 0;
            boolean removed = (mask & 2) != 0;
            boolean alive = (mask & 4) != 0;
            check(C1VanillaLifecyclePolicy.mayRunWorldTick(registered, removed, alive)
                    == (registered && !removed && alive), "world gate: " + mask);
        }
        System.out.println("PASS R0 Vanilla Lifecycle Ownership Policy: " + assertions + " assertions");
    }
}
