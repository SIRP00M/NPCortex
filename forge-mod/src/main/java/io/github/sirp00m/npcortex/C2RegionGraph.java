package io.github.sirp00m.npcortex;

import java.util.ArrayDeque;
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
 * C2-P1-B3: immutable, pure-Java partition of a verified B1 graph.
 * Regions are *connected components inside an X/Z tile*, NOT entire tiles:
 * a wall may split a tile into two different regions. Directed portals are
 * sourced ONLY from B1 edges; geometric adjacency never invents traversal.
 * No chunk loads, entity calls, NPC motion, caching, or persistence.
 */
final class C2RegionGraph {
    static final int DEFAULT_TILE_SIZE = 4;
    static final int MIN_TILE_SIZE = 2;
    static final int MAX_TILE_SIZE = 6;

    record Id(int tileX, int tileZ, int component) { }
    record Region(Id id, List<C2TraversabilityGraph.Node> nodes) {
        Region { Objects.requireNonNull(id); nodes = List.copyOf(nodes); }
    }
    record Portal(Id source, Id destination, C2TraversabilityGraph.Edge edge) {
        Portal {
            Objects.requireNonNull(source); Objects.requireNonNull(destination);
            Objects.requireNonNull(edge);
            if (source.equals(destination)) throw new IllegalArgumentException("Portal cannot be intra-region");
        }
    }
    private record Tile(int x, int z) { }

    private final List<Region> regions;
    private final List<Portal> portals;
    private final Map<C2TraversabilityGraph.Node, Id> assignments;
    private final Map<Id, List<C2TraversabilityGraph.Node>> regionNodes;

    private C2RegionGraph(List<Region> regions, List<Portal> portals,
                          Map<C2TraversabilityGraph.Node, Id> assignments) {
        this.regions = List.copyOf(regions);
        this.portals = List.copyOf(portals);
        this.assignments = Collections.unmodifiableMap(new LinkedHashMap<>(assignments));
        Map<Id, List<C2TraversabilityGraph.Node>> copy = new LinkedHashMap<>();
        for (Region region : regions) copy.put(region.id(), region.nodes());
        this.regionNodes = Collections.unmodifiableMap(copy);
    }
    List<Region> regions() { return regions; }
    List<Portal> portals() { return portals; }
    Id regionOf(C2TraversabilityGraph.Node node) { return assignments.get(node); }
    List<C2TraversabilityGraph.Node> nodesIn(Id region) {
        return regionNodes.getOrDefault(region, List.of());
    }

    static C2RegionGraph build(C2TraversabilityGraph graph, C2NavGrid.Snapshot snapshot,
                               int tileSize) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(snapshot, "snapshot");
        if (tileSize < MIN_TILE_SIZE || tileSize > MAX_TILE_SIZE)
            throw new IllegalArgumentException("Unsupported Region tile size");
        if (graph.nodes().size() > C2NavGrid.MAX_CANDIDATES)
            throw new IllegalArgumentException("Unbounded Region graph");
        if (snapshot.radius() < 1 || snapshot.radius() > C2NavGrid.MAX_RADIUS)
            throw new IllegalArgumentException("Invalid snapshot radius");
        // B2 performs complete vertex/footY-to-snapshot validation before A*.
        // Enforce that invariant for standalone navregion diagnostics as well.
        Map<C2AStarPlanner.Target, Double> observed = new HashMap<>();
        for (C2NavGrid.Cell cell : snapshot.cells()) {
            if (!C2NavGrid.plannable(cell.condition())) continue;
            var target = new C2AStarPlanner.Target(cell.x(),cell.supportY(),cell.z());
            if (observed.putIfAbsent(target,cell.footY()) != null)
                throw new IllegalArgumentException("Duplicate walkable cell");
        }
        for (C2TraversabilityGraph.Node node : graph.nodes()) {
            if (Math.abs((long)node.x()-snapshot.originX()) > snapshot.radius()
                    || Math.abs((long)node.z()-snapshot.originZ()) > snapshot.radius()
                    || Math.abs((long)node.supportY()-snapshot.originY()) > C2NavGrid.VERTICAL_RADIUS)
                throw new IllegalArgumentException("Out-of-snapshot graph node");
            Double expectedFootY = observed.remove(new C2AStarPlanner.Target(
                    node.x(),node.supportY(),node.z()));
            if (expectedFootY==null || Double.compare(expectedFootY,node.footY())!=0)
                throw new IllegalArgumentException("Graph vertex/height does not match observation");
        }
        if (!observed.isEmpty()) throw new IllegalArgumentException("Graph misses walkable observations");

        long left = (long)snapshot.originX()-snapshot.radius();
        long near = (long)snapshot.originZ()-snapshot.radius();
        Map<Tile, List<C2TraversabilityGraph.Node>> tiles = new LinkedHashMap<>();
        for (C2TraversabilityGraph.Node node : graph.nodes()) {
            int tx = (int)Math.floorDiv((long)node.x()-left, tileSize);
            int tz = (int)Math.floorDiv((long)node.z()-near, tileSize);
            tiles.computeIfAbsent(new Tile(tx, tz), ignored -> new ArrayList<>()).add(node);
        }
        // Weak connectivity within a tile groups geometry without assuming
        // motion is reversible. Direction is respected ONLY by graph.neighbors
        // in local shortest path calculations and by directed portal edges.
        Map<C2TraversabilityGraph.Node,List<C2TraversabilityGraph.Node>> incoming = new HashMap<>();
        for (var e:graph.edges())
            incoming.computeIfAbsent(e.to(),ignored->new ArrayList<>()).add(e.from());
        Map<C2TraversabilityGraph.Node, Id> assigned = new LinkedHashMap<>();
        List<Region> regions = new ArrayList<>();
        for (var tileEntry : tiles.entrySet()) {
            Tile tile = tileEntry.getKey();
            Set<C2TraversabilityGraph.Node> inTile = new HashSet<>(tileEntry.getValue());
            int component = 0;
            for (C2TraversabilityGraph.Node seed : tileEntry.getValue()) {
                if (assigned.containsKey(seed)) continue;
                Id id = new Id(tile.x(), tile.z(), component++);
                List<C2TraversabilityGraph.Node> connected = new ArrayList<>();
                ArrayDeque<C2TraversabilityGraph.Node> queue = new ArrayDeque<>();
                assigned.put(seed, id);
                queue.add(seed);
                while (!queue.isEmpty()) {
                    var node = queue.removeFirst();
                    connected.add(node);
                    for (var edge : graph.neighbors(node)) {
                        var neighbor = edge.to();
                        if (inTile.contains(neighbor) && !assigned.containsKey(neighbor)) {
                            assigned.put(neighbor, id);
                            queue.addLast(neighbor);
                        }
                    }
                    for (var neighbor : incoming.getOrDefault(node,List.of())) {
                        if (inTile.contains(neighbor) && !assigned.containsKey(neighbor)) {
                            assigned.put(neighbor,id);
                            queue.addLast(neighbor);
                        }
                    }
                }
                regions.add(new Region(id, connected));
            }
        }
        List<Portal> portals = new ArrayList<>();
        for (var edge : graph.edges()) {
            Id a = assigned.get(edge.from());
            Id b = assigned.get(edge.to());
            if (a == null || b == null) throw new IllegalArgumentException("Graph edge outside Region nodes");
            if (!a.equals(b)) portals.add(new Portal(a, b, edge));
        }
        return new C2RegionGraph(regions, portals, assigned);
    }
}
