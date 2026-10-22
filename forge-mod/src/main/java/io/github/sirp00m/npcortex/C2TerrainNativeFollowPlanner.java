package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Terrain-native, follow-only selection over the SAME fresh directed 3D graph
 * used to authorize C1 movement. Finds reachable follow-ring / rolling goals
 * in ONE bounded graph search, instead of ranking Euclidean goals before
 * learning whether their paths are reachable. No world reads, new physics,
 * teleport, chunk loading or speculative 3D edges here.
 *
 * Search state includes entry heading so turn penalties cannot accidentally
 * overwrite a cheaper arrival with a different subsequent turn cost.
 */
final class C2TerrainNativeFollowPlanner {
    private C2TerrainNativeFollowPlanner() { }
    private static final int MAX_EXPANDED = 3000;
    private static final int MAX_PATH_NODES = C2WaypointExecution.MAX_WAYPOINTS;
    private static final double EPS = 1.0e-8;
    private static final double TURN_PENALTY = .11;
    private static final double FAILED_NODE_PENALTY = 7.0;
    private static final double HEIGHT_PENALTY = .22;
    /** Bounded lateral exploration may precede actual progress around a ravine.
     * Session-level guard (not this pure planner) caps repeated detours. */
    static final int MAX_LATERAL_DETOURS = 6;

    record Selection(C2HierarchicalPlanner.Result plan, int expanded, String reason, int detourSide) {
        Selection(C2HierarchicalPlanner.Result plan,int expanded,String reason) {
            this(plan,expanded,reason,0);
        }
        boolean found() { return plan != null && plan.found(); }
    }
    private record State(C2TraversabilityGraph.Node node, int heading) { }
    private record QueueEntry(State state, double cost, long sequence) { }
    private static final Comparator<QueueEntry> ORDER=Comparator
            .comparingDouble(QueueEntry::cost).thenComparingLong(QueueEntry::sequence);

