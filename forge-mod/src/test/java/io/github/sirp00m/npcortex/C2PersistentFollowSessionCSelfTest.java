package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** C-S1..S4 isolated, dependency-free persistent Follow contracts.
 * Does NOT prove live Minecraft/Forge motion ownership or packet visibility. */
public final class C2PersistentFollowSessionCSelfTest {
    private static int assertions;
    private static void ok(boolean value,String description) {
        assertions++;
        if(!value) throw new AssertionError(description);
    }
    private static C2TraversabilityGraph graph(Set<String> gaps) {
        var scan=C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,
                        y==68&&!gaps.contains(x+","+z)
                                ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.OBSTRUCTED);
            }
        });
        return C2TraversabilityGraph.build3D(scan,(a,b,k)->
                k==C2TraversabilityGraph.MoveKind.WALK);
    }
    private static C2TraversabilityGraph.Node node(C2TraversabilityGraph g,int x,int z) {
        return g.nodes().stream().filter(n->n.x()==x&&n.z()==z).findFirst().orElseThrow();
    }
    private static void routeAppendContract() {
        var g=graph(Set.of());
        var a=node(g,0,0);var b=node(g,1,0);var c=node(g,2,0);
        var d=node(g,3,0);var e=node(g,4,0);var f=node(g,6,0);
        var route=C2WaypointExecution.followSmoothed(List.of(a,b,c));
        ok(route.walkChainAllowed(g,2),"initial route certified");
        UUID owned=UUID.randomUUID();
        route.startedWalkChain(owned,2);
        ok(route.waiting(),"one active token");
        ok(route.lastCommittedIndex()==2,"committed endpoint has exact index");
        ok(route.canAppendWalk(List.of(d,e)),"flat continuation permitted");
        ok(!route.canAppendWalk(List.of(c)),"zero-length repeated endpoint rejected");
        ok(!route.canAppendWalk(List.of(f)),"overlong shortcut rejected");
        var unsafe=graph(Set.of("3,0"));
        var proof=C2WaypointExecution.followSmoothed(List.of(c,d,e));
        ok(!proof.walkChainAllowed(unsafe,2),"blocked continuation fails fresh graph proof");
        ok(proof.walkChainAllowed(g,2),"live clear graph validates same continuation");
        route.appendWalkAfterC1Extension(owned,List.of(d,e));
        ok(route.expectedTicket().equals(owned),"rolling update retains original ticket");
        ok(route.lastCommittedIndex()==4,"receipt endpoint updated only after C1 extension");
        ok(route.receiptTarget().equals(e),"new receipt target is last approved node");
        ok(route.path().size()==5,"appended nodes retained");
        try { route.path().clear();throw new AssertionError("path was mutable"); }
        catch(UnsupportedOperationException expected) { assertions++; }
        try {route.appendWalkAfterC1Extension(UUID.randomUUID(),List.of(f));
            throw new AssertionError("foreign ticket accepted");
        } catch(IllegalStateException expected) { assertions++; }
        ok(route.observe(owned,true,"MOVING",false)==C2WaypointExecution.Update.ACTIVE,
                "active receipt remains active");
        ok(route.observe(owned,false,"COMPLETE",true)==C2WaypointExecution.Update.COMPLETE,
                "terminal receipt verifies extended endpoint");
        ok(route.done(),"verified terminal closes route");
        ok(!route.canAppendWalk(List.of(f)),"terminal route cannot extend");
    }
    private static void boundedContinuousWalk() {
        var p=(C1F2ContinuousWalk.Point)new C1F2ContinuousWalk.Point(1.5,69,.5);
        var walk=new C1F2ContinuousWalk(.5,69,.5,List.of(p));
        ok(walk.remaining()==1,"initial bounded single target");
        ok(walk.extend(List.of(new C1F2ContinuousWalk.Point(2.5,69,.5))),
                "same C1 token receives preverified extension");
        ok(walk.queued()==2,"two targets under same ticket");
        ok(walk.next(.5,69,.5,0)!=null,"movement starts");
        ok(walk.next(1.5,69,.5,0)!=null,"first target consumed without terminal stop");
        ok(walk.remaining()==1 && walk.moving(),"motion continuous at boundary");
        ok(walk.next(2.5,69,.5,0)==null && "COMPLETE".equals(walk.terminal()),
                "final receipt is still required");
        ok(!walk.extend(List.of(new C1F2ContinuousWalk.Point(3.5,69,.5))),
                "terminal ticket cannot restart");
        var longWalk=new C1F2ContinuousWalk(.5,69,.5,
                List.of(new C1F2ContinuousWalk.Point(4.5,69,.5)));
        double x=4.5;
        for(int i=0;i<12;i++) {
            double nx=x+4;
            ok(longWalk.extend(List.of(new C1F2ContinuousWalk.Point(nx,69,.5))),
                    "bounded C-S1 live lease extends beyond old 32-block limit");
            // Simulate verified physical progress; no teleport or world access.
            ok(longWalk.next(x,69,.5,0)!=null,
                    "rolling head waypoint consumed, C1 still active");
            x=nx;
        }
        ok(longWalk.metrics().extensions()==12,"all extensions counted");
        longWalk.cancel();
        ok(longWalk.next(x,69,.5,0)==null,"cancel fences all future movement");
        ok(!longWalk.extend(List.of(new C1F2ContinuousWalk.Point(x+1,69,.5))),
                "cancelled C1 cannot be extended");
    }
    private static void safetyAndPolicy() {
        ok(!C1F2PreflightPolicy.requiresForwardProbe(0.0),"zero-input turns skip forward probe");
        ok(!C1F2PreflightPolicy.requiresForwardProbe(1e-9),"subthreshold input skips probe");
        ok(C1F2PreflightPolicy.requiresForwardProbe(.24),"actual forward input still sweeps");
        ok(!C1F2PreflightPolicy.requiresForwardProbe(Double.NaN),"NaN input cannot authorize movement");
        ok(!C1F2PreflightPolicy.requiresForwardProbe(Double.POSITIVE_INFINITY),"nonfinite input fails closed");
        var p=new C2FollowPolicy();
        p.planned(10,7,8);
        int initial=p.generation();
        p.rollingExtended(11,8,9);
        ok(p.generation()==initial+1,"generation increments on live verified append");
        ok(!p.ready(11),"future expensive plans throttled");
        ok(p.ready(11+C2FollowPolicy.PERSISTENT_PROBE_TICKS),"throttle expires");
        ok(!p.targetMoved(8,9),"player pose updated on verified append");
        p.failed(22);
        int failures=p.failures();
        p.rollingExtended(23,9,9);
        ok(p.failures()==failures,"planning never falsely resets physical failures");
        UUID ticket=UUID.randomUUID();
        ok(C2FollowPolicy.ownedTicket(ticket,ticket,true,
                C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND),"same C2 owner valid");
        ok(!C2FollowPolicy.ownedTicket(ticket,UUID.randomUUID(),true,
                C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND),"foreign ticket rejected");
        ok(!C2FollowPolicy.ownedTicket(ticket,ticket,true,
                C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND),"operator owner excluded");
        var g=graph(Set.of());
        ok(C2BPlusCorridor.clear(g,node(g,0,0),node(g,3,0)),
                "certified flat corridor supports same-token extension");
        var hole=graph(Set.of("2,0"));
        ok(!C2BPlusCorridor.clear(hole,node(hole,0,0),node(hole,3,0)),
                "unsafe corridor remains rejected");
    }
    public static void main(String[] args) {
        routeAppendContract();boundedContinuousWalk();safetyAndPolicy();
        System.out.println("PASS NPCortex Persistent Follow Session C: "+assertions+" assertions (pure Java)");
    }
}
