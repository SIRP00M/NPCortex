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
 * C2-P1-B2: bounded, deterministic, PURE-JAVA read-only path planning over a
 * pre-verified B1 graph. Never loads chunks, accesses NPCs, invokes Ollama,
 * drives movement, or promises that a path remains safe after this snapshot.
 *
 * The route contract is usable by a future B3 Region Planner; the caller MUST
 * revalidate real-world collision and lifecycle before any future execution.
 */
final class C2AStarPlanner {
    static final int DEFAULT_EXPANSION_BUDGET = C2NavGrid.MAX_CANDIDATES;
    static final int MAX_EXPANSION_BUDGET = C2NavGrid.MAX_CANDIDATES;

    enum Status {
        PATH_FOUND, NO_PATH, INVALID_START, OUT_OF_SCAN, UNSAFE_TARGET,
        BUDGET_EXCEEDED
    }

    /** Target is an EXACT Minecraft support-block coordinate, not rounded
     * automatically to a nearby safe location. Fail closed if unobserved.
     */
    record Target(int x, int supportY, int z) { }

    /** Immutable snapshot of a calculation. Includes start and goal on success. */
    record Result(Status status, List<C2TraversabilityGraph.Node> path,
                  double cost, int expandedNodes, int visitedNodes,
                  int expansionBudget) {
        Result {
            Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
            if (!Double.isFinite(cost) || cost < 0.0 || expandedNodes < 0
                    || visitedNodes < 0 || expansionBudget < 1
                    || expansionBudget > MAX_EXPANSION_BUDGET)
                throw new IllegalArgumentException("Invalid A* result metadata");
            if ((status == Status.PATH_FOUND) != !path.isEmpty())
                throw new IllegalArgumentException("Path/status mismatch");
            if (status != Status.PATH_FOUND && cost != 0.0)
                throw new IllegalArgumentException("Failure must not claim path cost");
        }
        boolean found() { return status == Status.PATH_FOUND; }
    }

    private record QueueEntry(C2TraversabilityGraph.Node node, double cost,
                              double heuristic, double fScore, long order) { }
    private static final Comparator<QueueEntry> PRIORITY =
            Comparator.comparingDouble(QueueEntry::fScore)
                    .thenComparingDouble(QueueEntry::heuristic)
                    .thenComparingDouble(QueueEntry::cost)
                    .thenComparingLong(QueueEntry::order);

    /** Validate target bounds before querying the graph. Coordinates use the
     * same center/radius as the P1-A observation; supportY can vary +/- 2.
     */
    static Result plan(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                       Target start, Target goal, int budget) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        requireBudget(budget);
        if (snapshot.radius() < 1 || snapshot.radius() > C2NavGrid.MAX_RADIUS)
            throw new IllegalArgumentException("Invalid snapshot radius");
        if (snapshot.cells().size() > C2NavGrid.MAX_CANDIDATES)
            throw new IllegalArgumentException("Oversized snapshot");
        // Reject a graph with vertices inconsistent with this observation.
        // Edges are supplied by a B1 verified clearance probe, and our Forge
        // bridge always captures both together in one synchronous operation.
        Map<Target, Double> walkable = new HashMap<>();
        for (C2NavGrid.Cell cell : snapshot.cells()) {
            if (C2NavGrid.plannable(cell.condition()))
                if (walkable.putIfAbsent(new Target(cell.x(),cell.supportY(),cell.z()),
                        cell.footY()) != null)
                    throw new IllegalArgumentException("Duplicate walkable cell in observation");
        }
        for (C2TraversabilityGraph.Node node : graph.nodes()) {
            Target key = new Target(node.x(), node.supportY(), node.z());
            Double footY = walkable.remove(key);
            if (!inBounds(snapshot,key) || footY == null ||
                    Double.compare(footY,node.footY()) != 0)
                throw new IllegalArgumentException("Graph vertex does not match observation");
        }
        if (!walkable.isEmpty())
            throw new IllegalArgumentException("Graph missing observed walkable vertices");
        if (!inBounds(snapshot, start) || resolve(graph, start) == null)
            return failure(Status.INVALID_START, 0, 0, budget);
        if (!inBounds(snapshot, goal))
            return failure(Status.OUT_OF_SCAN, 0, 0, budget);
        C2TraversabilityGraph.Node startNode = resolve(graph, start);
        C2TraversabilityGraph.Node goalNode = resolve(graph, goal);
        if (goalNode == null)
            return failure(Status.UNSAFE_TARGET, 0, 0, budget);
        if (startNode.equals(goalNode))
            return new Result(Status.PATH_FOUND, List.of(startNode), 0, 0, 1, budget);