    static Selection select(C2TraversabilityGraph graph, C2AStarPlanner.Target from,
            double actorX,double actorY,double actorZ,
            double playerX,double playerY,double playerZ,
            Set<C2TraversabilityGraph.Node> avoided, C2TraversabilityGraph.Node preferred) {
        return select(graph,from,actorX,actorY,actorZ,playerX,playerY,playerZ,
                avoided,preferred,0,true);
    }
    static Selection select(C2TraversabilityGraph graph, C2AStarPlanner.Target from,
            double actorX,double actorY,double actorZ,
            double playerX,double playerY,double playerZ,
            Set<C2TraversabilityGraph.Node> avoided,C2TraversabilityGraph.Node preferred,
            int preferredDetourSide,boolean allowDetour) {
        if(preferredDetourSide<-1 || preferredDetourSide>1)
            throw new IllegalArgumentException("Invalid detour side");
        Objects.requireNonNull(graph);Objects.requireNonNull(from);Objects.requireNonNull(avoided);
        if(!Double.isFinite(actorX) || !Double.isFinite(actorY) || !Double.isFinite(actorZ)
                || !Double.isFinite(playerX) || !Double.isFinite(playerY) || !Double.isFinite(playerZ))
            throw new IllegalArgumentException("Invalid terrain-native Follow coordinates");
        C2TraversabilityGraph.Node root=null;
        for(var node:graph.nodes()) if(node.x()==from.x() && node.z()==from.z()
                && node.supportY()==from.supportY()) {root=node;break;}
        if(root==null) return new Selection(null,0,"NO_VERIFIED_ACTOR_SUPPORT_NODE");
        final double oldDistance=Math.hypot(playerX-actorX,playerZ-actorZ);
        final boolean rolling=oldDistance>C2FollowPolicy.ROLLING_DISTANCE;
        // Physical stop/ring nodes are not 'failed' merely because an earlier
        // attempt from a different posture was blocked. Do not oscillate away.
        if(!rolling && ring(root,playerX,playerY,playerZ) && !avoided.contains(root))
            return new Selection(result(List.of(root),0,0,1),0,"ALREADY_SAFE_FOLLOW_RING");

        State start=new State(root,-1);
        Map<State,Double> costs=new HashMap<>();
        Map<State,State> previous=new HashMap<>();
        PriorityQueue<QueueEntry> pending=new PriorityQueue<>(ORDER);
        Set<State> settled=new HashSet<>();
        costs.put(start,0.0);pending.add(new QueueEntry(start,0,0));
        double bestScore=Double.POSITIVE_INFINITY;
        State best=null;
        State lateralBest=null;
        double lateralBestScore=Double.POSITIVE_INFINITY;
        int lateralSide=0;
        int expanded=0;
        long seq=1;
        final double ux=oldDistance>EPS?(playerX-actorX)/oldDistance:0;
        final double uz=oldDistance>EPS?(playerZ-actorZ)/oldDistance:0;
        while(!pending.isEmpty() && expanded<MAX_EXPANDED) {
            var entry=pending.remove();var state=entry.state();
            if(entry.cost()>costs.getOrDefault(state,Double.POSITIVE_INFINITY)+EPS
                    || !settled.add(state)) continue;
            expanded++;
            var node=state.node();
            if(!node.equals(root) && (rolling
                    ? rollingCandidate(node,actorX,actorY,actorZ,playerX,playerY,playerZ,oldDistance,ux,uz)
                    : ring(node,playerX,playerY,playerZ))) {
                double lateral=Math.abs((node.x()+.5-actorX)*uz-(node.z()+.5-actorZ)*ux);
                double progress=(node.x()+.5-actorX)*ux+(node.z()+.5-actorZ)*uz;
                double targetDistance=Math.hypot(node.x()+.5-playerX,node.z()+.5-playerZ);
                // Rolling nodes favor centerline and forward progress, but
                // not at the expense of a long, hazardous or repeatedly
                // failed detour. Ring nodes minimize actual 3D travel cost.
                double score=rolling
                        ? entry.cost()*.6 + lateral*1.5 - progress*.7
                              +Math.abs(node.footY()-playerY)*HEIGHT_PENALTY
                        : entry.cost() + Math.abs(targetDistance-2.6)*.7;
                if(preferred!=null && preferred.equals(node)) score-=1.25;
                if(score<bestScore-EPS) {best=state;bestScore=score;}
            }
            // If every reachable candidate stays on the wrong side of an
            // obstacle, consider a bounded *sideways* corridor rather than
            // immediately claiming NO_PATH. Sideways detours have a separate
            // finite session budget, and still consist ONLY of verified edges.
            if(allowDetour && !node.equals(root) && oldDistance>C2FollowPolicy.STOP_DISTANCE
                    && Math.abs(node.footY()-actorY)<=C2NavGrid.VERTICAL_RADIUS+1.0) {
                double dx=node.x()+.5-actorX,dz=node.z()+.5-actorZ;
                double forward=dx*ux+dz*uz;
                double lateral=dx*uz-dz*ux;
                double absLateral=Math.abs(lateral),walk=Math.hypot(dx,dz);
                if(absLateral>=2.0 && absLateral<=5.8 && walk>=2.5 && walk<=5.8
                        && forward>=-1.5 && forward<=2.5) {
                    int side=lateral>0?1:-1;
                    double sidePenalty=preferredDetourSide!=0 && side!=preferredDetourSide?5.0:0.0;
                    double score=entry.cost()*.45 + Math.abs(absLateral-4.0)*.25
                            -forward*.12 + sidePenalty
                            +Math.abs(node.footY()-actorY)*HEIGHT_PENALTY;
                    if(score<lateralBestScore-EPS) {
                        lateralBest=state;lateralBestScore=score;lateralSide=side;
                    }
                }
            }
            for(var edge:graph.neighbors(node)) {
                if(!edge.from().equals(node) || !Double.isFinite(edge.cost()) || edge.cost()<=0) continue;
                int heading=heading(edge.from(),edge.to());
                // Failed-node memory changes preference, not passability: the
                // fresh graph alone certifies whether this edge may be used.
                double turn=state.heading()<0 ? 0 : angularSteps(state.heading(),heading)*TURN_PENALTY;
                double penalty=avoided.contains(edge.to())?FAILED_NODE_PENALTY:0;
                double nextCost=entry.cost()+edge.cost()+turn+penalty;
                State next=new State(edge.to(),heading);
                if(nextCost+EPS<costs.getOrDefault(next,Double.POSITIVE_INFINITY)) {
                    costs.put(next,nextCost);previous.put(next,state);
                    pending.add(new QueueEntry(next,nextCost,seq++));
                }
            }
        }
        boolean detour=false;
        if(best==null && lateralBest!=null && allowDetour) {
            best=lateralBest;detour=true;
        }
        if(best==null) return new Selection(null,expanded,expanded>=MAX_EXPANDED
                ? "TERRAIN_SEARCH_BUDGET_EXHAUSTED" : rolling
                ? "NO_REACHABLE_SAFE_3D_FRONTIER" : "NO_REACHABLE_SAFE_3D_FOLLOW_RING");
        ArrayList<C2TraversabilityGraph.Node> reverse=new ArrayList<>();
        for(State at=best;at!=null;at=previous.get(at)) {
            reverse.add(at.node());
            if(reverse.size()>MAX_PATH_NODES) return new Selection(null,expanded,"TERRAIN_ROUTE_TOO_LONG");
        }
        java.util.Collections.reverse(reverse);
        if(reverse.isEmpty() || !reverse.get(0).equals(root))
            return new Selection(null,expanded,"TERRAIN_ROUTE_DISCONNECTED");
        return new Selection(result(reverse,costs.get(best),expanded,settled.size()),expanded,
                detour?"VERIFIED_BOUNDED_LATERAL_DETOUR":rolling?"VERIFIED_3D_ROLLING_CORRIDOR"
                        :"VERIFIED_3D_FOLLOW_RING",detour?lateralSide:0);
    }

