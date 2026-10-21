package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** Fix #2 deterministic validation of telemetry, safe turn steering and
 * token-fenced bounded handoff contract. Does NOT simulate Vanilla physics. */
public final class C2F2MotionSmoothingFix2SelfTest {
    private static int checks;
    private static void ok(boolean value,String description) {
        checks++;
        if(!value) throw new AssertionError(description);
    }
    private static C1F2ContinuousWalk.Point p(double x,double z) {
        return new C1F2ContinuousWalk.Point(x,69,z);
    }
    private static C2NavGrid.Snapshot grid() {
        return C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,
                        y==68?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    private static C2TraversabilityGraph.Node n(C2TraversabilityGraph g,int x,int z) {
        return g.nodes().stream().filter(v->v.x()==x && v.z()==z).findFirst().orElseThrow();
    }
    public static void main(String[] args) {
        var telem=new C1F2MotionTelemetry();
        ok(telem.summary().samples()==0,"new telemetry empty");
        telem.sample(.05,1,18,0);
        telem.sample(.0,0,2,0);
        telem.sample(.08,1,0,1);
        telem.extension();
        var metrics=telem.summary();
        ok(metrics.samples()==3,"sample count");
        ok(metrics.nearStationary()==1,"stationary count");
        ok(metrics.commandedZero()==1,"zero input count");
        ok(metrics.turns()==2,"turn count");
        ok(metrics.extensions()==1,"extension count");
        ok(metrics.distinctWaypoints()==2,"waypoint count");
        ok(Math.abs(metrics.travelled()-.13)<1e-8,"travel distance");
        ok(metrics.toString().contains("extensions=1"),"telemetry formatted");
        var moving=new C1F2ContinuousWalk(.5,69,.5,List.of(p(1.5,.5),p(1.5,2.5)));
        // B follow recovery supersedes Fix #2 corner blending: at 0.5 from a
        // sharp corner, do NOT aim through the unverified diagonal chord.
        var intent=moving.next(1.05,69,.5,-90);
        ok(intent!=null,"bounded steering produced intent");
        ok(Math.abs(intent.targetZ()-.5)<1e-9,
                "B regression: no unverified chord through corner");
        ok(Math.abs(C2F2Locomotion.angularDelta(-90,intent.yaw()))<=18.01,"yaw rate capped");
        ok(moving.queued()==2 && moving.remaining()==2,"initial committed targets");
        var far=new C1F2ContinuousWalk(.5,69,.5,List.of(p(1.5,.5),p(1.5,2.5),p(1.5,3.5)));
        ok(!far.extend(List.of(p(2.5,3.5))),"no prefetch when more than two targets remain");
        moving.next(1.5,69,.5,intent.yaw());
        ok(moving.consumed()==1 && moving.remaining()==1,"one waypoint consumed");
        ok(moving.extend(List.of(p(2.5,2.5),p(3.5,2.5))),"same owner extension accepted");
        ok(moving.queued()==4 && moving.moving(),"same ticket remains active");
        ok(moving.metrics().extensions()==1,"extension telemetry");
        moving.cancel();
        ok(!moving.extend(List.of(p(4.5,2.5))),"no extension after cancel");
        ok(moving.next(2.5,69,2.5,0)==null,"no stale command post cancel");
        var graph=C2TraversabilityGraph.build3D(grid(),(a,b,k)->k==C2TraversabilityGraph.MoveKind.WALK);
        var route=C2WaypointExecution.followSmoothed(List.of(n(graph,0,0),n(graph,1,0),
                n(graph,2,0),n(graph,3,0),n(graph,4,0),n(graph,5,0)));
        var token=UUID.randomUUID();
        ok(route.walkChainAllowed(graph,2),"initial walk validated");
        route.startedWalkChain(token,2);
        ok(route.lastCommittedIndex()==2 && route.receiptTarget().equals(n(graph,2,0)),"initial receipt");
        ok(route.walkExtensionAllowed(graph,2),"fresh extension graph proof");
        var deny=C2TraversabilityGraph.build3D(grid(),(a,b,k)->
                !(a.x()==3 && b.x()==4 && a.z()==0 && b.z()==0));
        ok(!route.walkExtensionAllowed(deny,2),"new unsafe edge denies extension");
        try {route.extendedWalkChain(UUID.randomUUID(),2);throw new AssertionError("wrong token");}
        catch(IllegalArgumentException expected) {checks++;}
        route.extendedWalkChain(token,2);
        ok(route.lastCommittedIndex()==4,"extension updates receipt endpoint");
        ok(route.acceptsTicket(token),"same token owner");
        ok(route.observe(token,true,"ACTIVE",false)==C2WaypointExecution.Update.ACTIVE,"no early receipt");
        ok(route.observe(token,false,"COMPLETE",true)==C2WaypointExecution.Update.NEXT,"receipt advances four segments");
        ok(route.currentTarget().equals(n(graph,5,0)),"last waypoint remains");
        ok(!route.walkExtensionAllowed(graph,1),"no extension without active ticket");
        var legacy=new C2WaypointExecution(List.of(n(graph,0,0),n(graph,1,0),n(graph,2,0)));
        legacy.started(UUID.randomUUID());
        ok(!legacy.walkExtensionAllowed(graph,1),"legacy navigate rejects extension");
        for(int i=0;i<10;i++) {
            var separate=new C1F2ContinuousWalk(.5,69,.5,List.of(p(1.5,.5)));
            ok(separate.metrics().samples()==0 && separate.moving(),"NPC isolation "+i);
        }
        System.out.println("PASS C2-F2 Fix #2 telemetry/safe-steering/extension: "+checks+" assertions (pure JVM)");
    }
}