        // Min cost per unit of horizontal Manhattan displacement is an
        // admissible lower bound even for future multi-cell/vertical edges.
        // An all-vertical graph has h=0 (Dijkstra fallback).
        double minHorizontalCost = admissibleHorizontalCost(graph);
        Map<C2TraversabilityGraph.Node, Double> best = new HashMap<>();
        Map<C2TraversabilityGraph.Node, C2TraversabilityGraph.Node> parent = new HashMap<>();
        Set<C2TraversabilityGraph.Node> settled = new HashSet<>();
        PriorityQueue<QueueEntry> open = new PriorityQueue<>(PRIORITY);
        long sequence = 0;
        best.put(startNode, 0.0);
        double h = heuristic(startNode,goalNode,minHorizontalCost);
        open.add(new QueueEntry(startNode,0,h,h,sequence++));
        int expansions = 0;
        while (!open.isEmpty()) {
            QueueEntry current = open.poll();
            Double known = best.get(current.node());
            if (known == null || Double.compare(current.cost(),known) != 0
                    || settled.contains(current.node())) continue;
            // Arrival at goal is checked BEFORE consuming the next expansion.
            if (current.node().equals(goalNode)) {
                List<C2TraversabilityGraph.Node> reversed = new ArrayList<>();
                C2TraversabilityGraph.Node n = goalNode;
                reversed.add(n);
                while (!n.equals(startNode)) {
                    n = parent.get(n);
                    if (n == null || reversed.size() > graph.nodes().size())
                        throw new IllegalStateException("Broken A* parent chain");
                    reversed.add(n);
                }
                java.util.Collections.reverse(reversed);
                return new Result(Status.PATH_FOUND,reversed,known,expansions,best.size(),budget);
            }
            if (expansions >= budget)
                return failure(Status.BUDGET_EXCEEDED,expansions,best.size(),budget);
            settled.add(current.node());
            expansions++;
            for (C2TraversabilityGraph.Edge edge : graph.neighbors(current.node())) {
                if (!edge.from().equals(current.node()) || !graph.contains(edge.to())
                        || !Double.isFinite(edge.cost()) || edge.cost() <= 0)
                    throw new IllegalArgumentException("Invalid directed graph edge");
                if (settled.contains(edge.to())) continue;
                double candidate = current.cost() + edge.cost();
                if (!Double.isFinite(candidate)) throw new IllegalArgumentException("Path cost overflow");
                if (candidate < best.getOrDefault(edge.to(),Double.POSITIVE_INFINITY)) {
                    best.put(edge.to(),candidate);
                    parent.put(edge.to(),current.node());
                    double nextH = heuristic(edge.to(),goalNode,minHorizontalCost);
                    open.add(new QueueEntry(edge.to(),candidate,nextH,candidate+nextH,sequence++));
                }
            }
        }
        return failure(Status.NO_PATH, expansions, best.size(),budget);
    }

    private static C2TraversabilityGraph.Node resolve(C2TraversabilityGraph graph, Target target) {
        for (C2TraversabilityGraph.Node node : graph.nodes())
            if (node.x()==target.x() && node.supportY()==target.supportY()
                    && node.z()==target.z()) return node;
        return null;
    }

    private static boolean inBounds(C2NavGrid.Snapshot scan,Target t) {
        return Math.abs((long)t.x() - scan.originX()) <= scan.radius()
                && Math.abs((long)t.z() - scan.originZ()) <= scan.radius()
                && Math.abs((long)t.supportY() - scan.originY()) <= C2NavGrid.VERTICAL_RADIUS;
    }

    private static double admissibleHorizontalCost(C2TraversabilityGraph graph) {
        double min = Double.POSITIVE_INFINITY;
        for (C2TraversabilityGraph.Edge edge : graph.edges()) {
            long dx = Math.abs((long)edge.from().x()-edge.to().x());
            long dz = Math.abs((long)edge.from().z()-edge.to().z());
            double distance = Math.hypot(dx,dz);
            if (distance > 0) min = Math.min(min,edge.cost()/distance);
        }
        return Double.isFinite(min) && min > 0 ? Math.nextDown(min) : 0.0;
    }

    private static double heuristic(C2TraversabilityGraph.Node from,
                                    C2TraversabilityGraph.Node to,double stepMin) {
        long dx=Math.abs((long)from.x()-to.x());
        long dz=Math.abs((long)from.z()-to.z());
        return Math.hypot(dx,dz)*stepMin;
    }

    private static Result failure(Status status,int expanded,int visited,int budget) {
        return new Result(status,List.of(),0.0,expanded,visited,budget);
    }

    private static void requireBudget(int budget) {
        if (budget < 1 || budget > MAX_EXPANSION_BUDGET)
            throw new IllegalArgumentException("Expansion budget must be 1.."+MAX_EXPANSION_BUDGET);
    }
    private C2AStarPlanner() {}
}
