package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.Set;

/** H1-H4 isolated contracts. These do not certify Minecraft physics/client. */
public final class C2BPlusAdaptiveSteeringSelfTest {
    private static int tests;
    private static void ok(boolean b,String m) { tests++;if(!b)throw new AssertionError(m); }
    static C2NavGrid.Snapshot scan(Set<String> gaps) {
        return C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z){return true;}
            public C2NavGrid.Cell sample(int x,int y,int z){return new C2NavGrid.Cell(x,y,z,y+1,
                    y==68 && !gaps.contains(x+","+z) ? C2NavGrid.Condition.WALKABLE
                            : C2NavGrid.Condition.OBSTRUCTED);}
        });
    }
    static C2TraversabilityGraph.Node node(C2TraversabilityGraph g,int x,int z) {
        return g.nodes().stream().filter(n->n.x()==x&&n.z()==z).findFirst().orElseThrow();
    }
    static void checkSteering() {
        var a=C1F2AdaptiveSteering.onSegment(.5,.5,3.5,2.5,.5,.5);
        ok(a.x()>.5&&a.z()>.5,"lookahead is a true off-grid heading");
        ok(Math.abs((a.x()-.5)*2-(a.z()-.5)*3)<1e-8,"lookahead stays on verified segment");
        var b=C1F2AdaptiveSteering.onSegment(.5,.5,3.5,2.5,2,1.5);
        ok(b.x()>a.x(),"projection proceeds continuously as actor progresses");
        var end=C1F2AdaptiveSteering.onSegment(.5,.5,3.5,2.5,3.4,2.45);
        ok(end.x()==3.5&&end.z()==2.5,"endpoint receipt is preserved");
        var off=C1F2AdaptiveSteering.onSegment(.5,.5,3.5,2.5,2,4);
        ok(off.x()==3.5&&off.z()==2.5,"outside lane falls back to committed endpoint");
        try{C1F2AdaptiveSteering.onSegment(0,0,1,0,Double.NaN,0);throw new AssertionError();}
        catch(IllegalArgumentException expected){tests++;}
        var yui=new C1F2ContinuousWalk(.5,69,.5,
                List.of(new C1F2ContinuousWalk.Point(3.5,69,2.5)));
        var step=yui.next(.5,69,.5,0);
        ok(step!=null&&Math.abs(step.targetX()-3.5)<1e-8,
                "adaptive aim does not alter committed receipt target");
        ok(Math.abs(step.yaw())>1,"C1 forward steering accepts arbitrary heading");
        yui.cancel();ok(yui.next(.5,69,.5,0)==null,"cancel never resurrects movement");
    }
    static void checkCorridor() {
        var base=scan(Set.of());var g=C2TraversabilityGraph.build3D(base,(a,b,k)->
                k==C2TraversabilityGraph.MoveKind.WALK);
        var start=node(g,0,0);var end=node(g,3,2);
        ok(C2BPlusCorridor.clear(g,start,end),"valid level corridor certified");
        // (3,0) is in enclosing rectangle but clear of swept body;
        // B+ must not reject the entire shortcut because of this cell.
        var far=scan(Set.of("3,0"));var farGraph=C2TraversabilityGraph.build3D(far,(a,b,k)->
                k==C2TraversabilityGraph.MoveKind.WALK);
        ok(C2BPlusCorridor.clear(farGraph,node(farGraph,0,0),node(farGraph,3,2)),
                "irrelevant bounding-box obstruction does not veto chord");
        var gap=scan(Set.of("1,1"));var gg=C2TraversabilityGraph.build3D(gap,(a,b,k)->
                k==C2TraversabilityGraph.MoveKind.WALK);
        ok(!C2BPlusCorridor.clear(gg,node(gg,0,0),node(gg,3,2)),
                "unsafe swept support rejects shortcut");
        var risk=C2TraversabilityGraph.build3DWithRisk(base,(a,b,k)->
                (a.x()==1&&a.z()==1)||(b.x()==1&&b.z()==1)
                        ? new C2TraversalCost.Assessment(true,4)
                        : C2TraversalCost.Assessment.clear());
        ok(!C2BPlusCorridor.clear(risk,node(risk,0,0),node(risk,3,2)),
                "hazard on swept lane rejects shortcut");
        var height=scan(Set.of("2,1"));var g2=C2TraversabilityGraph.build3D(height,(a,b,k)->
                k==C2TraversabilityGraph.MoveKind.WALK);
        ok(!C2BPlusCorridor.clear(g2,node(g2,0,0),node(g2,3,2)),
                "unsupported near-edge cell fails closed");
        ok(!C2BPlusCorridor.clear(g,start,start),"zero length has no unverified edge");
    }
    static void checkFollow() {
        var scan=scan(Set.of());var g=C2TraversabilityGraph.build3D(scan,(a,b,k)->
                k==C2TraversabilityGraph.MoveKind.WALK);
        var result=C2FollowPlanner.selectRolling(g,scan,new C2AStarPlanner.Target(0,68,0),
                .5,69,.5,18.5,69,3.5,Set.of(),null);
        ok(result.checked()<=4,"bounded expensive rolling candidates");
        ok(result.found(),"bounded far-follow target found");
        var end=result.plan().path().get(result.plan().path().size()-1);
        ok(end.x()>=2,"rolling planner makes positive progress");
    }
    public static void main(String[] args) {
        checkSteering();checkCorridor();checkFollow();
        System.out.println("PASS NPCortex B+ H1-H4: "+tests+" assertions (pure Java)");
    }
}
