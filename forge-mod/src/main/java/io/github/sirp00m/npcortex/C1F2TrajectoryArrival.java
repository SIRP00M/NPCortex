package io.github.sirp00m.npcortex;

/** Pure-Java FOLLOW-only velocity-input limiter for a verified flat segment.
 * This cannot mark a C1/C2 receipt complete, move the avatar or alter a route.
 * The vanilla C1 controller remains the sole physical movement authority. */
final class C1F2TrajectoryArrival {
    private C1F2TrajectoryArrival() {}
    static double cap(double requested,double distance,double actualStep,
            boolean terminal,boolean corner) {
        if(!Double.isFinite(requested)||!Double.isFinite(distance)
                ||!Double.isFinite(actualStep)||requested<0||distance<0||actualStep<0)
            throw new IllegalArgumentException("Invalid arrival input");
        if(requested==0 || (!terminal && !corner)) return requested;
        // On approach to the final receipt, use very small inputs *before*
        // reaching the 0.28-block physical acceptance radius. Overshooting
        // then chasing the center point causes dramatic heading oscillation.
        double braking=terminal ? .95 : .70;
        if(distance>=braking) return requested;
        double low=terminal ? .10 : .18;
        double cap=low+(1-low)*Math.pow(distance/braking,2);
        // Higher measured step displacement warrants earlier deceleration,
        // without inventing a successful receipt or reversing input.
        if(actualStep>.15 && distance<.65) cap=Math.min(cap,.18);
        return Math.min(requested,cap);
    }
    /** Used for diagnostics and safe intermediate waypoint verification:
     * only the actually observed short physical move may cross a waypoint. */
    static boolean crossed(double previousX,double previousZ,double x,double z,
            double targetX,double targetZ,double radius) {
        if(!Double.isFinite(previousX)||!Double.isFinite(previousZ)||!Double.isFinite(x)
                ||!Double.isFinite(z)||!Double.isFinite(targetX)||!Double.isFinite(targetZ)
                ||!Double.isFinite(radius)||radius<=0) return false;
        double dx=x-previousX,dz=z-previousZ,travel=dx*dx+dz*dz;
        if(travel<1e-8 || travel>.49) return false;
        double t=((targetX-previousX)*dx+(targetZ-previousZ)*dz)/travel;
        return t>0 && t<1 && Math.hypot(previousX+t*dx-targetX,
                previousZ+t*dz-targetZ)<=radius;
    }
}
