package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayDeque;

/** Pure-Java, bounded follow-ring planning on a fresh local 3D graph.
 * The target is never used as a waypoint: NPCs seek a safe nearby standing
 * surface rather than colliding with a moving player. NO chunk access here.
 */
final class C2FollowPlanner {
    private C2FollowPlanner() { }
    record Selection(C2HierarchicalPlanner.Result plan, int checked, String reason) {
        boolean found() { return plan!=null && plan.found(); }
    }
    /** Reachability uses the ALREADY validated, bounded directed graph. This is
     * not a second world probe and cannot infer that a blocked edge is safe.
     * It prevents unreachable high-ranked ring/frontier nodes from consuming
     * all four expensive hierarchical searches. */
    private static Set<C2TraversabilityGraph.Node> reachable(C2TraversabilityGraph graph,
                                                              C2AStarPlanner.Target from) {
        var seen=new HashSet<C2TraversabilityGraph.Node>();
        var queue=new ArrayDeque<C2TraversabilityGraph.Node>();
        for(var node:graph.nodes()) if(node.x()==from.x() && node.supportY()==from.supportY()
                && node.z()==from.z()) {seen.add(node);queue.add(node);break;}
        while(!queue.isEmpty()) for(var edge:graph.neighbors(queue.removeFirst()))
            if(seen.add(edge.to())) queue.addLast(edge.to());
        return seen;
    }
    private static boolean allowed(C2HierarchicalPlanner.Result path,
                                   Set<C2TraversabilityGraph.Node> avoid) {
        return path.found() && !path.path().isEmpty()
                && (avoid==null || (!avoid.contains(path.path().get(path.path().size()-1))
                && (path.path().size()==1 || !avoid.contains(path.path().get(1)))));
    }
    static Selection selectRolling(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                            C2AStarPlanner.Target from, double actorX,double actorY,double actorZ,
                            double playerX,double playerY,double playerZ) {
        return selectRolling(graph,snapshot,from,actorX,actorY,actorZ,playerX,playerY,playerZ,
                Set.of(),null);
    }
    static Selection selectRolling(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                            C2AStarPlanner.Target from, double actorX,double actorY,double actorZ,
                            double playerX,double playerY,double playerZ,
                            Set<C2TraversabilityGraph.Node> avoid) {
        return selectRolling(graph,snapshot,from,actorX,actorY,actorZ,
                playerX,playerY,playerZ,avoid,null);
    }
    static Selection selectRolling(C2TraversabilityGraph graph,C2NavGrid.Snapshot snapshot,
            C2AStarPlanner.Target from,double actorX,double actorY,double actorZ,
            double playerX,double playerY,double playerZ,
            Set<C2TraversabilityGraph.Node> avoid,C2TraversabilityGraph.Node preferred) {
        if(graph==null || snapshot==null || from==null || avoid==null || !Double.isFinite(actorX)
                || !Double.isFinite(actorY) || !Double.isFinite(actorZ)
                || !Double.isFinite(playerX) || !Double.isFinite(playerY)
                || !Double.isFinite(playerZ))
            throw new IllegalArgumentException("Invalid rolling follow request");
        double oldDistance=Math.hypot(playerX-actorX,playerZ-actorZ);
        if(oldDistance<=C2FollowPolicy.ROLLING_DISTANCE)
            return select(graph,snapshot,from,actorX,actorZ,playerX,playerY,playerZ,
                    avoid,null);
        double ux=(playerX-actorX)/oldDistance,uz=(playerZ-actorZ)/oldDistance;
        List<C2TraversabilityGraph.Node> candidates=new ArrayList<>();
        for(var node:graph.nodes()) {
            double dx=node.x()+.5-actorX,dz=node.z()+.5-actorZ;
            double progress=dx*ux+dz*uz;
            double distance=Math.hypot(dx,dz);
            double nextDistance=Math.hypot(playerX-(node.x()+.5),playerZ-(node.z()+.5));
            if(progress>=2.0 && distance>=2.5 && distance<=5.8
                    && nextDistance<=oldDistance-1.0
                    && Math.abs(node.footY()-actorY)<=C2NavGrid.VERTICAL_RADIUS+1.0)
                candidates.add(node);
        }
        // H1: prioritize a stable centerline and useful progress together.
        // Unnecessary far-side/left-right switches are costly; keep at most
        // the original four expensive A* checks, never replan each C1 tick.
        candidates.sort(Comparator.<C2TraversabilityGraph.Node>comparingDouble(n->
                    Math.abs((n.x()+.5-actorX)*uz-(n.z()+.5-actorZ)*ux)*1.8
                    -((n.x()+.5-actorX)*ux+(n.z()+.5-actorZ)*uz)*.4
                    -(preferred!=null && preferred.equals(n)?1.1:0))
                .thenComparingDouble(n->Math.abs((n.x()+.5-actorX)*uz-(n.z()+.5-actorZ)*ux))
                .thenComparingInt(C2TraversabilityGraph.Node::x)
                .thenComparingInt(C2TraversabilityGraph.Node::z));
        Set<C2TraversabilityGraph.Node> connected=reachable(graph,from);
        int checked=0;
        C2HierarchicalPlanner.Result best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        for(var node:candidates) {
            if(!connected.contains(node) || avoid.contains(node)) continue;
            if(checked>=C2FollowPolicy.MAX_CANDIDATE_PLANS) break;
            checked++;
            var goal=new C2AStarPlanner.Target(node.x(),node.supportY(),node.z());
            var result=C2TurnAwarePlanner.plan(graph,snapshot,from,goal,C2FollowPolicy.PER_PLAN_BUDGET);
            if(result.path().size()>1 && allowed(result,avoid)) {
                double lateral=Math.abs((node.x()+.5-actorX)*uz-(node.z()+.5-actorZ)*ux);
                double progress=(node.x()+.5-actorX)*ux+(node.z()+.5-actorZ)*uz;
                double score=result.cost()*.12 + lateral*1.5-progress*.36
                        -(preferred!=null && preferred.equals(node)?1.1:0);
                if(score<bestScore) { bestScore=score;best=result; }
            }
        }
        if(best!=null) return new Selection(best,checked,"ROLLING_CENTERLINE_PATH_FOUND");
        return new Selection(null,checked,candidates.isEmpty()
                ? "NO_SAFE_ROLLING_FRONTIER" : "ROLLING_NO_REACHABLE_ALTERNATIVE");
    }

