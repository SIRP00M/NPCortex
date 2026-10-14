package io.github.sirp00m.npcortex.agent;

/** Physical soil interaction geometry shared by observation, planning and execution.
 * Mining uses eye reach; pickup uses chest range. They are different distances.
 */
public final class MaterialApproach {
    private MaterialApproach() {}
    public static final double PICKUP_RADIUS = 2.25;
    public static boolean reachable(double x, double y, double z, double eyeHeight,
            double bodyHeight, double bodyWidth, int tx, int ty, int tz, double miningReachSq) {
        // Never select a stand whose body overlaps the space above the source.
        double half=bodyWidth/2;
        if (x+half>tx && x-half<tx+1 && z+half>tz && z-half<tz+1
                && y<ty+2 && y+bodyHeight>ty+1) return false;
        double dx=x-tx-0.5, dz=z-tz-0.5;
        double eyeDy=y+eyeHeight-ty-0.5, chestDy=y+bodyHeight*0.45-ty-0.5;
        return dx*dx+dz*dz+eyeDy*eyeDy<=miningReachSq
                && dx*dx+dz*dz+chestDy*chestDy<=PICKUP_RADIUS*PICKUP_RADIUS;
    }
}
