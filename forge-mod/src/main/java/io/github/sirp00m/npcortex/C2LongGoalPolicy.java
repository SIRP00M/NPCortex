package io.github.sirp00m.npcortex;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Pure bounded long-range heuristic: one logical C2 goal executed as short legs.
 * Not a complete pathfinder: it may end with NO_PROGRESS / OSCILLATION / LEG_LIMIT.
 * Holds only in-memory, per-Run state; nothing here is persisted or replayed.
 */
final class C2LongGoalPolicy {
    enum LongState { NONE, LEG_ACTIVE, LEG_QUIESCING, FINAL_ACTIVE }
    enum Completion { NOT_LONG, LEG_DONE, FINAL_VERIFY }
    enum Verdict { CONTINUE, NO_PROGRESS, OSCILLATION, LEG_LIMIT }

    static final int MAX_DISTANCE = 160;        // Chebyshev blocks from the start, navlong admission
    static final int MAX_LEGS = 40;           // 160 blocks at <=6 per leg needs 27; rest is detour margin
    static final int MIN_LEG_LENGTH = 3;        // Chebyshev X/Z blocks per intermediate leg
    static final int PER_LEG_TICKS = 600;       // includes quiescence and FIFO wait
    static final int BASE_TOTAL_TICKS = 1200;
    static final int TICKS_PER_BLOCK = 40;
    static final int MAX_TOTAL_TICKS = 8000;
    static final int TOTAL_SEARCH_BUDGET = 120000;
    static final int NO_BEST_LEGS = 6;          // legs in a row without a new best distance
    static final int MAX_CELL_VISITS = 3;
    static final double MIN_PROGRESS = 2.0;
    static final double VISIT_PENALTY = 6.0;
    static final int CELL_SIZE = 4;

    private C2LongGoalPolicy() {}

    /** Only a route planned to the authorized final target may lead to a result. */
    static Completion onRouteComplete(LongState state) {
        return switch (state) {
            case NONE -> Completion.NOT_LONG;
            case LEG_ACTIVE -> Completion.LEG_DONE;
            case FINAL_ACTIVE -> Completion.FINAL_VERIFY;
            case LEG_QUIESCING -> throw new IllegalStateException("route cannot complete while quiescing");
        };
    }

    static int totalTickLimit(int distance) {
        if (distance < 0) throw new IllegalArgumentException("distance");
        return (int) Math.min(MAX_TOTAL_TICKS, BASE_TOTAL_TICKS + (long) TICKS_PER_BLOCK * distance);
    }

    /** Per-leg and total wall limits; a LongGoal never runs unbounded. */
    static boolean timedOut(int routeTicks, int legBaseTicks, int totalLimit) {
        return routeTicks - legBaseTicks > PER_LEG_TICKS || routeTicks > totalLimit;
    }

    record Cell(int cx, int cz, int yBand) { }
    static Cell cellOf(int x, int supportY, int z) {
        return new Cell(Math.floorDiv(x, CELL_SIZE), Math.floorDiv(z, CELL_SIZE), Math.floorDiv(supportY, CELL_SIZE));
    }
    record Selection(C2TraversabilityGraph.Node target, boolean isFinal, double score) { }

    static final class LongGoal {
        final int finalX, finalSupportY, finalZ, startDistance;
        private final Map<Cell, Integer> visits = new HashMap<>();
        private double bestDistance;
        private int legsSinceBest, legs, expanded;
        private C2TraversabilityGraph.Node legTarget;

        LongGoal(int startX, int startSupportY, int startZ, int finalX, int finalSupportY, int finalZ) {
            this.finalX = finalX; this.finalSupportY = finalSupportY; this.finalZ = finalZ;
            this.startDistance = Math.max(Math.abs(finalX - startX), Math.abs(finalZ - startZ));
            if (startDistance > MAX_DISTANCE) throw new IllegalArgumentException("LongGoal beyond MAX_DISTANCE");
            visits.put(cellOf(startX, startSupportY, startZ), 1);
            bestDistance = distance(startX, startZ);
        }

        int legs() { return legs; }
        int expanded() { return expanded; }
        C2TraversabilityGraph.Node legTarget() { return legTarget; }
        int totalTickLimit() { return C2LongGoalPolicy.totalTickLimit(startDistance); }
        double distance(int x, int z) { return Math.hypot(x - finalX, z - finalZ); }
        int visits(int x, int supportY, int z) { return visits.getOrDefault(cellOf(x, supportY, z), 0); }

