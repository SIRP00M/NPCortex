package io.github.sirp00m.npcortex.agent;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded voxel search independent of trees, Forge and Qwen. Never mutates a world.
 * WALK covers horizontal moves and one-block steps. PILLAR is a real jump/place;
 * BRIDGE places one supported adjacent floor block before walking onto it.
 * Weighted A* prioritizes usable routes over optimal material cost.
 * Virtual supports are part of search identity, not merely the current position.
 */
public final class Navigation3D {
    public record Cell(int x, int y, int z) {
        public Cell add(int dx, int dy, int dz) { return new Cell(x+dx, y+dy, z+dz); }
    }
    public enum Move { WALK, PILLAR, BRIDGE }
    public record Step(Move move, Cell from, Cell to, Cell placed) {}
    public record Plan(List<Step> steps, int materials, int expanded, boolean reached) {
        public Plan(List<Step> steps, int materials, int expanded) { this(steps,materials,expanded,true); }
        public Plan { steps = List.copyOf(steps); }
    }
    public interface World {
        boolean allowed(Cell cell); // includes loaded chunks, bounds and hazards
        boolean clear(Cell cell);
        // Walkable vegetation is not necessarily replaceable without terrain damage.
        default boolean placeable(Cell cell) { return clear(cell); }
        boolean support(Cell cell);
        boolean goal(Cell feet);
        double estimate(Cell feet);
    }
    private record Key(Cell at, Set<Cell> placed) {}
    private record Node(Cell at, Set<Cell> placed, List<Step> steps, double cost) {}
    // Per-segment work, NOT a lifetime height/distance/edit quota.
    public static final int MAX_EDITS = 24, MAX_STEPS = 64, MAX_NODES = 2400;
    public enum Status { COMPLETE, PARTIAL, SEARCH_BUDGET, NO_PATH, INVALID_START }
    public record SearchResult(Status status, Plan route, int expanded) {}
    private static boolean empty(World w, Cell p, Set<Cell> placed) {
        return w.allowed(p) && !placed.contains(p) && w.clear(p);
    }
    private static boolean placeable(World w, Cell p, Set<Cell> placed) {
        return w.allowed(p) && !placed.contains(p) && w.placeable(p);
    }
    private static boolean solid(World w, Cell p, Set<Cell> placed) {
        return w.allowed(p) && (placed.contains(p) || w.support(p));
    }
    private static boolean body(World w, Cell p, Set<Cell> placed) {
        return empty(w,p,placed) && empty(w,p.add(0,1,0),placed);
    }
    /** One physical, walk-only flood fill for exploration frontiers.
     * Finds actual connected standing positions instead of hallucinating that an
     * isolated safe tile can be reached. No blocks are placed and only loaded,
     * supported, collision-safe cells may be visited. Recomputed on execution.
     */
    public record TerrainRoutes(Cell start, Map<Cell, Step> parents, int expanded) {
        public TerrainRoutes { parents = Map.copyOf(parents); }
        public Set<Cell> destinations() { return parents.keySet(); }
        public Plan routeTo(Cell target) {
            if(target.equals(start)) return new Plan(List.of(),0,expanded,true);
            if(!parents.containsKey(target)) return null;
            var reversed=new ArrayList<Step>();
            Cell at=target;
            while(!at.equals(start)) {
                Step step=parents.get(at);
                if(step==null) return null;
                reversed.add(step); at=step.from();
            }
            Collections.reverse(reversed);
            return new Plan(reversed,0,expanded,true);
        }
    }
    public static TerrainRoutes terrainRoutes(World world, Cell start, int radius,
            int maxDepth, int maxNodes) {
        if(!body(world,start,Set.of()) || !solid(world,start.add(0,-1,0),Set.of()))
            return new TerrainRoutes(start,Map.of(),0);
        var parents=new LinkedHashMap<Cell,Step>();
        var distance=new LinkedHashMap<Cell,Integer>();
        var queue=new ArrayDeque<Cell>();
        distance.put(start,0); queue.add(start);
        int expanded=0;
        while(!queue.isEmpty() && expanded<maxNodes) {
            Cell from=queue.removeFirst();
            expanded++;
            int depth=distance.get(from);
            if(depth>=maxDepth) continue;
            for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                for(int dy:new int[]{0,1,-1}) {
                    Cell to=from.add(dir[0],dy,dir[1]);
                    if(Math.abs(to.x()-start.x())>radius || Math.abs(to.z()-start.z())>radius
                            || Math.abs(to.y()-start.y())>6 || distance.containsKey(to)) continue;
                    if(!body(world,to,Set.of()) || !solid(world,to.add(0,-1,0),Set.of())) continue;
                    if(dy==1 && !empty(world,from.add(0,2,0),Set.of())) continue;
                    if(dy==-1 && !empty(world,to.add(0,2,0),Set.of())) continue;
                    parents.put(to,new Step(Move.WALK,from,to,null));
                    distance.put(to,depth+1); queue.addLast(to);
                }
        }
        return new TerrainRoutes(start,parents,expanded);
    }
    public static Plan plan(World world, Cell start, int materials) {
        SearchResult result=search(world,start,materials,false);
        return result.route();
    }
    public static SearchResult search(World world, Cell start, int materials, boolean allowPartial) {
        int budget = Math.max(0, Math.min(MAX_EDITS, materials));
        if (!body(world,start,Set.of()) || !solid(world,start.add(0,-1,0),Set.of()))
            return new SearchResult(Status.INVALID_START,null,0);
        var open = new PriorityQueue<Node>(java.util.Comparator.comparingDouble(
                n -> n.cost + 4.0 * world.estimate(n.at)));
        var seen = new HashSet<Key>();
        open.add(new Node(start,Set.of(),List.of(),0));
        int expanded=0;
        Node best=null;
        double initial=world.estimate(start), bestEstimate=initial;
        boolean limited=false;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node n = open.remove();
            if (!seen.add(new Key(n.at,n.placed))) continue;
            expanded++;
            if (world.goal(n.at)) return new SearchResult(Status.COMPLETE,
                    new Plan(n.steps,n.placed.size(),expanded,true),expanded);
            double estimate=world.estimate(n.at);
            if (!n.steps.isEmpty() && estimate < bestEstimate - 0.01) {
                best=n; bestEstimate=estimate;
            }
            if (n.steps.size() >= MAX_STEPS) { limited=true; continue; }
            if (budget>0 && n.placed.size()>=budget) limited=true;
            for (int[] d : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
                for (int dy : new int[] {0,1,-1}) {
                    Cell to=n.at.add(d[0],dy,d[1]);
                    if (!body(world,to,n.placed) || !solid(world,to.add(0,-1,0),n.placed)) continue;
                    // Up/down transitions need clearance through the swept upper corner.
                    if (dy==1 && !empty(world,n.at.add(0,2,0),n.placed)) continue;
                    if (dy==-1 && !empty(world,to.add(0,2,0),n.placed)) continue;
                    offer(open,n,Move.WALK,to,null,1.0+Math.abs(dy)*0.5);
                }
                Cell to=n.at.add(d[0],0,d[1]), floor=to.add(0,-1,0);
                if (n.placed.size()<budget && body(world,to,n.placed)
                        && placeable(world,floor,n.placed) && solid(world,n.at.add(0,-1,0),n.placed))
                    offer(open,n,Move.BRIDGE,to,floor,4.0);
            }
            Cell up=n.at.add(0,1,0);
            if (n.placed.size()<budget && body(world,up,n.placed)
                    && placeable(world,n.at,n.placed) && solid(world,n.at.add(0,-1,0),n.placed))
                offer(open,n,Move.PILLAR,up,n.at,4.5);
        }
        limited |= !open.isEmpty();
        // A partial plan is explicitly NOT target arrival. Strict improvement
        // prevents returning a circular segment merely because the queue filled.
        if (allowPartial && best!=null && initial-bestEstimate>=1.0)
            return new SearchResult(Status.PARTIAL,
                    new Plan(best.steps,best.placed.size(),expanded,false),expanded);
        return new SearchResult(limited ? Status.SEARCH_BUDGET : Status.NO_PATH,null,expanded);
    }
    private static void offer(PriorityQueue<Node> open, Node n, Move move, Cell to, Cell placed, double cost) {
        // Bound queued allocations too, including duplicate states not yet expanded.
        if (open.size() >= MAX_NODES * 4) return;
        var edits=new HashSet<>(n.placed);
        if (placed!=null) edits.add(placed);
        var path=new ArrayList<>(n.steps);
        path.add(new Step(move,n.at,to,placed));
        open.add(new Node(to,Set.copyOf(edits),List.copyOf(path),n.cost+cost));
    }
    private Navigation3D() {}
}
