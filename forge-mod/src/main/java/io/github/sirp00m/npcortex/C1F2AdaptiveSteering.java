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
        double preview=Math.min(1,t+LOOKAHEAD/Math.sqrt(len2));
        return new Aim(startX+preview*dx,startZ+preview*dz);
    }
}
