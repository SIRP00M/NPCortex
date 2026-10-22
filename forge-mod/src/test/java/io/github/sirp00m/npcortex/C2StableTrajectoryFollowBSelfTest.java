package io.github.sirp00m.npcortex;

import java.util.List;

/** Stable Trajectory B regression; PURE JAVA, NOT Minecraft acceptance. */
public final class C2StableTrajectoryFollowBSelfTest {
    private static int count;
    private static void ok(boolean good,String why) {
        count++; if(!good) throw new AssertionError(why);
    }
    private static C1F2ContinuousWalk.Point point(double x,double z) {
        return new C1F2ContinuousWalk.Point(x,69,z);
    }
    static void lateralDeadband() {
        var center=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,2,.5);
        ok(center.x()>2&&Math.abs(center.z()-.5)<1e-9,"straight forward aim");
        var left=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,2,.57);
        var right=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,2,.43);
        ok(Math.abs(left.z()-.57)<1e-9 && Math.abs(right.z()-.43)<1e-9,
                "small lateral error must not be corrected every tick");
        ok(Math.abs(left.x()-right.x())<1e-9,"left/right mirror progress");
        var farther=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,2,.8);
        ok(farther.z()<.8 && farther.z()>.5,
                "larger off-track error corrected gently without oversteering");
        var negative=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,2,.2);
        ok(negative.z()>.2 && negative.z()<.5,"mirrored correction bounded");
        var unsafe=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,2,1.1);
        ok(unsafe.x()==4.5&&unsafe.z()==.5,"outside certified lateral region falls back to waypoint");
        var towardEnd=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,4.3,.55);
        ok(towardEnd.x()<=4.5+1e-9,"lookahead never extends past certified end");
        var overshoot=C1F2AdaptiveSteering.onSegment(.5,.5,4.5,.5,4.85,.6);
        ok(overshoot.x()==4.5&&overshoot.z()==.5,
                "actual overshoot returns to certified endpoint; no fake receipt");
    }
    static void arrivalAndOvershoot() {
        ok(C1F2TrajectoryArrival.cap(.8,1.8,.15,true,false)==.8,
                "far along straight does not brake early");
        ok(C1F2TrajectoryArrival.cap(.8,.36,.18,true,false)<.20,
                "physical arrival braking reduces final forward input");
        ok(C1F2TrajectoryArrival.cap(.8,.3,.06,true,false)>.0,
                "near final receipt still makes bounded progress");
        ok(C1F2TrajectoryArrival.cap(0,.31,.2,true,false)==0,
                "unsafe-turn zero forward remains zero");
        ok(C1F2TrajectoryArrival.cap(.85,.4,.1,false,true)<.85,
                "sharp verified corner approach slows");
        ok(C1F2TrajectoryArrival.cap(.8,.4,.1,false,false)==.8,
                "nonterminal straight leg maintains cruise speed");
        var finalTarget=new C1F2ContinuousWalk(.5,69,.5,List.of(point(4.5,.5)));
        finalTarget.next(.5,69,.5,-90);
        var near=finalTarget.next(4.81,69,.5,-90);
        ok(near!=null && finalTarget.moving(),
                "final endpoint overshoot MUST NOT fabricate a completed receipt");
        // An actual location inside C1's receipt tolerance is still required.
        ok(finalTarget.next(4.74,69,.5,90)==null
                        && "COMPLETE".equals(finalTarget.terminal()),
                "final goal completes only with actual near-position");
        var intermediate=new C1F2ContinuousWalk(.5,69,.5,
                List.of(point(1.5,.5),point(3.5,.5)));
        intermediate.next(.5,69,.5,-90);
        var past=intermediate.next(1.72,69,.5,-90);
        ok(past!=null && intermediate.consumed()==1,
                "intermediate actual point within tolerance can be consumed, no ticket reset");
        var notCrossed=new C1F2ContinuousWalk(.5,69,.5,
                List.of(point(1.5,.5),point(3.5,.5)));
        notCrossed.next(.5,69,.5,-90);
        notCrossed.next(2.0,69,.5,-90);
        ok(notCrossed.consumed()==0,"teleport-like sample may not skip a waypoint");
        notCrossed.cancel();
        ok(notCrossed.next(2,69,.5,-90)==null,"cancelled state never resumes");
    }
    static void continuity() {
        var memory=new C2FollowAlternatives();
        // Build a minimal real route using a flat local graph so that only
        // certified nodes can ever be preferred by this layer.
        var snap=C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,
                        y==68?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
        var graph=C2TraversabilityGraph.build3D(snap,(a,b,kind)->
                kind==C2TraversabilityGraph.MoveKind.WALK);
        var preferred=graph.nodes().stream().filter(n->n.x()==3&&n.z()==0)
                .findFirst().orElseThrow();
        var start=graph.nodes().stream().filter(n->n.x()==0&&n.z()==0)
                .findFirst().orElseThrow();
        memory.planned(C2WaypointExecution.followSmoothed(List.of(start,preferred)));
        memory.targetRelocated(.5,.5,9.5,.5);
        ok(preferred.equals(memory.preferred()),
                "moving player on same side keeps certified previous target as SCORE hint");
        memory.targetRelocated(4.5,.5,9.5,.5);
        ok(memory.preferred()==null,"passed target is not preferred after actor progresses");
        memory.planned(C2WaypointExecution.followSmoothed(List.of(start,preferred)));
        memory.targetRelocated(.5,.5,-9.5,.5);
        ok(memory.preferred()==null,"reverse player target clears stale heading preference");
        memory.planned(C2WaypointExecution.followSmoothed(List.of(start,preferred)));
        memory.targetRelocated();
        ok(memory.preferred()==null,"explicit full relocation reset still clears old preference");
    }
    public static void main(String[] args) {
        lateralDeadband(); arrivalAndOvershoot(); continuity();
        System.out.println("PASS Stable Trajectory Follow B: "+count+" assertions (pure Java)");
    }
}
