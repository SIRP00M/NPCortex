package io.github.sirp00m.npcortex;

/** Resolve the supporting block from the *observed foot surface*, including
 * bottom slabs. Integer footY implies support at footY-1; half-height footY
 * implies support at floor(footY). Matches the S2 landing probe convention.
 */
final class C2S3SupportY {
    private C2S3SupportY() { }
    static int at(double footY) {
        if (!Double.isFinite(footY) || footY < Integer.MIN_VALUE+2.0
                || footY > Integer.MAX_VALUE-2.0)
            throw new IllegalArgumentException("Invalid footY for support resolution");
        return (int)Math.floor(footY-0.04);
    }
}
