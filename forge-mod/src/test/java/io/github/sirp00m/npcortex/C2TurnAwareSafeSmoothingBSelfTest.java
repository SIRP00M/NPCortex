package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.Set;

/** B: isolated algorithm contracts, NOT Forge/Vanilla gameplay certification. */
public final class C2TurnAwareSafeSmoothingBSelfTest {
    private static int assertions;
    private static void ok(boolean condition,String reason) {
        assertions++;
        if(!condition) throw new AssertionError(reason);
    }
    private static C2NavGrid.Snapshot flat(Set<String> gaps) {
        return C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,
                        y==68 && !gaps.contains(x+","+z)
                        ? C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    private static C2TraversabilityGraph graph(C2NavGrid.Snapshot snap) {
        return C2TraversabilityGraph.build3D(snap,(a,b,kind)->kind==C2TraversabilityGraph.MoveKind.WALK);
    }
    private static C2TraversabilityGraph.Node n(C2TraversabilityGraph g,int x,int z) {
        return g.nodes().stream().filter(v->v.x()==x&&v.z()==z).findFirst().orElseThrow();
    }
    private static double turns(List<C2TraversabilityGraph.Node> path) {
        double penalty=0;
        for(int i=2;i<path.size();i++) {
            var a=path.get(i-2);var b=path.get(i-1);var c=path.get(i);
            penalty+=C2TurnAwarePlanner.turnCost(Integer.compare(b.x(),a.x()),
                    Integer.compare(b.z(),a.z()),Integer.compare(c.x(),b.x()),
                    Integer.compare(c.z(),b.z()));
        }
        return penalty;
    }
    public static void main(String[] args) {
        var scan=flat(Set.of());var open=graph(scan);
        var start=new C2AStarPlanner.Target(0,68,0);
        var end=new C2AStarPlanner.Target(3,68,2);
        var turned=C2TurnAwarePlanner.plan(open,scan,start,end,3000);
        ok(turned.found(),"bounded turn-aware plan succeeds on open ground");
        ok(turned.source()==C2HierarchicalPlanner.Source.FOLLOW_TURN_AWARE,
                "follow path has distinct source rather than claiming ordinary hierarchy");
        ok(turned.path().get(0).equals(n(open,0,0))&&
                turned.path().get(turned.path().size()-1).equals(n(open,3,2)),
                "plan preserves exact start and goal");
        ok(turned.expandedNodes()<=3000,"turn-aware expansion budget respected");
        double base=0;
        for(int i=1;i<turned.path().size();i++) {
            var a=turned.path().get(i-1);var b=turned.path().get(i);
            var edge=open.neighbors(a).stream().filter(e->e.to().equals(b)).findFirst().orElseThrow();
            base+=edge.cost();
        }
        ok(Math.abs(turned.cost()-base-turns(turned.path()))<1e-7,
                "physical/risk edge cost plus turn cost preserved");
        ok(C2TurnAwarePlanner.turnCost(1,0,1,0)==0,"straight legs incur no turn cost");
        ok(C2TurnAwarePlanner.turnCost(1,0,0,1)>0,"right-angle turn penalized");
        ok(C2TurnAwarePlanner.turnCost(1,0,-1,0)>
                C2TurnAwarePlanner.turnCost(1,0,0,1),"U-turn cost exceeds right angle");
        var shortcut=C2F2Locomotion.smooth(open,turned.path());
        ok(shortcut.size()==2,"open flat zigzag can become safe any-angle straight segment");
        ok(shortcut.get(0).equals(n(open,0,0))&&shortcut.get(1).equals(n(open,3,2)),
                "shortcut ends on verified path endpoints");
        var owned=C2WaypointExecution.followSmoothed(shortcut);
        ok(owned.walkChainAllowed(open,1),"C1 ticket bridge proof accepts clean any-angle shortcut");
        var gapScan=flat(Set.of("1,1"));var gapGraph=graph(gapScan);
        ok(!C2F2SafeShortcut.clear(gapGraph,n(gapGraph,0,0),n(gapGraph,3,2)),
                "unobserved/unsupported internal column prohibits line shortcut");
        ok(!owned.walkChainAllowed(gapGraph,1),"fresh graph safety rejects obsolete shortcut");
        var risky=C2TraversabilityGraph.build3DWithRisk(scan,(a,b,k)->
                (a.x()==1&&a.z()==1)||(b.x()==1&&b.z()==1)
                        ? new C2TraversalCost.Assessment(true,5)
                        : C2TraversalCost.Assessment.clear());
        ok(!C2F2SafeShortcut.clear(risky,n(risky,0,0),n(risky,3,2)),
                "risky interior of bounding rectangle cannot be silently shortcutted");
        ok(!owned.walkChainAllowed(risky,1),"risk added since plan fails revalidation");
        var riskPlan=C2TurnAwarePlanner.plan(risky,scan,start,end,3000);
        ok(riskPlan.found(),"turn-aware planner finds a risk-aware path");
        boolean riskyEdge=false;
        for(int i=1;i<riskPlan.path().size();i++) {
            var a=riskPlan.path().get(i-1);var b=riskPlan.path().get(i);
            if((a.x()==1&&a.z()==1)||(b.x()==1&&b.z()==1)) riskyEdge=true;
        }
        ok(!riskyEdge,"risk cost outweighs turn preference");
        var normal=C2AStarPlanner.plan(open,scan,start,end,C2AStarPlanner.MAX_EXPANSION_BUDGET);
        ok(normal.found()&&normal.cost()<=turned.cost()+1e-7,
                "static ordinary A* remains available with unmodified cost semantics");
        var limit=C2TurnAwarePlanner.plan(open,scan,start,end,1);
        ok(limit.status()==C2HierarchicalPlanner.Status.BUDGET_EXCEEDED,
                "low budget stops search rather than expanding without bound");
        var noWay=C2TurnAwarePlanner.plan(gapGraph,gapScan,start,
                new C2AStarPlanner.Target(1,68,1),3000);
        ok(noWay.status()==C2HierarchicalPlanner.Status.UNSAFE_TARGET,
                "obstructed destination rejected");
        var same=C2TurnAwarePlanner.plan(open,scan,start,start,3000);
        ok(same.found()&&same.path().size()==1,"same-node follow HOLD remains valid");
        System.out.println("PASS C2 Turn-Aware + Safe Smoothing B: "+assertions+" assertions (pure JVM)");
    }
}
