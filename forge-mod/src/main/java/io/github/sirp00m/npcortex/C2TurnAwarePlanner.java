package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Follow-only bounded A* over (standing node, incoming heading). A physical
 * directed B1 edge is mandatory for every move; turn preference can NEVER
 * override collision, unloaded-chunk or environmental-risk costs. The static
 * C2 operator/hierarchical planner remains unchanged.
 */
final class C2TurnAwarePlanner {
    private C2TurnAwarePlanner() { }
    static final double TURN_WEIGHT = 0.22;
    private static final double EPS = 1e-9;

    record State(C2TraversabilityGraph.Node node, int dx, int dz) { }
    record Work(State state, double g, double h, long order) { }
    private static final Comparator<Work> ORDER = Comparator
            .comparingDouble((Work w) -> w.g()+w.h())
            .thenComparingDouble(Work::h)
            .thenComparingDouble(Work::g)
            .thenComparingLong(Work::order);

    static C2HierarchicalPlanner.Result plan(C2TraversabilityGraph graph,
            C2NavGrid.Snapshot snapshot, C2AStarPlanner.Target start,
            C2AStarPlanner.Target goal, int budget) {
        Objects.requireNonNull(graph); Objects.requireNonNull(snapshot);
        Objects.requireNonNull(start); Objects.requireNonNull(goal);
        if (budget < 1 || budget > C2FollowPolicy.PER_PLAN_BUDGET)
            throw new IllegalArgumentException("Turn-aware expansion budget out of range");
        // Trust only a graph consistent with its bounded terrain snapshot.
        Map<C2AStarPlanner.Target, Double> cells = new HashMap<>();
        for (var c : snapshot.cells()) if (C2NavGrid.plannable(c.condition())) {
            var id=new C2AStarPlanner.Target(c.x(),c.supportY(),c.z());
            if (cells.putIfAbsent(id,c.footY())!=null)
                throw new IllegalArgumentException("Duplicate terrain observation");
        }
        C2TraversabilityGraph.Node from=null,to=null;
        for (var n:graph.nodes()) {
            var id=new C2AStarPlanner.Target(n.x(),n.supportY(),n.z());
            Double foot=cells.remove(id);
            if (foot==null || Double.compare(foot,n.footY())!=0)
                throw new IllegalArgumentException("Graph/snapshot mismatch");
            if (id.equals(start)) from=n;
            if (id.equals(goal)) to=n;
        }
        if (!cells.isEmpty()) throw new IllegalArgumentException("Incomplete traversal graph");
        if (!inside(snapshot,start) || from==null)
            return fail(C2HierarchicalPlanner.Status.INVALID_START,0,0,budget);
        if (!inside(snapshot,goal))
            return fail(C2HierarchicalPlanner.Status.OUT_OF_SCAN,0,0,budget);
        if (to==null) return fail(C2HierarchicalPlanner.Status.UNSAFE_TARGET,0,0,budget);
        if (from.equals(to)) return new C2HierarchicalPlanner.Result(
                C2HierarchicalPlanner.Status.PATH_FOUND,C2HierarchicalPlanner.Source.FOLLOW_TURN_AWARE,
                List.of(from),0,0,1,budget,0,0);

        // Every heuristic is a lower bound on physical edge cost, ignoring
        // non-negative turn penalties. Dijkstra fallback for vertical edges.
        double minimum=Double.POSITIVE_INFINITY;
        for(var edge:graph.edges()) {
            double horizontal=Math.hypot((long)edge.to().x()-edge.from().x(),
                    (long)edge.to().z()-edge.from().z());
            if(horizontal>0) minimum=Math.min(minimum,edge.cost()/horizontal);
        }
        final double lower=Double.isFinite(minimum) ? Math.nextDown(minimum) : 0;
        State origin=new State(from,0,0);
        Map<State,Double> best=new HashMap<>();
        Map<State,State> previous=new HashMap<>();
        PriorityQueue<Work> open=new PriorityQueue<>(ORDER);
        long order=0;
        best.put(origin,0.0);
        open.add(new Work(origin,0,estimate(from,to,lower),order++));
        int expanded=0;
        while(!open.isEmpty()) {
            Work current=open.poll();
            if (current.g()>best.getOrDefault(current.state(),Double.POSITIVE_INFINITY)+EPS) continue;
            if (current.state().node().equals(to)) {
                List<C2TraversabilityGraph.Node> reverse=new ArrayList<>();
                State walk=current.state();
                while(walk!=null) {
                    reverse.add(walk.node());
                    if(reverse.size()>budget+1) throw new IllegalStateException("Cyclic follow parent chain");
                    walk=previous.get(walk);
                }
                Collections.reverse(reverse);
                return new C2HierarchicalPlanner.Result(C2HierarchicalPlanner.Status.PATH_FOUND,
                        C2HierarchicalPlanner.Source.FOLLOW_TURN_AWARE,reverse,current.g(),
                        expanded,best.size(),budget,0,0);
            }
            if (expanded>=budget) return fail(C2HierarchicalPlanner.Status.BUDGET_EXCEEDED,
                    expanded,best.size(),budget);
            expanded++;
            for(var edge:graph.neighbors(current.state().node())) {
                if(!edge.from().equals(current.state().node()) || !graph.contains(edge.to())
                        || edge.cost()<=0 || !Double.isFinite(edge.cost()))
                    throw new IllegalArgumentException("Invalid directed graph edge");
                int dx=Integer.compare(edge.to().x(),edge.from().x());
                int dz=Integer.compare(edge.to().z(),edge.from().z());
                State next=new State(edge.to(),dx,dz);
                double cost=current.g()+edge.cost()+turnCost(current.state().dx(),current.state().dz(),dx,dz);
                if (!Double.isFinite(cost)) throw new IllegalArgumentException("Non-finite follow cost");
                if(cost+EPS<best.getOrDefault(next,Double.POSITIVE_INFINITY)) {
                    best.put(next,cost);previous.put(next,current.state());
                    open.add(new Work(next,cost,estimate(edge.to(),to,lower),order++));
                }
            }
        }
        return fail(C2HierarchicalPlanner.Status.NO_PATH,expanded,best.size(),budget);
    }

    static double turnCost(int ax,int az,int bx,int bz) {
        if((ax==0&&az==0)||(bx==0&&bz==0)) return 0;
        double dot=(double)ax*bx+(double)az*bz;
        double cosine=dot/(Math.hypot(ax,az)*Math.hypot(bx,bz));
        return TURN_WEIGHT*(1-Math.max(-1,Math.min(1,cosine)));
    }
    private static double estimate(C2TraversabilityGraph.Node n,C2TraversabilityGraph.Node goal,double lower) {
        return Math.hypot((long)n.x()-goal.x(),(long)n.z()-goal.z())*lower;
    }
    private static boolean inside(C2NavGrid.Snapshot scan,C2AStarPlanner.Target point) {
        return Math.abs((long)point.x()-scan.originX())<=scan.radius()
                && Math.abs((long)point.z()-scan.originZ())<=scan.radius()
                && Math.abs((long)point.supportY()-scan.originY())<=C2NavGrid.VERTICAL_RADIUS;
    }
    private static C2HierarchicalPlanner.Result fail(C2HierarchicalPlanner.Status status,
            int expanded,int visited,int budget) {
        return new C2HierarchicalPlanner.Result(status,C2HierarchicalPlanner.Source.NONE,
                List.of(),0,expanded,visited,budget,0,0);
    }
}
