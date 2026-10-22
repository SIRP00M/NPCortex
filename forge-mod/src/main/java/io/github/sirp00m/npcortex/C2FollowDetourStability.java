package io.github.sirp00m.npcortex;

/** S3 follow-only: moving 1.5 blocks to trigger replanning is NOT reason
 * enough to throw away a successful chosen detour side on uneven terrain.
 * A large target relocation permits new exploration. */
final class C2FollowDetourStability {
    static final double RELOCATION_DISTANCE=6.0;
    private double anchorX,anchorZ;
    private boolean anchored;
    void chosen(double x,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(z)) throw new IllegalArgumentException();
        if(!anchored) {anchorX=x;anchorZ=z;anchored=true;}
    }
    boolean shouldReset(double x,double z) {
        if(!anchored) return true;
        return Double.isFinite(x)&&Double.isFinite(z)
                && Math.hypot(x-anchorX,z-anchorZ)>=RELOCATION_DISTANCE;
    }
    void clear() {anchored=false;}
}
