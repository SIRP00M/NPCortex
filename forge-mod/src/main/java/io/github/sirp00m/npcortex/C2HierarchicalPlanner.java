package io.github.sirp00m.npcortex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * C2-P1-B3: bounded exact portal-overlay hierarchy, pure Java and READ-ONLY.
 * Every portal comes from a B1 edge; each intra-region arc is an actual local
 * shortest path. Retaining all portal endpoints avoids falsely constraining
 * the path to one approximate region route. An independent B2 reference must
 * agree on cost, or its verified path is used as a declared fallback.
 * B2 reference expansions are included in the SAME total search budget.
 */
final class C2HierarchicalPlanner {
    static final int DEFAULT_BUDGET = 12000;
    static final int MAX_BUDGET = 24000;
    private static final double EPS = 1.0e-7;

    enum Status { PATH_FOUND, NO_PATH, INVALID_START, OUT_OF_SCAN, UNSAFE_TARGET,
                  BUDGET_EXCEEDED, INCONSISTENT_GRAPH }
    enum Source { HIERARCHICAL, B2_REFERENCE_FALLBACK, FOLLOW_TURN_AWARE, FOLLOW_TERRAIN_NATIVE, NONE }
    record Result(Status status, Source source, List<C2TraversabilityGraph.Node> path,
                  double cost, int expandedNodes, int visitedNodes, int budget,
                  int regionCount, int portalCount) {
        Result {
            Objects.requireNonNull(status); Objects.requireNonNull(source);
            path = List.copyOf(path);
            if (!Double.isFinite(cost) || cost < 0 || budget < 1 || budget > MAX_BUDGET
                    || expandedNodes < 0 || visitedNodes < 0 || regionCount < 0 || portalCount < 0)
                throw new IllegalArgumentException("Invalid hierarchical result");
            if ((status == Status.PATH_FOUND) != !path.isEmpty())
                throw new IllegalArgumentException("Path/status mismatch");
            if (status != Status.PATH_FOUND && cost != 0)
                throw new IllegalArgumentException("Failed hierarchy cannot claim cost");
        }
        boolean found() { return status == Status.PATH_FOUND; }
    }
    private record Arc(C2TraversabilityGraph.Node from, C2TraversabilityGraph.Node to,
                       double cost, List<C2TraversabilityGraph.Node> path) { }
    private record Work(C2TraversabilityGraph.Node node, double cost,
                        double heuristic, double score, long order) {
        Work(C2TraversabilityGraph.Node node,double cost,long order) {
            this(node,cost,0.0,cost,order); // local Dijkstra
        }
    }
    private static final Comparator<Work> ORDER = Comparator.comparingDouble(Work::score)
            .thenComparingDouble(Work::heuristic)
            .thenComparingDouble(Work::cost)
            .thenComparingLong(Work::order);
    private record Local(Map<C2TraversabilityGraph.Node,Arc> routes, boolean exceeded) { }
    private record Route(List<C2TraversabilityGraph.Node> nodes, double cost, boolean exceeded) { }
    private static final class Counter {
        final int limit;
        int used;
        int visited;
        Counter(int limit) { this.limit=limit; }
        boolean consume() { if (used >= limit) return false; used++; return true; }
        int remaining() { return limit - used; }
    }

