package io.github.sirp00m.npcortex;

/** C1-C4 isolated risk-cost/graph/planner tests. Does not emulate Forge collision. */
public final class C2GeneralizedTraversalCSelfTest {
    private static int checks;
    private static void ok(boolean pass, String note) {
        checks++;
        if (!pass) throw new AssertionError(note);
    }
    private static void rejects(Runnable action, String note) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection: "+note);
    }
    private static C2NavGrid.Snapshot flat() {
        return C2NavGrid.scan(0,0,0,2,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) { return true; }
            public C2NavGrid.Cell sample(int x,int y,int z) {
                boolean ground=y==0;
                double penalty=ground && x==1 && z==0 ? 8.0 : 0.0;
                return new C2NavGrid.Cell(x,y,z,y+1,
                        ground?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.UNSUPPORTED,penalty);
            }
        });
    }
    private static C2TraversabilityGraph.Node n(int x,int z) {
        return new C2TraversabilityGraph.Node(x,0,z,1);
    }
    public static void main(String[] args) {
        var clear=C2TraversalCost.Assessment.clear();
        var slow=C2TraversalCost.fromRisk(C2TraversalCost.Risk.COSTLY,5);
        ok(clear.passable() && clear.penalty()==0,"clear remains passable and free");
        ok(slow.passable() && slow.penalty()==5,"soft hazard costs without blocking");
        ok(!C2TraversalCost.fromRisk(C2TraversalCost.Risk.FORBIDDEN,0).passable(),"hard hazard forbidden");
        ok(!C2TraversalCost.fromRisk(C2TraversalCost.Risk.UNKNOWN,0).passable(),"unknown fails closed");
        ok(C2TraversalCost.edgeCost(1.0,slow)==6,"penalty adds to edge cost");
        rejects(()->new C2TraversalCost.Assessment(false,1),"blocked cannot claim penalty");
        rejects(()->new C2TraversalCost.Assessment(true,Double.NaN),"nonfinite penalty");
        rejects(()->C2TraversalCost.edgeCost(1.0,C2TraversalCost.Assessment.blocked()),
                "cannot cost rejected edge");
        rejects(()->new C2NavGrid.Cell(0,0,0,1,C2NavGrid.Condition.UNLOADED,1),
                "no risk on unloaded sentinel");
        rejects(()->new C2NavGrid.Cell(0,0,0,1,C2NavGrid.Condition.WALKABLE,-1),
                "no negative cell penalty");
        var grid=flat();
        ok(grid.cells().stream().anyMatch(c->c.x()==1 && c.z()==0 && c.riskPenalty()==8),
                "soft hazard remains a graph candidate");
        var graph=C2TraversabilityGraph.build3DWithRisk(grid,(from,to,kind)-> {
            if ((from.x()==1 && from.z()==0) || (to.x()==1 && to.z()==0))
                return new C2TraversalCost.Assessment(true,8);
            return clear;
        });
        ok(graph.contains(n(1,0)),"soft hazard not misclassified as wall");
        var floorCostGraph=C2TraversabilityGraph.build3DWithRisk(grid,(from,to,kind)->clear);
        ok(floorCostGraph.neighbors(n(0,0)).stream().anyMatch(e ->
                e.to().equals(n(1,0)) && e.cost()>=9),
                "floor risk propagates into graph even if body sweep is clear");
        ok(graph.neighbors(n(0,0)).stream().anyMatch(e->e.to().equals(n(1,0))
                && e.cost()>=9),"soft hazard increases directional edge cost");
        var result=C2AStarPlanner.plan(graph,grid,new C2AStarPlanner.Target(0,0,0),
                new C2AStarPlanner.Target(2,0,0),C2AStarPlanner.MAX_EXPANSION_BUDGET);
        ok(result.found(),"path still found");
        ok(result.path().stream().noneMatch(v->v.equals(n(1,0))),
                "A-star avoids expensive traversable cell when safe detour exists");
        ok(result.cost()<10,"safe detour cheaper than risky shortcut");
        var hierarchical=C2HierarchicalPlanner.plan(graph,grid,new C2AStarPlanner.Target(0,0,0),
                new C2AStarPlanner.Target(2,0,0),C2RegionGraph.DEFAULT_TILE_SIZE,
                C2AStarPlanner.MAX_EXPANSION_BUDGET);
        ok(hierarchical.found(),"hierarchical route found");
        ok(hierarchical.path().stream().noneMatch(v->v.equals(n(1,0))),
                "hierarchical route respects same risk weights");
        ok(Math.abs(hierarchical.cost()-result.cost())<1e-8,
                "weighted A-star and hierarchical cost agree");
        var hard=C2TraversabilityGraph.build3DWithRisk(grid,(from,to,kind)->
                to.x()==1 && to.z()==0 || from.x()==1 && from.z()==0
                        ? C2TraversalCost.Assessment.blocked() : clear);
        ok(hard.neighbors(n(0,0)).stream().noneMatch(e->e.to().equals(n(1,0))),
                "hard hazard has no executable edge");
        ok(hard.neighbors(n(1,0)).isEmpty(),"hard hazard cannot escape through forbidden edge");
        // Preserve bidirectional safety of flat pairs; never infer reverse from one clearance.
        var directional=C2TraversabilityGraph.build3DWithRisk(grid,(from,to,kind)->
                from.x()<to.x() ? C2TraversalCost.Assessment.blocked() : clear);
        ok(directional.neighbors(n(0,0)).stream().noneMatch(e->e.to().equals(n(1,0))),
                "cardinal collision must be proven in BOTH directions");
        System.out.println("PASS C2 Generalized Traversal C: "+checks+" assertions (pure Java)");
    }
}