    static Selection select(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                            C2AStarPlanner.Target from, double actorX,double actorZ,
                            double playerX,double playerY,double playerZ) {
        return select(graph,snapshot,from,actorX,actorZ,playerX,playerY,playerZ,Set.of(),null);
    }
    static Selection select(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                            C2AStarPlanner.Target from, double actorX,double actorZ,
                            double playerX,double playerY,double playerZ,
                            Set<C2TraversabilityGraph.Node> avoid,
                            C2TraversabilityGraph.Node preferred) {
        if(avoid==null || graph==null || snapshot==null || from==null || !Double.isFinite(actorX)
                || !Double.isFinite(actorZ) || !Double.isFinite(playerX)
                || !Double.isFinite(playerY) || !Double.isFinite(playerZ))
            throw new IllegalArgumentException("Invalid follow planning snapshot/target");
        // Standing on an approved ring node is already a successful local
        // placement: do not wander away just because another ring node has
        // a marginally better numeric distance score.
        for(var node:graph.nodes()) if(node.x()==from.x() && node.z()==from.z()
                && node.supportY()==from.supportY() && !avoid.contains(node)
                && Math.abs(node.footY()-playerY)<=1.1) {
            double d=Math.hypot(node.x()+.5-playerX,node.z()+.5-playerZ);
            if(d>=1.8 && d<=3.35) {
                var stay=C2HierarchicalPlanner.plan(graph,snapshot,from,from,
                        C2RegionGraph.DEFAULT_TILE_SIZE,C2FollowPolicy.PER_PLAN_BUDGET);
                if(stay.found()) return new Selection(stay,1,"ALREADY_SAFE_FOLLOW_RING");
            }
            break;
        }
        List<C2TraversabilityGraph.Node> candidates=new ArrayList<>();
        for(var node:graph.nodes()) {
            double d=Math.hypot(node.x()+0.5-playerX,node.z()+0.5-playerZ);
            if(d>=1.8 && d<=3.35 && Math.abs(node.footY()-playerY)<=1.1)
                candidates.add(node);
        }
        candidates.sort(Comparator.comparingDouble((C2TraversabilityGraph.Node n)->
                    Math.abs(Math.hypot(n.x()+0.5-playerX,n.z()+0.5-playerZ)-2.6)
                    - (preferred!=null && preferred.equals(n) ? .55 : 0))
                .thenComparingDouble(n->Math.hypot(n.x()+0.5-actorX,n.z()+0.5-actorZ))
                .thenComparingInt(C2TraversabilityGraph.Node::x)
                .thenComparingInt(C2TraversabilityGraph.Node::z));
        Set<C2TraversabilityGraph.Node> connected=reachable(graph,from);
        C2HierarchicalPlanner.Result best=null;
        int checked=0;
        for(var node:candidates) {
            if(!connected.contains(node) || avoid.contains(node)) continue;
            if(checked>=C2FollowPolicy.MAX_CANDIDATE_PLANS) break;
            checked++;
            var goal=new C2AStarPlanner.Target(node.x(),node.supportY(),node.z());
            var result=C2TurnAwarePlanner.plan(graph,snapshot,from,goal,C2FollowPolicy.PER_PLAN_BUDGET);
            if(allowed(result,avoid) && (best==null || result.cost()<best.cost()
                    || (preferred!=null && result.path().get(result.path().size()-1).equals(preferred)
                        && result.cost()<=best.cost()+.8))) best=result;
        }
        return new Selection(best,checked,best!=null ? "PATH_FOUND" :
                candidates.isEmpty() ? "NO_SAFE_FOLLOW_RING" : "NO_REACHABLE_FOLLOW_ALTERNATIVE");
    }
}