    static Result plan(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                       C2AStarPlanner.Target start, C2AStarPlanner.Target goal,
                       int tileSize, int budget) {
        Objects.requireNonNull(graph); Objects.requireNonNull(snapshot);
        Objects.requireNonNull(start); Objects.requireNonNull(goal);
        if (budget < 1 || budget > MAX_BUDGET) throw new IllegalArgumentException("Invalid B3 search budget");
        C2RegionGraph regions = C2RegionGraph.build(graph, snapshot, tileSize);
        int nr = regions.regions().size(), np = regions.portals().size();
        var s = find(graph,start); var g = find(graph,goal);
        if (s == null || g == null || !bounds(snapshot,start) || !bounds(snapshot,goal)) {
            var baseline = C2AStarPlanner.plan(graph,snapshot,start,goal,
                    Math.min(budget,C2AStarPlanner.MAX_EXPANSION_BUDGET));
            return new Result(convert(baseline.status()),Source.NONE,List.of(),0,
                    baseline.expandedNodes(),baseline.visitedNodes(),budget,nr,np);
        }
        if (s.equals(g)) return new Result(Status.PATH_FOUND,Source.HIERARCHICAL,
                List.of(s),0,0,1,budget,nr,np);

        Counter counter = new Counter(budget);
        boolean reachable = coarseReachable(regions, regions.regionOf(s),regions.regionOf(g),counter);
        if (counter.remaining()==0) return fail(Status.BUDGET_EXCEEDED,counter,budget,nr,np);
        if (!reachable) return compareReference(null,0,graph,snapshot,start,goal,counter,budget,nr,np);

        // Overlay vertices = EVERY directed portal endpoint plus start/goal.
        Set<C2TraversabilityGraph.Node> endpoints = new LinkedHashSet<>();
        endpoints.add(s); endpoints.add(g);
        for (var portal:regions.portals()) {
            endpoints.add(portal.edge().from()); endpoints.add(portal.edge().to());
        }
        Map<C2TraversabilityGraph.Node,List<Arc>> overlay = new LinkedHashMap<>();
        for (var node:endpoints) overlay.put(node,new ArrayList<>());
        for (var portal:regions.portals()) {
            var edge=portal.edge();
            overlay.get(edge.from()).add(new Arc(edge.from(),edge.to(),edge.cost(),
                    List.of(edge.from(),edge.to())));
        }
        for (var region:regions.regions()) {
            Set<C2TraversabilityGraph.Node> inRegion = new HashSet<>(region.nodes());
            List<C2TraversabilityGraph.Node> borders = new ArrayList<>();
            for (var node:endpoints) if (inRegion.contains(node)) borders.add(node);
            if (borders.size() < 2) continue;
            for (var from:borders) {
                Local local = shortestInRegion(graph,inRegion,from,borders,counter);
                if (local.exceeded()) return fail(Status.BUDGET_EXCEEDED,counter,budget,nr,np);
                for (var dest:borders) {
                    Arc arc = local.routes().get(dest);
                    if (arc!=null && !dest.equals(from)) overlay.get(from).add(arc);
                }
            }
        }
        Route overlayRoute = shortestOverlay(overlay,s,g,graph,counter);
        if (overlayRoute.exceeded()) return fail(Status.BUDGET_EXCEEDED,counter,budget,nr,np);
        return compareReference(overlayRoute.nodes(),overlayRoute.cost(),graph,snapshot,
                start,goal,counter,budget,nr,np);
    }

    private static boolean coarseReachable(C2RegionGraph regionGraph,
                                           C2RegionGraph.Id start,C2RegionGraph.Id goal,Counter counter) {
        if (start.equals(goal)) return true;
        Map<C2RegionGraph.Id,List<C2RegionGraph.Id>> adjacency = new LinkedHashMap<>();
        for (var r:regionGraph.regions()) adjacency.put(r.id(),new ArrayList<>());
        for (var portal:regionGraph.portals())
            adjacency.get(portal.source()).add(portal.destination());
        Set<C2RegionGraph.Id> seen = new HashSet<>();
        ArrayDeque<C2RegionGraph.Id> q = new ArrayDeque<>();
        q.add(start);seen.add(start); counter.visited++;
        while(!q.isEmpty()) {
            if (!counter.consume()) return false;
            var cur=q.removeFirst();
            for (var next:adjacency.getOrDefault(cur,List.of())) {
                if (next.equals(goal)) return true;
                if (seen.add(next)) { q.addLast(next);counter.visited++; }
            }
        }
        return false;
    }

