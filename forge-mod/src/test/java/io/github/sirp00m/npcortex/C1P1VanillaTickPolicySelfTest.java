package io.github.sirp00m.npcortex;

/** Pure Java-17 tests. Does NOT substitute for Forge compile/gameplay. */
public final class C1P1VanillaTickPolicySelfTest {
    private static int assertions;
    private static void assertCase(boolean want, boolean actual, String name) {
        ++assertions;
        if (want != actual) throw new AssertionError(name + " expected=" + want + " actual=" + actual);
    }
    public static void main(String[] ignored) {
        for (int mask = 0; mask < 32; mask++) {
            boolean live = (mask & 1) != 0;
            boolean active = (mask & 2) != 0;
            boolean loaded = (mask & 4) != 0;
            boolean grounded = (mask & 8) != 0;
            boolean support = (mask & 16) != 0;
            boolean expected = live && !active && loaded && (!grounded || !support);
            assertCase(expected, C1P1VanillaTickPolicy.needsSettling(live, active, loaded, grounded, support), "mask=" + mask);
        }
        System.out.println("PASS C1-P1-C VanillaTickPolicy: " + assertions + " assertions");
    }
}
