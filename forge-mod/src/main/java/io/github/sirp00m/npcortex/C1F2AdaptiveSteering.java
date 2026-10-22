package io.github.sirp00m.npcortex;

/** H3: bounded, continuous 360-degree steering on an ALREADY approved flat
 * segment. Produces only presentation/forward-input guidance; it cannot
 * select an unverified new route or modify a C1 ticket. Never blends across
 * a corner (that chord would require a separate C2 corridor certificate).
 */
final class C1F2AdaptiveSteering {
    private C1F2AdaptiveSteering() {}
    static final double LOOKAHEAD = .85;
    static final double MAX_LATERAL = .42;
    // A small position error is not a steering error: correcting it on every
    // tick makes a straight certified segment wobble left and right.
    static final double LATERAL_DEADBAND = .12;
    static final double CORRECTION_GAIN = .38;
    record Aim(double x,double z) {}
    static Aim onSegment(double startX,double startZ,double endX,double endZ,
                         double actorX,double actorZ) {
        if(!Double.isFinite(startX)||!Double.isFinite(startZ)
                ||!Double.isFinite(endX)||!Double.isFinite(endZ)
                ||!Double.isFinite(actorX)||!Double.isFinite(actorZ))
            throw new IllegalArgumentException("Non-finite adaptive steering input");
        double dx=endX-startX,dz=endZ-startZ,len2=dx*dx+dz*dz;
        if(len2<.0009) return new Aim(endX,endZ);
        double t=Math.max(0,Math.min(1,((actorX-startX)*dx+(actorZ-startZ)*dz)/len2));
        double nearestX=startX+t*dx,nearestZ=startZ+t*dz;
        // Outside the certified nominal lane? Do not invent a new target.
        // Let vanilla-style heading and per-tick swept checks recover safely.
        if(Math.hypot(actorX-nearestX,actorZ-nearestZ)>MAX_LATERAL)
            return new Aim(endX,endZ);
        double len=Math.sqrt(len2),ux=dx/len,uz=dz/len;
        double along=(actorX-startX)*ux+(actorZ-startZ)*uz;
        double remaining=len-along;
        // Once past the endpoint it must be physically reacquired: never
        // extrapolate a fake waypoint receipt or steer past an unsafe corner.
        if(remaining<=LOOKAHEAD) return new Aim(endX,endZ);
        // Tiny cross-track displacement is tolerated until the endpoint.
        // Look AHEAD in the current direction, not sideways at a block center.
        double offsetX=actorX-nearestX,offsetZ=actorZ-nearestZ;
        double lateral=Math.hypot(offsetX,offsetZ);
        double correction=lateral<=LATERAL_DEADBAND ? 0
                : CORRECTION_GAIN*(lateral-LATERAL_DEADBAND)/lateral;
        double preview=Math.min(LOOKAHEAD,remaining);
        return new Aim(actorX+ux*preview-offsetX*correction,
                actorZ+uz*preview-offsetZ*correction);
    }
}
