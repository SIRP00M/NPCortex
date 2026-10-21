package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** C2-P1-B2 pure-Java contract/regression suite. NO Forge MSPT/physics claim. */
public final class C2AStarPlannerSelfTest {
    private static int checks;
    private static void ok(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static void reject(Runnable runnable,String description) {
        checks++;
        try { runnable.run(); }
        catch (IllegalArgumentException | NullPointerException expected) { return; }
        throw new AssertionError("Should reject: "+description);
    }
    interface Terrain { C2NavGrid.Condition at(int x,int y,int z); }
    private static final C2TraversabilityGraph.ClearanceProbe CLEAR=(a,b)->true;
    private static C2NavGrid.Snapshot grid(int radius,Terrain terrain) {
        return C2NavGrid.scan(0,0,0,radius,new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return true; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1.0,terrain.at(x,y,z));
            }
        });
    }
    private static C2NavGrid.Condition flat(int x,int y,int z) {
        return y==0 ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED;
    }
    private static C2AStarPlanner.Target at(int x,int y,int z) {
        return new C2AStarPlanner.Target(x,y,z);
    }
    private static C2AStarPlanner.Result search(C2NavGrid.Snapshot scan,
                                                 C2TraversabilityGraph graph,
                                                 C2AStarPlanner.Target start,
                                                 C2AStarPlanner.Target target,int budget) {
        return C2AStarPlanner.plan(graph,scan,start,target,budget);
    }
    private static void verifyLegalPath(C2TraversabilityGraph g,C2AStarPlanner.Result result) {
        checks++;
        if (!result.found()) throw new AssertionError("No path: "+result.status());
        for (int i=1;i<result.path().size();i++) {
            var previous=result.path().get(i-1);
            var current=result.path().get(i);
            ok(g.neighbors(previous).stream().anyMatch(e->e.to().equals(current)),"all edges exist");
        }
    }
    public static void main(String[] args) {
        var scan=grid(2,C2AStarPlannerSelfTest::flat);
        var graph=C2TraversabilityGraph.build(scan,CLEAR);
        var origin=at(-2,0,0);var goal=at(2,0,0);
        var found=search(scan,graph,origin,goal,845);
        ok(found.status()==C2AStarPlanner.Status.PATH_FOUND,"flat path found");
        ok(found.path().size()==5,"shortest cardinal route has 5 nodes");
        ok(found.cost()==4.0,"flat optimum cost 4");
        ok(found.expandedNodes()>0 && found.expandedNodes()<=25,"finite expansions");
        ok(found.visitedNodes()>=found.path().size(),"visited at least path nodes");
        ok(found.path().get(0).x()==-2 && found.path().get(4).x()==2,"endpoints exact");
        verifyLegalPath(graph,found);
        var same=search(scan,graph,origin,origin,1);
        ok(same.found() && same.cost()==0.0 && same.path().size()==1,"start==target");
        ok(same.expandedNodes()==0,"start==target has no expansion");
        ok(search(scan,graph,origin,goal,1).status()==C2AStarPlanner.Status.BUDGET_EXCEEDED,
                "budget exhausted before route complete");
        ok(search(scan,graph,origin,goal,4).found(),"exact four expansions enough");
        ok(search(scan,graph,origin,at(3,0,0),845).status()==C2AStarPlanner.Status.OUT_OF_SCAN,
                "target X outside radius");
        ok(search(scan,graph,origin,at(0,3,0),845).status()==C2AStarPlanner.Status.OUT_OF_SCAN,
                "target Y outside scan height");
        ok(search(scan,graph,at(4,0,0),goal,845).status()==C2AStarPlanner.Status.INVALID_START,
                "start outside scan");
        ok(search(scan,graph,at(0,1,0),goal,845).status()==C2AStarPlanner.Status.INVALID_START,
                "start not a graph node");
        ok(search(scan,graph,origin,at(0,1,0),845).status()==C2AStarPlanner.Status.UNSAFE_TARGET,
                "non-walkable target is unsafe");
        ok(search(scan,graph,origin,at(Integer.MAX_VALUE,0,0),845).status()==C2AStarPlanner.Status.OUT_OF_SCAN,
                "coordinate extremes cannot overflow bound checking");
        reject(()->search(scan,graph,origin,goal,0),"zero budget");
        reject(()->search(scan,graph,origin,goal,846),"overbudget");
        reject(()->search(null,graph,origin,goal,845),"snapshot missing");
        reject(()->search(scan,null,origin,goal,845),"graph missing");
        for (int i=0;i<12;i++)
            ok(search(scan,graph,origin,goal,845).equals(found),"deterministic result #"+i);
        checks++;
        try { found.path().clear();throw new AssertionError("mutable path"); }
        catch (UnsupportedOperationException expected) { }

        // A 3-wide wall with one-cell edge corridor: A* must go AROUND, no diagonal.
        var wallScan=grid(2,(x,y,z)-> y==0 && x==0 && Math.abs(z)<=1
                ? C2NavGrid.Condition.OBSTRUCTED : flat(x,y,z));
        var wallGraph=C2TraversabilityGraph.build(wallScan,CLEAR);
        var detour=search(wallScan,wallGraph,origin,goal,845);
        ok(detour.found(),"route around wall available");
        ok(detour.cost()==8.0,"optimal detour length is eight steps");
        ok(detour.path().stream().anyMatch(n->Math.abs(n.z())==2),"detour uses wall opening");
        verifyLegalPath(wallGraph,detour);
        var closedScan=grid(2,(x,y,z)-> y==0 && x==0
                ? C2NavGrid.Condition.OBSTRUCTED : flat(x,y,z));
        reject(()->search(closedScan,graph,origin,goal,845),
                "wrong graph/snapshot pair refused");
        var closedGraph=C2TraversabilityGraph.build(closedScan,CLEAR);
        var closed=search(closedScan,closedGraph,origin,goal,845);
        ok(closed.status()==C2AStarPlanner.Status.NO_PATH,"closed wall has no route");
        ok(closed.path().isEmpty() && closed.cost()==0,"no invented path or cost");
        ok(search(closedScan,closedGraph,origin,goal,1).status()==C2AStarPlanner.Status.BUDGET_EXCEEDED,
                "budget status distinct from proven no path");

        var hazardScan=grid(2,(x,y,z)-> y==0 && x==0 && z==0
                ? C2NavGrid.Condition.HAZARDOUS : flat(x,y,z));
        var hazardGraph=C2TraversabilityGraph.build(hazardScan,CLEAR);
        var hazardResult=search(hazardScan,hazardGraph,origin,goal,845);
        ok(hazardResult.found() && hazardResult.cost()==6,"hazard cell avoided");
        ok(hazardResult.path().stream().noneMatch(n->n.x()==0 && n.z()==0),"no hazard traversed");
        verifyLegalPath(hazardGraph,hazardResult);

        var multiLevelScan=grid(2,(x,y,z)-> y==(x<0?0:1)
                ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED);
        var multiLevelGraph=C2TraversabilityGraph.build(multiLevelScan,CLEAR);
        ok(search(multiLevelScan,multiLevelGraph,at(-2,0,0),at(2,1,0),845).status()
                ==C2AStarPlanner.Status.NO_PATH,"3D nodes but no invented jump/step transitions");
        ok(search(multiLevelScan,multiLevelGraph,at(0,1,0),at(2,1,0),845).found(),
                "same elevated plane is traversable");

        var unloaded=C2NavGrid.scan(0,0,0,2,new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return x!=0; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,flat(x,y,z));
            }
        });
        var unloadedGraph=C2TraversabilityGraph.build(unloaded,CLEAR);
        ok(search(unloaded,unloadedGraph,origin,goal,845).status()==C2AStarPlanner.Status.NO_PATH,
                "unloaded border cannot become phantom route");
        ok(search(unloaded,unloadedGraph,origin,at(0,0,0),845).status()==C2AStarPlanner.Status.UNSAFE_TARGET,
                "unloaded target is unsafe (not confirmed walkable)");

        // Clearance probe denial prevents path even though snapshot is walkable.
        var noClear=C2TraversabilityGraph.build(scan,(a,b)->false);
        ok(search(scan,noClear,origin,goal,845).status()==C2AStarPlanner.Status.NO_PATH,
                "collision veto blocks disconnected path");
        var maxScan=grid(6,C2AStarPlannerSelfTest::flat);
        var maxGraph=C2TraversabilityGraph.build(maxScan,CLEAR);
        var maxResult=search(maxScan,maxGraph,at(-6,0,-6),at(6,0,6),845);
        ok(maxResult.found() && maxResult.cost()==24.0,"maximum scan radius route optimal");
        ok(maxResult.path().size()==25,"24 edges in max route");
        long began=System.nanoTime();
        for (int i=0;i<150;i++) search(maxScan,maxGraph,at(-6,0,-6),at(6,0,6),845);
        double ms=(System.nanoTime()-began)/1_000_000.0;
        ok(Double.isFinite(ms) && ms>=0,"bounded pure JVM timing");
        System.out.printf(java.util.Locale.ROOT,
                "PASS C2 P1-B2 AStarPlanner: %d assertions; maxFlat=%d nodes/%d edges; pure Java 150 plans=%.2fms (NOT Forge MSPT)%n",
                checks,maxGraph.nodes().size(),maxGraph.edges().size(),ms);
    }
}