    private static Local shortestInRegion(C2TraversabilityGraph graph,
                     Set<C2TraversabilityGraph.Node> allowed,
                     C2TraversabilityGraph.Node source,
                     List<C2TraversabilityGraph.Node> targets, Counter counter) {
        Map<C2TraversabilityGraph.Node,Double> best = new HashMap<>();
        Map<C2TraversabilityGraph.Node,C2TraversabilityGraph.Edge> parent = new HashMap<>();
        Set<C2TraversabilityGraph.Node> settled = new HashSet<>();
        Set<C2TraversabilityGraph.Node> outstanding = new HashSet<>(targets);
        PriorityQueue<Work> pq = new PriorityQueue<>(ORDER);
        long seq=0;
        pq.add(new Work(source,0,seq++));best.put(source,0.0);counter.visited++;
        while (!pq.isEmpty() && !outstanding.isEmpty()) {
            Work work = pq.remove();
            if (Double.compare(best.getOrDefault(work.node(),Double.POSITIVE_INFINITY),work.cost())!=0
                    || !settled.add(work.node())) continue;
            if (!counter.consume()) return new Local(Map.of(),true);
            outstanding.remove(work.node());
            for (var edge:graph.neighbors(work.node())) {
                if (!allowed.contains(edge.to()) || !edge.from().equals(work.node())
                        || settled.contains(edge.to())) continue;
                double candidate=work.cost()+edge.cost();
                if (!Double.isFinite(candidate)) throw new IllegalArgumentException("Local path overflow");
                if (candidate < best.getOrDefault(edge.to(),Double.POSITIVE_INFINITY)) {
                    if (!best.containsKey(edge.to())) counter.visited++;
                    best.put(edge.to(),candidate);parent.put(edge.to(),edge);
                    pq.add(new Work(edge.to(),candidate,seq++));
                }
            }
        }
        Map<C2TraversabilityGraph.Node,Arc> routes = new LinkedHashMap<>();
        for (var dest:targets) {
            if (dest.equals(source) || !settled.contains(dest)) continue;
            List<C2TraversabilityGraph.Node> reverse=new ArrayList<>();
            var p=dest;reverse.add(p);
            while(!p.equals(source)) {
                var step=parent.get(p);
                if (step==null || reverse.size()>allowed.size())
                    throw new IllegalStateException("Invalid region predecessor chain");
                p=step.from();reverse.add(p);
            }
            Collections.reverse(reverse);
            routes.put(dest,new Arc(source,dest,best.get(dest),List.copyOf(reverse)));
        }
        return new Local(routes,false);
    }

    /** Portal-level A* (local intra-region Dijkstra paths supply exact arc costs). */
    private static Route shortestOverlay(Map<C2TraversabilityGraph.Node,List<Arc>> adjacency,
                    C2TraversabilityGraph.Node start,C2TraversabilityGraph.Node goal,
                    C2TraversabilityGraph graph,Counter counter) {
        Map<C2TraversabilityGraph.Node,Double> best=new HashMap<>();
        Map<C2TraversabilityGraph.Node,Arc> parent=new HashMap<>();
        Set<C2TraversabilityGraph.Node> settled=new HashSet<>();
        PriorityQueue<Work> pq=new PriorityQueue<>(ORDER);
        double stepBound = admissibleHorizontalCost(graph);
        long seq=0;
        double initialH = heuristic(start,goal,stepBound);
        pq.add(new Work(start,0,initialH,initialH,seq++));
        best.put(start,0.0);counter.visited++;
        while(!pq.isEmpty()) {
            Work current=pq.poll();
            if (Double.compare(best.getOrDefault(current.node(),Double.POSITIVE_INFINITY),current.cost())!=0
                    || !settled.add(current.node())) continue;
            if (current.node().equals(goal)) {
                List<Arc> reverse=new ArrayList<>();var node=goal;
                while(!node.equals(start)) {
                    Arc arc=parent.get(node);
                    if (arc==null || reverse.size()>adjacency.size())
                        throw new IllegalStateException("Invalid overlay predecessor chain");
                    reverse.add(arc); node=arc.from();
                }
                Collections.reverse(reverse);
                List<C2TraversabilityGraph.Node> route=new ArrayList<>();route.add(start);
                for (Arc arc:reverse) route.addAll(arc.path().subList(1,arc.path().size()));
                return new Route(List.copyOf(route),current.cost(),false);
            }
            if (!counter.consume()) return new Route(List.of(),0,true);
            for (Arc arc:adjacency.getOrDefault(current.node(),List.of())) {
                if (settled.contains(arc.to())) continue;
                double candidate=current.cost()+arc.cost();
                if (!Double.isFinite(candidate)) throw new IllegalArgumentException("Overlay cost overflow");
                if (candidate < best.getOrDefault(arc.to(),Double.POSITIVE_INFINITY)) {
                    if (!best.containsKey(arc.to())) counter.visited++;
                    best.put(arc.to(),candidate);parent.put(arc.to(),arc);
                    double h=heuristic(arc.to(),goal,stepBound);
                    pq.add(new Work(arc.to(),candidate,h,candidate+h,seq++));
                }
            }
        }
        return new Route(List.of(),0,false);
    }

