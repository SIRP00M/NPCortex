package io.github.sirp00m.npcortex.cplus;

/** C-F1 D+ overlay on F0 transitions, synthetic contract only.
 * A failed call AFTER an effect might have happened is NOT a proof of no effect.
 */
public final class CPlusF1EffectGuard {
    private CPlusF1EffectGuard() {}
    public enum Evidence { NONE, VERIFIED_WORLD_EFFECT, VERIFIED_NO_EFFECT, RECONCILED_OBSERVATION }

    public static CPlusLifecycle.State classifyFailure(CPlusLifecycle.State current,
            boolean effectMayHaveOccurred, Evidence evidence) {
        if (current == null || evidence == null || CPlusLifecycle.terminal(current))
            throw new IllegalArgumentException("invalid failure classification state");
        if (effectMayHaveOccurred && evidence != Evidence.VERIFIED_NO_EFFECT)
            return CPlusLifecycle.State.IN_DOUBT;
        return CPlusLifecycle.State.FAILED;
    }
    public static boolean allowCompensation(boolean hasVerifiedOutcome, boolean authorized, boolean worldRechecked) {
        return hasVerifiedOutcome && authorized && worldRechecked;
    }
}
