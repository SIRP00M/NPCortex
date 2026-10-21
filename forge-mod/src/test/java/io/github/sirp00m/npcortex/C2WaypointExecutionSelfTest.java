package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pure JVM protocol regression; NOT Forge movement/gameplay/MSPT acceptance. */
public final class C2WaypointExecutionSelfTest {
    private static int count;
    private static void check(boolean p,String detail) {
        count++; if (!p) throw new AssertionError(detail);
    }
    private static void reject(Runnable r,String detail) {
        count++;
        try { r.run(); } catch (IllegalArgumentException | NullPointerException | IllegalStateException | UnsupportedOperationException expected) { return; }
        throw new AssertionError("Should reject: "+detail);
    }
    private static C2TraversabilityGraph.Node node(int x,int z) {
        return new C2TraversabilityGraph.Node(x,0,z,1);
    }
    private static C2NavGrid.Snapshot scan(java.util.function.BiPredicate<Integer,Integer> walkable) {
        return C2NavGrid.scan(0,0,0,4,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,y==0&&walkable.test(x,z)
                        ?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    public static void main(String[] args) {
        var g=C2TraversabilityGraph.build(scan((x,z)->true),(a,b)->true);
        var path=List.of(node(0,0),node(1,0),node(2,0));
        var r=new C2WaypointExecution(path);
        check(r.totalSegments()==2,"two segments");
        check(r.currentIndex()==1 && r.currentTarget().equals(node(1,0)),"first target");
        check(r.edgeAllowed(g),"real B1 WALK edge");
        check(!r.waiting(),"idle initially");
        UUID t=UUID.randomUUID();r.started(t);
        check(r.waiting() && r.expectedTicket().equals(t),"correlated ticket");
        check(!r.edgeAllowed(g),"cannot start double segment");
        reject(()->r.started(UUID.randomUUID()),"cannot start duplicate");
        check(r.observe(t,true,"ACTIVE",false)==C2WaypointExecution.Update.ACTIVE,"active motion is not completion");
        check(r.observe(t,true,"COMPLETE",true)==C2WaypointExecution.Update.ACTIVE,"active always waiting");
        check(r.observe(t,false,"COMPLETE",true)==C2WaypointExecution.Update.NEXT,"first segment verified");
        check(r.currentIndex()==2 && r.edgeAllowed(g),"only advance to adjacent next node");
        t=UUID.randomUUID();r.started(t);
        UUID wrong=UUID.randomUUID();
        check(r.observe(wrong,false,"COMPLETE",true)==C2WaypointExecution.Update.FAILED,"reject stale token");
        check(r.waiting(),"wrong receipt cannot consume rightful token");
        check(r.observe(t,false,"COMPLETE",true)==C2WaypointExecution.Update.COMPLETE,"last segment verified");
        check(r.done() && !r.waiting(),"final transaction complete");
        check(!r.edgeAllowed(g),"completed route cannot restart");
        reject(()->r.started(UUID.randomUUID()),"completed route rejects replay");
        var blocked=C2TraversabilityGraph.build(scan((x,z)->x!=1),(a,b)->true);
        var b=new C2WaypointExecution(path);
        check(!b.edgeAllowed(blocked),"blocked edge fails closed");
        var isolated=C2TraversabilityGraph.build(scan((x,z)->true),(a,c)->false);
        check(!b.edgeAllowed(isolated),"missing collision clearance fails closed");
        var cancel=new C2WaypointExecution(List.of(node(0,0),node(0,1)));
        cancel.started(UUID.randomUUID());
        check(cancel.observe(cancel.expectedTicket(),false,"CANCELLED",true)==C2WaypointExecution.Update.FAILED,"cancel never accepted");
        var timeout=new C2WaypointExecution(List.of(node(0,0),node(0,1)));
        timeout.started(UUID.randomUUID());
        check(timeout.observe(timeout.expectedTicket(),false,"TIMED_OUT",true)==C2WaypointExecution.Update.FAILED,"timeout never accepted");
        var far=new C2WaypointExecution(List.of(node(0,0),node(0,1)));
        far.started(UUID.randomUUID());
        check(far.observe(far.expectedTicket(),false,"COMPLETE",false)==C2WaypointExecution.Update.FAILED,"C1 receipt alone not enough");
        var single=new C2WaypointExecution(List.of(node(0,0)));
        check(single.totalSegments()==1 && single.edgeAllowed(g),"precision centering at same node");
        single.started(UUID.randomUUID());
        check(single.observe(single.expectedTicket(),false,"COMPLETE",true)==C2WaypointExecution.Update.COMPLETE,"single node done");
        reject(()->new C2WaypointExecution(List.of()),"empty path");
        reject(()->new C2WaypointExecution(List.of(node(0,0),node(2,0))),"skip not safe");
        reject(()->new C2WaypointExecution(List.of(node(0,0),node(1,1))),"diagonal not permitted");
        reject(()->new C2WaypointExecution(List.of(node(0,0),
                new C2TraversabilityGraph.Node(1,0,0,2.5))),"unsupported 1.5-block elevation rejected");
        List<C2TraversabilityGraph.Node> overLimit=new ArrayList<>();
        for(int i=0;i<65;i++) overLimit.add(node(i,0));
        reject(()->new C2WaypointExecution(overLimit),"bounded total segments");
        check(r.path().equals(path),"immutable validated nodes");
        reject(()->r.path().clear(),"path immutable");
        // Exercise a small array of sequential segments and non-reused tokens.
        for(int n=1;n<=20;n++) {
            var pp=new ArrayList<C2TraversabilityGraph.Node>();
            for(int i=0;i<=n;i++) pp.add(node(i,0));
            var exec=new C2WaypointExecution(pp);
            for(int i=1;i<=n;i++) {
                UUID receipt=UUID.randomUUID();exec.started(receipt);
                check(exec.observe(receipt,false,"COMPLETE",true)==
                        (i==n?C2WaypointExecution.Update.COMPLETE:C2WaypointExecution.Update.NEXT),"segment "+i+" of "+n);
            }
            check(exec.done(),"n segments done: "+n);
        }
        System.out.println("PASS C2 P1-C Waypoint Protocol: "+count+" assertions (pure Java, not Forge gameplay)");
    }
}
