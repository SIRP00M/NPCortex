package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** F2 Fix #1 contract tests: one-session steering, guarded graph handoff.
 * No claim about a real Minecraft client, combat packets or Forge MSPT. */
public final class C2F2ContinuousFollowFix1SelfTest {
    private static int assertions;
    private static void ok(boolean good,String reason) { assertions++;if(!good)throw new AssertionError(reason); }
    private static void rejects(Runnable request,String label) {
        try {request.run();throw new AssertionError("accepted: "+label);}
        catch(IllegalArgumentException expected) {assertions++;}
    }
    private static C2NavGrid.Snapshot flat() {
        return C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z){return true;}
            public C2NavGrid.Cell sample(int x,int y,int z){return new C2NavGrid.Cell(x,y,z,y+1,
                    y==68?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);}
        });
    }
    private static C2TraversabilityGraph.Node n(C2TraversabilityGraph g,int x,int z) {
        return g.nodes().stream().filter(n->n.x()==x&&n.z()==z).findFirst().orElseThrow();
    }
    public static void main(String[] args) {
        var straight=new C1F2ContinuousWalk(0.5,69,0.5,List.of(
                new C1F2ContinuousWalk.Point(1.5,69,.5),
                new C1F2ContinuousWalk.Point(2.5,69,.5),
                new C1F2ContinuousWalk.Point(3.5,69,.5)));
        float yaw=-70;
        var first=straight.next(.5,69,.5,yaw);
        ok(first!=null && first.forward()>.0 && first.forward()<=1,"move while turning");
        ok(Math.abs(C2F2Locomotion.angularDelta(yaw,first.yaw()))<=18.0001,"turn rate clamped");
        var unsafeTurn=new C1F2ContinuousWalk(.5,69,.5,List.of(new C1F2ContinuousWalk.Point(2.5,69,.5)));
        ok(unsafeTurn.next(.5,69,.5,90).forward()==0,"sharp reverse must not walk into unchecked cell");
        ok(straight.consumed()==0 && straight.moving(),"first leg active");
        var midway=straight.next(1.46,69,.5,first.yaw());
        ok(straight.consumed()==1 && midway!=null,"intermediate target consumed without terminal");
        ok(straight.moving(),"same session stays active after first waypoint");
        straight.next(2.49,69,.5,midway.yaw());
        ok(straight.consumed()==2,"second waypoint passed on same ticket");
        ok(straight.next(3.5,69,.5,-90)==null && "COMPLETE".equals(straight.terminal()),
                "last verified goal completes only at end");
        ok(!straight.moving() && straight.consumed()==3,"no stale input after completion");
        ok(straight.next(3.5,69,.5,-90)==null,"completion is idempotent");
        var cancelled=new C1F2ContinuousWalk(.5,69,.5,List.of(new C1F2ContinuousWalk.Point(2.5,69,.5)));
        cancelled.cancel();
        ok(!cancelled.moving() && cancelled.next(.5,69,.5,0)==null,"cancel cannot resume");
        ok("CANCELLED".equals(cancelled.terminal()),"cancel terminal recorded");
        var blocked=new C1F2ContinuousWalk(.5,69,.5,List.of(new C1F2ContinuousWalk.Point(2.5,69,.5)));
        for(int i=0;i<30 && blocked.moving();i++) blocked.next(.5,69,.5,0);
        ok("BLOCKED".equals(blocked.terminal()),"no-progress bounded fail closed");
        var failedHeight=new C1F2ContinuousWalk(.5,69,.5,List.of(new C1F2ContinuousWalk.Point(1.5,69,.5)));
        failedHeight.next(.5,68.5,.5,0);
        ok("BLOCKED".equals(failedHeight.terminal()),"height deviation denied");
        var wrap=new C1F2ContinuousWalk(.5,69,.5,List.of(new C1F2ContinuousWalk.Point(.5,69,-1.5)));
        var angle=wrap.next(.5,69,.5,179);
        ok(Math.abs(C2F2Locomotion.angularDelta(179,angle.yaw()))<=18.001,"wrapped yaw bounded");
        rejects(()->new C1F2ContinuousWalk(0,69,0,List.of()),"empty route");
        rejects(()->new C1F2ContinuousWalk(0,69,0,List.of(new C1F2ContinuousWalk.Point(0,69,0))),"zero segment");
        rejects(()->new C1F2ContinuousWalk(0,69,0,List.of(new C1F2ContinuousWalk.Point(1,70,0))),"vertical");
        rejects(()->new C1F2ContinuousWalk(0,69,0,List.of(new C1F2ContinuousWalk.Point(9,69,0))),"unbounded leg");
        rejects(()->new C1F2ContinuousWalk(0,69,0,List.of(new C1F2ContinuousWalk.Point(Double.NaN,69,0))),"nan");
        rejects(()->new C1F2ContinuousWalk(0,69,0,List.of(
                new C1F2ContinuousWalk.Point(3,69,0),new C1F2ContinuousWalk.Point(6,69,0),
                new C1F2ContinuousWalk.Point(9,69,0))),"unbounded total");
        var graph=C2TraversabilityGraph.build3D(flat(),(a,b,k)->k==C2TraversabilityGraph.MoveKind.WALK);
        var nodes=List.of(n(graph,0,0),n(graph,1,0),n(graph,2,0),n(graph,3,0));
        var route=C2WaypointExecution.followSmoothed(nodes);
        ok(route.walkChainAllowed(graph,3),"fresh three-link walk chain validated");
        var deny=C2TraversabilityGraph.build3D(flat(),(a,b,k)-> !(a.x()==1 && a.z()==0 && b.x()==2 && b.z()==0));
        ok(!route.walkChainAllowed(deny,3),"middle edge mutation denied");
        UUID t=UUID.randomUUID();
        route.startedWalkChain(t,3);
        ok(route.waiting() && route.receiptTarget().equals(n(graph,3,0)),"ticket remains owner of final node");
        ok(route.observe(t,true,"ACTIVE",false)==C2WaypointExecution.Update.ACTIVE,"no partial receipt");
        ok(route.observe(UUID.randomUUID(),false,"COMPLETE",true)==C2WaypointExecution.Update.FAILED,
                "foreign ticket fails closed");
        ok(route.observe(t,false,"COMPLETE",true)==C2WaypointExecution.Update.COMPLETE,
                "one verified receipt consumes exactly three segments");
        ok(route.done(),"chain route marked complete");
        var legacy=new C2WaypointExecution(nodes);
        rejects(()->legacy.startedWalkChain(UUID.randomUUID(),2),"legacy route cannot start chain");
        var route2=C2WaypointExecution.followSmoothed(nodes);
        route2.startedWalkChain(UUID.randomUUID(),2);
        var t2=route2.expectedTicket();
        ok(route2.observe(t2,false,"BLOCKED",false)==C2WaypointExecution.Update.FAILED,
                "failure cannot skip intermediate points");
        var graph2=C2TraversabilityGraph.build3D(flat(),(a,b,k)->k==C2TraversabilityGraph.MoveKind.WALK);
        var up=new C2TraversabilityGraph.Node(1,69,0,70);
        var typed=C2WaypointExecution.followSmoothed(List.of(n(graph2,0,0),up));
        ok(!typed.walkChainAllowed(graph2,1),"typed vertical never enters WALK chain");
        for(int i=0;i<10;i++) {
            var each=new C1F2ContinuousWalk(.5,69,.5,List.of(new C1F2ContinuousWalk.Point(1.5,69,.5)));
            ok(each.moving() && each.next(.5,69,.5,0)!=null,"independent actor session "+i);
        }
        System.out.println("PASS F2 Fix #1 continuous follow contracts: "+assertions+" assertions (pure JVM)");
    }
}
