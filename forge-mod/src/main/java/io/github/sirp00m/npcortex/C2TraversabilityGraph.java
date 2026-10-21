package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * C2-P1-B1: immutable, bounded, read-only traversal graph built from a P1-A
 * snapshot. This is NOT a path planner and does not move an NPC.
 *
 * A WALKABLE standing surface is only a *candidate* vertex. A WALK edge must
 * also pass a server-world clearance probe for the ENTIRE horizontal corridor.
 * Live F2 3D graphs admit diagonals ONLY on exactly-level WALK surfaces,
 * after both orthogonal corridors and the direct player sweep are verified.
 * Legacy flat tests stay cardinal-only. Unknown terrain is never executable;
 * elevations remain separate nodes even at the same X/Z.
 */
final class C2TraversabilityGraph {
    private static final double HEIGHT_EPSILON = 0.0001;
    private static final int[][] UNIQUE_CARDINAL = {{0, 1}, {1, 0}};
    private static final int[][] UNIQUE_DIAGONAL = {{1, 1}, {1, -1}};
    private static final int MAX_EDGES = 20 * C2NavGrid.MAX_CANDIDATES;

    enum MoveKind { WALK, STEP_UP, STEP_DOWN, JUMP_UP, SAFE_DESCEND }
    // Horizontal cost is never below WALK; traversal-specific costs favor a
    // safe flat detour where one exists. No reverse edge is inferred.
    static MoveKind kind(double rise) {
        if (Math.abs(rise) <= HEIGHT_EPSILON) return MoveKind.WALK;
        if (Math.abs(rise-0.5) <= HEIGHT_EPSILON) return MoveKind.STEP_UP;
        if (Math.abs(rise+0.5) <= HEIGHT_EPSILON) return MoveKind.STEP_DOWN;
        if (Math.abs(rise-1.0) <= HEIGHT_EPSILON) return MoveKind.JUMP_UP;
        if (Math.abs(rise+1.0) <= HEIGHT_EPSILON) return MoveKind.SAFE_DESCEND;
        return null;
    }
    static double cost(MoveKind kind) {
        return switch (kind) {
            case WALK -> 1.0;
            case STEP_UP, STEP_DOWN -> 1.30;
            case JUMP_UP -> 2.0;
            case SAFE_DESCEND -> 1.65;
        };
    }
    record Node(int x, int supportY, int z, double footY) {
        Node {
            if (!Double.isFinite(footY)) throw new IllegalArgumentException("Non-finite node height");
        }
    }
    record Edge(Node from, Node to, MoveKind kind, double cost) {
        Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
            if (from.equals(to) || !Double.isFinite(cost) || cost <= 0.0)
                throw new IllegalArgumentException("Invalid traversal edge");
        }
    }
    record Key(int x, int y, int z) {}

    /** Must be bound to a Minecraft SERVER-thread, NO-FORCE-LOAD collision probe.
     * Both endpoints are known WALKABLE nodes; this must check the swept corridor.
     * If unable to determine safety, return false (never infer an unobstructed edge).
     */
    interface ClearanceProbe { boolean clear(Node from, Node to); }
    /** Independent directional probe, bound to the C1 traversal safety contract. */
    interface TraversalProbe { boolean clear(Node from, Node to, MoveKind kind); }
    interface CostProbe { C2TraversalCost.Assessment assess(Node from, Node to, MoveKind kind); }

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Map<Node, List<Edge>> adjacency;

    private C2TraversabilityGraph(List<Node> nodes, List<Edge> edges, Map<Node, List<Edge>> adjacency) {
        this.nodes = List.copyOf(nodes);
        this.edges = List.copyOf(edges);
        Map<Node, List<Edge>> copy = new LinkedHashMap<>();
        for (Map.Entry<Node, List<Edge>> entry : adjacency.entrySet())
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        this.adjacency = Collections.unmodifiableMap(copy);
    }
    List<Node> nodes() { return nodes; }
    List<Edge> edges() { return edges; }
    List<Edge> neighbors(Node from) { return adjacency.getOrDefault(from, List.of()); }
    boolean contains(Node node) { return adjacency.containsKey(node); }

    /** Legacy flat graph fixture stays flat for B1/B2/B3 regression compatibility. */
    static C2TraversabilityGraph build(C2NavGrid.Snapshot scan, ClearanceProbe probe) {
        if (probe == null) throw new IllegalArgumentException("Clearance probe required");
        return buildInternal(scan,(from,to,kind) ->
                kind==MoveKind.WALK && probe.clear(from,to)
                        ? C2TraversalCost.Assessment.clear() : C2TraversalCost.Assessment.blocked(),false);
    }

    static C2TraversabilityGraph build3D(C2NavGrid.Snapshot scan, TraversalProbe probe) {
        return buildInternal(scan,(from,to,kind) -> probe.clear(from,to,kind)
                ? C2TraversalCost.Assessment.clear() : C2TraversalCost.Assessment.blocked(),true);
    }

    static C2TraversabilityGraph build3DWithRisk(C2NavGrid.Snapshot scan, CostProbe probe) {
        return buildInternal(scan,probe,true);
    }

    private static C2TraversabilityGraph buildInternal(C2NavGrid.Snapshot scan, CostProbe probe,
                                                      boolean diagonals) {
        if (scan == null) throw new IllegalArgumentException("Snapshot required");
        if (probe == null) throw new IllegalArgumentException("Clearance probe required: cannot assume edges safe");
        if (scan.radius() < 1 || scan.radius() > C2NavGrid.MAX_RADIUS)
            throw new IllegalArgumentException("Invalid snapshot radius");
        if (scan.cells().size() > C2NavGrid.MAX_CANDIDATES)
            throw new IllegalArgumentException("Snapshot over capacity");

        // Reject ambiguous input, including walkable cells forged into unloaded columns.
        Set<Key> seen = new HashSet<>();
        Set<Long> unloadedColumns = new HashSet<>();
        for (C2NavGrid.Cell c : scan.cells()) {
            if (Math.abs((long)c.x() - scan.originX()) > scan.radius()
                    || Math.abs((long)c.z() - scan.originZ()) > scan.radius()
                    || Math.abs((long)c.supportY() - scan.originY()) > C2NavGrid.VERTICAL_RADIUS)
                throw new IllegalArgumentException("Out-of-snapshot cell");
            if (!seen.add(new Key(c.x(),c.supportY(),c.z())))
                throw new IllegalArgumentException("Duplicate terrain cell");
            if (c.condition() == C2NavGrid.Condition.UNLOADED)
                unloadedColumns.add(columnKey(c.x(), c.z()));
        }

        Map<Key, Node> byKey = new HashMap<>();
        Map<Key, Double> riskAt = new HashMap<>();
        List<Node> vertices = new ArrayList<>();
        for (C2NavGrid.Cell c : scan.cells()) {
            if (!C2NavGrid.plannable(c.condition())
                    || unloadedColumns.contains(columnKey(c.x(), c.z()))) continue;
            Node node = new Node(c.x(),c.supportY(),c.z(),c.footY());
            Key key=new Key(node.x(),node.supportY(),node.z());
            byKey.put(key,node);
            riskAt.put(key,c.riskPenalty());
            vertices.add(node);
        }
        // The world sweep measures body-level hazards; the observed standing
        // surface contributes penalties for slowing floor blocks (e.g. Soul Sand).
        // Use MAX rather than sum to avoid double-counting the same risk.
        CostProbe assessed=(from,to,kind) -> {
            C2TraversalCost.Assessment raw=probe.assess(from,to,kind);
            if(raw==null || !raw.passable()) return C2TraversalCost.Assessment.blocked();
            double risk=Math.max(raw.penalty(),Math.max(
                    riskAt.getOrDefault(new Key(from.x(),from.supportY(),from.z()),0.0),
                    riskAt.getOrDefault(new Key(to.x(),to.supportY(),to.z()),0.0)));
            return new C2TraversalCost.Assessment(true,risk);
        };
        Map<Node, List<Edge>> adjacency = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        for (Node node : vertices) adjacency.put(node, new ArrayList<>());

        for (Node from : vertices) {
            for (int[] step : UNIQUE_CARDINAL) {
                // Cardinal 3D primitives retain their independently validated
                // directional semantics; diagonal jumps/drops are forbidden.
                long nx = (long)from.x() + step[0], nz = (long)from.z() + step[1];
                if (nx < Integer.MIN_VALUE || nx > Integer.MAX_VALUE
                        || nz < Integer.MIN_VALUE || nz > Integer.MAX_VALUE) continue;
                for (int dy = -C2NavGrid.VERTICAL_RADIUS * 2;
                        dy <= C2NavGrid.VERTICAL_RADIUS * 2; dy++) {
                    long ny = (long) from.supportY() + dy;
                    if (ny < Integer.MIN_VALUE || ny > Integer.MAX_VALUE) continue;
                    Node to = byKey.get(new Key((int)nx,(int)ny,(int)nz));
                    if (to == null) continue;
                    MoveKind forwardKind = kind(to.footY()-from.footY());
                    MoveKind backwardKind = kind(from.footY()-to.footY());
                    if (forwardKind==null || backwardKind==null) continue;
                    // Legacy WALK edges stay symmetric; vertical edges are
                    // independent directed approvals (up may be safe, down not).
                    if (forwardKind==MoveKind.WALK) {
                        var f = assessed.assess(from,to,forwardKind);
                        var b = assessed.assess(to,from,backwardKind);
                        if (f == null || b == null || !f.passable() || !b.passable()) continue;
                        addEdge(edges,adjacency,from,to,forwardKind,
                                C2TraversalCost.edgeCost(cost(forwardKind),f));
                        addEdge(edges,adjacency,to,from,backwardKind,
                                C2TraversalCost.edgeCost(cost(backwardKind),b));
                    } else {
                        var f=assessed.assess(from,to,forwardKind);
                        var b=assessed.assess(to,from,backwardKind);
                        if (f != null && f.passable())
                            addEdge(edges,adjacency,from,to,forwardKind,
                                    C2TraversalCost.edgeCost(cost(forwardKind),f));
                        if (b != null && b.passable())
                            addEdge(edges,adjacency,to,from,backwardKind,
                                    C2TraversalCost.edgeCost(cost(backwardKind),b));
                    }
                    if (edges.size() > MAX_EDGES) throw new IllegalArgumentException("Too many graph edges");
                }
            }
        }
        if (diagonals) {
            // F2: flat-only diagonal movement. Both component orthogonal
            // corridors MUST be independently traversable in both directions,
            // in addition to the direct swept player AABB. No corner-cutting,
            // no diagonal jump/drop and no invented reverse edge.
            for (Node from : vertices) for (int[] step : UNIQUE_DIAGONAL) {
                long nx=(long)from.x()+step[0], nz=(long)from.z()+step[1];
                if(nx<Integer.MIN_VALUE || nx>Integer.MAX_VALUE
                        || nz<Integer.MIN_VALUE || nz>Integer.MAX_VALUE) continue;
                Node to=byKey.get(new Key((int)nx,from.supportY(),(int)nz));
                if(to==null || Math.abs(to.footY()-from.footY())>HEIGHT_EPSILON) continue;
                Node midX=byKey.get(new Key(to.x(),from.supportY(),from.z()));
                Node midZ=byKey.get(new Key(from.x(),from.supportY(),to.z()));
                if(midX==null || midZ==null
                        || Math.abs(midX.footY()-from.footY())>HEIGHT_EPSILON
                        || Math.abs(midZ.footY()-from.footY())>HEIGHT_EPSILON) continue;
                if (!edgeBoth(adjacency,from,midX) || !edgeBoth(adjacency,midX,to)
                        || !edgeBoth(adjacency,from,midZ) || !edgeBoth(adjacency,midZ,to)
                         ) continue;
                var forward=assessed.assess(from,to,MoveKind.WALK);
                var backward=assessed.assess(to,from,MoveKind.WALK);
                if(forward==null || backward==null || !forward.passable() || !backward.passable()) continue;
                addEdge(edges,adjacency,from,to,MoveKind.WALK,
                        C2TraversalCost.edgeCost(Math.sqrt(2.0),forward));
                addEdge(edges,adjacency,to,from,MoveKind.WALK,
                        C2TraversalCost.edgeCost(Math.sqrt(2.0),backward));
                if(edges.size()>MAX_EDGES) throw new IllegalArgumentException("Too many graph edges");
            }
        }
        return new C2TraversabilityGraph(vertices,edges,adjacency);
    }

    private static boolean edgeBoth(Map<Node,List<Edge>> adjacency,Node from,Node to) {
        return adjacency.getOrDefault(from,List.of()).stream().anyMatch(e -> e.to().equals(to)
                    && e.kind()==MoveKind.WALK)
                && adjacency.getOrDefault(to,List.of()).stream().anyMatch(e -> e.to().equals(from)
                    && e.kind()==MoveKind.WALK);
    }
    private static void addEdge(List<Edge> edges,Map<Node,List<Edge>> adjacency,
                                Node from,Node to,MoveKind kind) {
        addEdge(edges,adjacency,from,to,kind,cost(kind));
    }
    private static void addEdge(List<Edge> edges,Map<Node,List<Edge>> adjacency,
                                Node from,Node to,MoveKind kind,double amount) {
        Edge edge=new Edge(from,to,kind,amount);
        edges.add(edge);
        adjacency.get(from).add(edge);
    }

    /** Preserve both signed coordinate components; no lossy packed 16-bit index. */
    private static long columnKey(int x, int z) {
        return ((long)x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
