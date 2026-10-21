package io.github.sirp00m.npcortex;

/** C-S3: deterministic C1 preflight distinction. A zero-forward turn cannot
 * be rejected by an obstacle that the avatar has NOT commanded travel into.
 * Invalid/non-finite values fail closed to no movement. */
final class C1F2PreflightPolicy {
    private C1F2PreflightPolicy() { }
    static boolean requiresForwardProbe(double forward) {
        return Double.isFinite(forward) && forward>1.0e-5;
    }
}