    private static boolean ring(C2TraversabilityGraph.Node n,double x,double y,double z) {
        double d=Math.hypot(n.x()+.5-x,n.z()+.5-z);
        return d>=1.8 && d<=3.35 && Math.abs(n.footY()-y)<=1.1;
    }
    private static boolean rollingCandidate(C2TraversabilityGraph.Node n,
            double ax,double ay,double az,double px,double py,double pz,
            double oldDistance,double ux,double uz) {
        double dx=n.x()+.5-ax,dz=n.z()+.5-az;
        double progress=dx*ux+dz*uz;
        double distance=Math.hypot(dx,dz);
        double remaining=Math.hypot(px-(n.x()+.5),pz-(n.z()+.5));
        return progress>=1.0 && distance>=1.0 && distance<=5.8
                && remaining<=oldDistance-.7
                && Math.abs(n.footY()-ay)<=C2NavGrid.VERTICAL_RADIUS+1.0
                && Math.abs(n.footY()-py)<=C2NavGrid.VERTICAL_RADIUS+2.0;
    }
    private static int heading(C2TraversabilityGraph.Node from,C2TraversabilityGraph.Node to) {
        int x=Integer.compare(to.x(),from.x()),z=Integer.compare(to.z(),from.z());
        if(x==0 && z==0) throw new IllegalArgumentException("No movement bearing");
        return (int)Math.round(Math.atan2(z,x)*4/Math.PI + 8)%8;
    }
    private static int angularSteps(int a,int b) {
        int delta=Math.abs(a-b);return Math.min(delta,8-delta);
    }
    private static C2HierarchicalPlanner.Result result(List<C2TraversabilityGraph.Node> path,
            double cost,int expanded,int visited) {
        return new C2HierarchicalPlanner.Result(C2HierarchicalPlanner.Status.PATH_FOUND,
                C2HierarchicalPlanner.Source.FOLLOW_TERRAIN_NATIVE,path,
                cost,expanded,visited,MAX_EXPANDED,0,0);
    }
}
