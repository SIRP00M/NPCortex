package io.github.sirp00m.npcortex;

import java.util.LinkedHashSet;
import java.util.Set;

/** Follow-only, per-session bounded route hysteresis and failed-edge memory.
 * Read-only hints to a FRESH verified planner. No world reads and no rights
 * to start/stop/extend a C1 ticket. Not persisted across restarts. */
final class C2FollowAlternatives {
    private static final int MAX_AVOIDED_NODES=8;
    private final LinkedHashSet<C2TraversabilityGraph.Node> avoided=new LinkedHashSet<>();
    private C2TraversabilityGraph.Node preferred;

    Set<C2TraversabilityGraph.Node> avoided() { return Set.copyOf(avoided); }
    int avoidedCount() { return avoided.size(); }
    C2TraversabilityGraph.Node preferred() { return preferred; }

    void planned(C2WaypointExecution route) {
        if(route==null || route.path().isEmpty()) return;
        preferred=route.path().get(route.path().size()-1);
    }
    void failed(C2WaypointExecution route) {
        if(route==null || route.path().size()<2) return;
        // Block both the first route edge and its terminal candidate to avoid
        // replaying the identical turn after a verified collision/stall.
        avoid(route.path().get(route.currentIndex()));
        avoid(route.path().get(route.path().size()-1));
    }
    private void avoid(C2TraversabilityGraph.Node node) {
        avoided.remove(node);
        avoided.add(node);
        while(avoided.size()>MAX_AVOIDED_NODES) avoided.remove(avoided.iterator().next());
    }
    void verifiedProgress() { avoided.clear(); }
    void targetRelocated() { avoided.clear(); preferred=null; }
    /** Keep a recently verified follow-heading preference while the player
     * keeps moving ahead. A relocated target must still get a FRESH 3D plan:
     * this only biases scoring, never resurrects a route or bypasses a graph. */
    void targetRelocated(double actorX,double actorZ,double playerX,double playerZ) {
        avoided.clear();
        if(preferred==null) return;
        double fx=playerX-actorX,fz=playerZ-actorZ;
        double px=preferred.x()+.5-actorX,pz=preferred.z()+.5-actorZ;
        double forward=fx*px+fz*pz;
        double distance=Math.hypot(px,pz);
        double projection=Math.hypot(fx,fz);
        // No preference behind the actor, past the newly reached player,
        // or beyond the radius of the local follow scan.
        if(projection<1e-6 || forward<=0 || distance<.9 || distance>5.8
                || forward/projection<.4) preferred=null;
    }
}
