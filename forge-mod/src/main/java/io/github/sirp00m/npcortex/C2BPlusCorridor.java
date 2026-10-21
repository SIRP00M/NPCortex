package io.github.sirp00m.npcortex;

import java.util.HashMap;
import java.util.Map;

/** H2: finite-width, player-footprint certificate for a FLAT Follow-only chord.
 * The certificate is a fresh, directed, risk-free local graph; the Forge bridge
 * must ALSO check current world collision/hazards immediately before committing.
 * A gap, unknown cell, expensive edge, height transition or graph corner cut
 * fails closed. This is not a general physics/navigation override.
 */
final class C2BPlusCorridor {
    private C2BPlusCorridor() {}
    static final double HALF_WIDTH = .32; // vanilla standing player half width + margin
    static final double STEP = .16;
    private static final double EPS = 1e-4;
    static boolean clear(C2TraversabilityGraph graph,
                         C2TraversabilityGraph.Node from,C2TraversabilityGraph.Node to) {
        if(graph==null || from==null || to==null || from.equals(to)
                || !graph.contains(from) || !graph.contains(to)
                || from.supportY()!=to.supportY() || Math.abs(from.footY()-to.footY())>EPS)
            return false;
        double dx=to.x()-from.x(), dz=to.z()-from.z();
        if(Math.max(Math.abs(dx),Math.abs(dz))>C2WaypointExecution.FOLLOW_MAX_RUN)
            return false;
        double distance=Math.hypot(dx,dz);
        int steps=(int)Math.ceil(distance/STEP);
        if(steps<1 || steps>48) return false;
        Map<Long,C2TraversabilityGraph.Node> cells=new HashMap<>();
        int minX=Math.min(from.x(),to.x())-1,maxX=Math.max(from.x(),to.x())+1;
        int minZ=Math.min(from.z(),to.z())-1,maxZ=Math.max(from.z(),to.z())+1;
        for(var n:graph.nodes()) if(n.x()>=minX && n.x()<=maxX && n.z()>=minZ && n.z()<=maxZ
                && n.supportY()==from.supportY() && Math.abs(n.footY()-from.footY())<=EPS)
            cells.put(key(n.x(),n.z()),n);
        C2TraversabilityGraph.Node previous=from;
        for(int i=0;i<=steps;i++) {
            double t=(double)i/steps, x=from.x()+.5+dx*t,z=from.z()+.5+dz*t;
            // All columns touched by the player's horizontal collision
            // footprint must have a compatible support. This is stricter
            // than sampling only the point path, but not the entire rectangle.
            for(double ox:new double[]{-HALF_WIDTH,HALF_WIDTH})
                for(double oz:new double[]{-HALF_WIDTH,HALF_WIDTH}) {
                    var n=cells.get(key((int)Math.floor(x+ox),(int)Math.floor(z+oz)));
                    if(n==null || !safeCell(graph,n)) return false;
                }
            var here=cells.get(key((int)Math.floor(x),(int)Math.floor(z)));
            if(here==null || !safeCell(graph,here)) return false;
            if(!here.equals(previous) && !safeStep(graph,previous,here)) return false;
            previous=here;
        }
        return previous.equals(to);
    }
    private static boolean safeCell(C2TraversabilityGraph graph,C2TraversabilityGraph.Node node) {
        // A risky node's incoming/outgoing graph WALK costs include its
        // terrain and body hazard. Require a verified low-cost neighbor.
        for(var e:graph.neighbors(node)) if(e.kind()==C2TraversabilityGraph.MoveKind.WALK
                && e.cost()<=Math.hypot(e.to().x()-node.x(),e.to().z()-node.z())+EPS)
            return true;
        return false;
    }
    private static boolean safeStep(C2TraversabilityGraph graph,
                                    C2TraversabilityGraph.Node a,C2TraversabilityGraph.Node b) {
        return safeEdge(graph,a,b) && safeEdge(graph,b,a);
    }
    private static boolean safeEdge(C2TraversabilityGraph graph,
                                    C2TraversabilityGraph.Node a,C2TraversabilityGraph.Node b) {
        double length=Math.hypot(a.x()-b.x(),a.z()-b.z());
        return graph.neighbors(a).stream().anyMatch(e -> e.to().equals(b)
                && e.kind()==C2TraversabilityGraph.MoveKind.WALK && e.cost()<=length+EPS);
    }
    private static long key(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}
}
