package io.github.sirp00m.npcortex;

/** Pure, testable rules: no LLM may bypass these server-side restrictions. */
public final class AutonomyPolicy {
    public record Decision(boolean warnOwner, boolean tryRetreat) {}
    private AutonomyPolicy() {}

    public static Decision decide(boolean enabled, boolean ownerPresent, boolean threatVisible,
            boolean following, boolean planBusy, boolean alreadyRetreating,
            double distance, double alertRadius, double retreatRadius,
            long gameTick, long nextAlertTick) {
        if (!enabled || !ownerPresent || !threatVisible || !Double.isFinite(distance)
                || distance < 0 || distance > alertRadius) return new Decision(false, false);
        boolean warn = gameTick >= nextAlertTick;
        // Never move an NPC that has been told to stay. Never interrupt explicit plans.
        boolean retreat = following && !planBusy && !alreadyRetreating && distance <= retreatRadius;
        return new Decision(warn, retreat);
    }
}
