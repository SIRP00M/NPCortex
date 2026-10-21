package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** C2-P1-B3 pure Java 17 region/portal/A* tests; NOT Minecraft MSPT. */
public final class C2HierarchicalPlannerSelfTest {
    private static int checks;
    private static void ok(boolean passed,String description) {
        checks++; if(!passed) throw new AssertionError(description);
    }
    private static void reject(Runnable call,String description) {
        checks++;
        try {call.run();}
        catch(IllegalArgumentException | NullPointerException expected) {return;}
        throw new AssertionError("Expected rejection: "+description);
    }
    interface Terrain {C2NavGrid.Condition sample(int x,int y,int z);}
    private static C2NavGrid.Snapshot scan(int radius,Terrain fn) {
        return C2NavGrid.scan(0,0,0,radius,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,fn.sample(x,y,z));
            }
        });
    }
    private static C2NavGrid.Condition flat(int x,int y,int z) {
        return y==0?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.UNSUPPORTED;
    }
    private static C2TraversabilityGraph graph(C2NavGrid.Snapshot s) {
        return C2TraversabilityGraph.build(s,(a,b)->true);
    }
    private static C2AStarPlanner.Target at(int x,int y,int z) {
        return new C2AStarPlanner.Target(x,y,z);
    }
    private static C2HierarchicalPlanner.Result route(C2NavGrid.Snapshot s,C2TraversabilityGraph g,
                   C2AStarPlanner.Target a,C2AStarPlanner.Target b,int budget) {
        return C2HierarchicalPlanner.plan(g,s,a,b,4,budget);
    }
    private static void equalOptimal(C2NavGrid.Snapshot s,C2TraversabilityGraph g,
                    C2AStarPlanner.Target a,C2AStarPlanner.Target b) {
        var ref=C2AStarPlanner.plan(g,s,a,b,845);
        var got=route(s,g,a,b,C2HierarchicalPlanner.DEFAULT_BUDGET);
        ok(got.status().name().equals(ref.status().name()),"same status as B2: "+a+" "+b+" got="+got.status()+" vs "+ref.status());
        if (ref.found()) {
            ok(got.found(),"B3 found B2 path");
            ok(Math.abs(got.cost()-ref.cost())<1e-7,"optimal cost identical to B2");
            ok(got.path().get(0).x()==a.x() && got.path().get(got.path().size()-1).x()==b.x(),"correct endpoints X");
            ok(got.path().get(0).z()==a.z() && got.path().get(got.path().size()-1).z()==b.z(),"correct endpoints Z");
            for(int i=1;i<got.path().size();i++) {
                var before=got.path().get(i-1);var after=got.path().get(i);
                ok(g.neighbors(before).stream().anyMatch(e->e.to().equals(after)),"only real B1 edges");
            }
        } else ok(got.path().isEmpty() && got.cost()==0,"no fabricated failure path");
        var again=route(s,g,a,b,C2HierarchicalPlanner.DEFAULT_BUDGET);
        ok(again.status()==got.status() && again.path().equals(got.path())
            && again.cost()==got.cost() && again.expandedNodes()==got.expandedNodes(),"deterministic B3");
    }
    public static void main(String[] args) {
        var open=scan(6,C2HierarchicalPlannerSelfTest::flat);
        var g=graph(open);
        var regions=C2RegionGraph.build(g,open,4);
        ok(regions.regions().size()==16,"13x13 splits into 4x4=16 tile components");
        ok(regions.portals().size()>0,"cross-region portals from real graph edges");
        ok(regions.portals().stream().allMatch(p->g.edges().contains(p.edge())),"all portals are verified original edges");
        ok(regions.portals().stream().allMatch(p->!p.source().equals(p.destination())),"no fake intra-region portals");
        ok(regions.regions().stream().mapToInt(r->r.nodes().size()).sum()==g.nodes().size(),"all nodes assigned once");
        equalOptimal(open,g,at(-6,0,-6),at(6,0,6));
        equalOptimal(open,g,at(-4,0,2),at(3,0,-3));
        var first=route(open,g,at(-6,0,-6),at(6,0,6),12000);
        ok(first.source()==C2HierarchicalPlanner.Source.HIERARCHICAL,"flat path produced by hierarchy");
        ok(first.regionCount()==16 && first.portalCount()==regions.portals().size(),"region metadata");
        ok(first.expandedNodes()<=12000,"bounded total expansions");
        ok(route(open,g,at(0,0,0),at(0,0,0),1).found(),"start==goal under tight budget");
        ok(route(open,g,at(-6,0,-6),at(6,0,6),1).status()
                ==C2HierarchicalPlanner.Status.BUDGET_EXCEEDED,"budget cannot invent NO_PATH");
        ok(route(open,g,at(-6,0,-6),at(7,0,6),12000).status()
                ==C2HierarchicalPlanner.Status.OUT_OF_SCAN,"out of observed grid");
        ok(route(open,g,at(7,0,0),at(1,0,1),12000).status()
                ==C2HierarchicalPlanner.Status.INVALID_START,"invalid source");
        ok(route(open,g,at(0,0,0),at(1,1,1),12000).status()
                ==C2HierarchicalPlanner.Status.UNSAFE_TARGET,"unobserved height not safe");
        reject(()->route(open,g,at(0,0,0),at(1,0,0),0),"zero budget");
        reject(()->C2HierarchicalPlanner.plan(g,open,at(0,0,0),at(1,0,0),1,12000),"tile size <2");
        reject(()->C2HierarchicalPlanner.plan(g,open,at(0,0,0),at(1,0,0),7,12000),"tile size >6");
        reject(()->route(open,g,at(0,0,0),at(1,0,0),24001),"budget overcap");

        // A single geographic tile can contain TWO disconnected components;
        // geometry-only Region joins would fabricate a crossing at x=0.
        var oneTileSplit=scan(2,(x,y,z)->y==0 && x==0
                ?C2NavGrid.Condition.OBSTRUCTED:flat(x,y,z));
        var oneTileGraph=graph(oneTileSplit);
        var oneTileRegions=C2RegionGraph.build(oneTileGraph,oneTileSplit,6);
        ok(oneTileRegions.regions().size()==2,"one tile correctly contains two disconnected regions");
        ok(oneTileRegions.portals().isEmpty(),"no invented portal across wall inside single tile");
        equalOptimal(oneTileSplit,oneTileGraph,at(-1,0,0),at(1,0,0));
        // Verify multiple snapshots; hierarchy must recalculate on fresh input.
        var randomized = new java.util.Random(70329);
        for (int iteration=0;iteration<40;iteration++) {
            var obstructions=new java.util.HashSet<String>();
            for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) {
                if (x==-3&&z==-3 || x==3&&z==3) continue;
                if (randomized.nextDouble()<0.22) obstructions.add(x+":"+z);
            }
            var sampled=scan(3,(x,y,z)->y==0 && obstructions.contains(x+":"+z)
                    ?C2NavGrid.Condition.OBSTRUCTED:flat(x,y,z));
            equalOptimal(sampled,graph(sampled),at(-3,0,-3),at(3,0,3));
        }
        var wall=scan(6,(x,y,z)->y==0 && x==0 && Math.abs(z)<5
                ?C2NavGrid.Condition.OBSTRUCTED:flat(x,y,z));
        var wallGraph=graph(wall);
        equalOptimal(wall,wallGraph,at(-5,0,0),at(5,0,0));
        var blocked=scan(6,(x,y,z)->y==0 && x==0
                ?C2NavGrid.Condition.OBSTRUCTED:flat(x,y,z));
        var blockedGraph=graph(blocked);
        equalOptimal(blocked,blockedGraph,at(-5,0,0),at(5,0,0));
        var split=scan(3,(x,y,z)->y==0 && x==-2 && z==-1
                ?C2NavGrid.Condition.OBSTRUCTED:flat(x,y,z));
        var splitGraph=graph(split);
        var splitRegions=C2RegionGraph.build(splitGraph,split,4);
        ok(splitRegions.portals().stream().allMatch(p->splitGraph.edges().contains(p.edge())),"portal safety with partial wall");
        var hazard=scan(4,(x,y,z)->y==0 && x==0 && z==0
                ?C2NavGrid.Condition.HAZARDOUS:flat(x,y,z));
        equalOptimal(hazard,graph(hazard),at(-4,0,0),at(4,0,0));
        var tall=scan(4,(x,y,z)-> y==(x<=0?0:1)
                ?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.UNSUPPORTED);
        var tallGraph=graph(tall);
        equalOptimal(tall,tallGraph,at(-3,0,0),at(3,1,0));
        equalOptimal(tall,tallGraph,at(1,1,0),at(3,1,0));
        var unloaded=C2NavGrid.scan(0,0,0,4,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return x!=0;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,flat(x,y,z));
            }
        });
        equalOptimal(unloaded,graph(unloaded),at(-3,0,0),at(3,0,0));
        var collision=C2TraversabilityGraph.build(open,(a,b)->false);
        equalOptimal(open,collision,at(-3,0,0),at(3,0,0));
        reject(()->C2RegionGraph.build(g,blocked,4),"stale graph/snapshot mismatch");
        checks++;
        try {regions.regions().clear();throw new AssertionError("Region list mutable");}
        catch(UnsupportedOperationException expected) {}
        checks++;
        try {first.path().clear();throw new AssertionError("Path list mutable");}
        catch(UnsupportedOperationException expected) {}
        long begin=System.nanoTime();
        for(int i=0;i<10;i++) route(open,g,at(-6,0,-6),at(6,0,6),12000);
        double elapsed=(System.nanoTime()-begin)/1_000_000.0;
        ok(Double.isFinite(elapsed) && elapsed>=0,"pure JVM benchmark finite");
        System.out.printf(java.util.Locale.ROOT,
            "PASS C2 P1-B3 Hierarchical Region Planner: %d assertions; %d regions/%d directed portals; pure Java 10 plans=%.2fms (NOT Forge MSPT)%n",
            checks,regions.regions().size(),regions.portals().size(),elapsed);
    }
}