    private static double admissibleHorizontalCost(C2TraversabilityGraph graph) {
        double min=Double.POSITIVE_INFINITY;
        for (var edge:graph.edges()) {
            long dx=Math.abs((long)edge.from().x()-edge.to().x());
            long dz=Math.abs((long)edge.from().z()-edge.to().z());
            double distance=Math.hypot(dx,dz);
            if (distance>0) min=Math.min(min,edge.cost()/distance);
        }
        return Double.isFinite(min) && min>0 ? Math.nextDown(min) : 0.0;
    }
    private static double heuristic(C2TraversabilityGraph.Node from,
                                    C2TraversabilityGraph.Node goal,double bound) {
        long dx=Math.abs((long)from.x()-goal.x());
        long dz=Math.abs((long)from.z()-goal.z());
        return Math.hypot(dx,dz)*bound;
    }
    private static Result compareReference(List<C2TraversabilityGraph.Node> route,double cost,
                     C2TraversabilityGraph graph,C2NavGrid.Snapshot snapshot,
                     C2AStarPlanner.Target start,C2AStarPlanner.Target goal,Counter counter,
                     int budget,int nr,int np) {
        if (counter.remaining() < 1) return fail(Status.BUDGET_EXCEEDED,counter,budget,nr,np);
        var ref=C2AStarPlanner.plan(graph,snapshot,start,goal,
                Math.min(counter.remaining(),C2AStarPlanner.MAX_EXPANSION_BUDGET));
        counter.used+=ref.expandedNodes();counter.visited+=ref.visitedNodes();
        if (ref.status()==C2AStarPlanner.Status.BUDGET_EXCEEDED)
            return fail(Status.BUDGET_EXCEEDED,counter,budget,nr,np);
        if (ref.found()) {
            if (route!=null && !route.isEmpty() && Math.abs(cost-ref.cost())<=EPS
                    && safeRoute(graph,route,cost))
                return new Result(Status.PATH_FOUND,Source.HIERARCHICAL,route,cost,
                        counter.used,counter.visited,budget,nr,np);
            return new Result(Status.PATH_FOUND,Source.B2_REFERENCE_FALLBACK,
                    ref.path(),ref.cost(),counter.used,counter.visited,budget,nr,np);
        }
        if (route!=null && !route.isEmpty()) return fail(Status.INCONSISTENT_GRAPH,counter,budget,nr,np);
        return fail(convert(ref.status()),counter,budget,nr,np);
    }
    private static boolean safeRoute(C2TraversabilityGraph graph,List<C2TraversabilityGraph.Node> path,double cost) {
        double sum=0;
        for (int i=1;i<path.size();i++) {
            boolean valid=false;
            for (var edge:graph.neighbors(path.get(i-1))) {
                if (edge.to().equals(path.get(i))) {sum+=edge.cost();valid=true;break;}
            }
            if (!valid) return false;
        }
        return Math.abs(sum-cost)<=EPS;
    }
    private static C2TraversabilityGraph.Node find(C2TraversabilityGraph graph,C2AStarPlanner.Target t) {
        for (var n:graph.nodes()) if (n.x()==t.x() && n.supportY()==t.supportY()
                && n.z()==t.z()) return n;
        return null;
    }
    private static boolean bounds(C2NavGrid.Snapshot scan,C2AStarPlanner.Target t) {
        return Math.abs((long)t.x()-scan.originX())<=scan.radius()
                && Math.abs((long)t.z()-scan.originZ())<=scan.radius()
                && Math.abs((long)t.supportY()-scan.originY())<=C2NavGrid.VERTICAL_RADIUS;
    }
    private static Status convert(C2AStarPlanner.Status status) {return Status.valueOf(status.name());}
    private static Result fail(Status status,Counter c,int budget,int nr,int np) {
        return new Result(status,Source.NONE,List.of(),0,c.used,c.visited,budget,nr,np);
    }
}