        /** Record the arrival at the end of a completed leg and judge progress. */
        Verdict arrived(int x, int supportY, int z) {
            int count = visits.merge(cellOf(x, supportY, z), 1, Integer::sum);
            double d = distance(x, z);
            if (d <= bestDistance - MIN_PROGRESS) { bestDistance = d; legsSinceBest = 0; }
            else legsSinceBest++;
            if (count > MAX_CELL_VISITS) return Verdict.OSCILLATION;
            if (legsSinceBest >= NO_BEST_LEGS) return Verdict.NO_PROGRESS;
            if (legs >= MAX_LEGS) return Verdict.LEG_LIMIT;
            return Verdict.CONTINUE;
        }

        /** True when the final column lies inside the captured X/Z/Y window around (x,supportY,z). */
        boolean finalInWindow(int x, int supportY, int z) {
            return Math.abs((long) finalX - x) <= C2NavGrid.MAX_RADIUS && Math.abs((long) finalZ - z) <= C2NavGrid.MAX_RADIUS
                    && Math.abs((long) finalSupportY - supportY) <= C2NavGrid.VERTICAL_RADIUS;
        }

        /** The final column is a verified walkable graph node (UNSAFE_TARGET otherwise). */
        boolean finalWalkable(C2TraversabilityGraph graph) {
            for (var n : graph.nodes()) if (n.x() == finalX && n.supportY() == finalSupportY && n.z() == finalZ) return true;
            return false;
        }

        /** Bounded budget: false once the cumulative search budget is spent. */
        boolean chargeSearch(int expandedNodes) {
            if (expandedNodes < 0) throw new IllegalArgumentException("expanded");
            expanded += expandedNodes;
            return expanded <= TOTAL_SEARCH_BUDGET;
        }

        void legStarted(C2TraversabilityGraph.Node target) { legs++; legTarget = target; }

        /**
         * Choose the next target from nodes reachable from {@code start} inside the
         * captured graph. The final node wins if reachable; otherwise the lowest of
         * distance-to-final plus a bounded visit penalty. No hard exclusion: a
         * visited cell stays selectable so a dead end can be left. Null = no candidate.
         */
        Selection select(C2TraversabilityGraph graph, C2TraversabilityGraph.Node start) {
            if (graph == null || start == null || !graph.contains(start)) return null;
            Map<C2TraversabilityGraph.Node, Double> cost = reachable(graph, start);
            Selection best = null;
            for (var entry : cost.entrySet()) {
                var n = entry.getKey();
                if (n.x() == finalX && n.supportY() == finalSupportY && n.z() == finalZ)
                    return new Selection(n, true, 0.0);
            }
            double bestCost = 0;
            for (var entry : cost.entrySet()) {
                var n = entry.getKey();
                if (Math.max(Math.abs(n.x() - start.x()), Math.abs(n.z() - start.z())) < MIN_LEG_LENGTH) continue;
                double score = distance(n.x(), n.z()) + VISIT_PENALTY * visits(n.x(), n.supportY(), n.z());
                double c = entry.getValue();
                if (best == null || score < best.score() - 1e-9
                        || Math.abs(score - best.score()) <= 1e-9 && better(n, c, best.target(), bestCost)) {
                    best = new Selection(n, false, score);
                    bestCost = c;
                }
            }
            return best;
        }

        private static boolean better(C2TraversabilityGraph.Node a, double ca, C2TraversabilityGraph.Node b, double cb) {
            if (Math.abs(ca - cb) > 1e-9) return ca < cb;
            if (a.x() != b.x()) return a.x() < b.x();
            if (a.z() != b.z()) return a.z() < b.z();
            return a.supportY() < b.supportY();
        }
    }

    /** Deterministic Dijkstra over the already-verified graph; bounded by graph size. */
    static Map<C2TraversabilityGraph.Node, Double> reachable(C2TraversabilityGraph graph,
                                                             C2TraversabilityGraph.Node start) {
        record Item(C2TraversabilityGraph.Node node, double cost) { }
        Comparator<Item> order = Comparator.comparingDouble(Item::cost)
                .thenComparingInt(i -> i.node().x()).thenComparingInt(i -> i.node().z())
                .thenComparingInt(i -> i.node().supportY());
        Map<C2TraversabilityGraph.Node, Double> best = new HashMap<>();
        PriorityQueue<Item> queue = new PriorityQueue<>(order);
        best.put(start, 0.0);
        queue.add(new Item(start, 0.0));
        while (!queue.isEmpty()) {
            Item item = queue.poll();
            if (item.cost() > best.get(item.node())) continue;
            for (var edge : graph.neighbors(item.node())) {
                double next = item.cost() + edge.cost();
                Double known = best.get(edge.to());
                if (known == null || next < known - 1e-12) {
                    best.put(edge.to(), next);
                    queue.add(new Item(edge.to(), next));
                }
            }
        }
        return best;
    }
}
