package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Follow B regression: pure Java only, no Forge simulation assertions. */
public final class C2F2AdaptiveFollowBSelfTest {
    private static int assertions;
    private static void ok(boolean good, String message) {
        assertions++;if(!good)throw new AssertionError(message);
    }
    private static C2NavGrid.Snapshot flat() {
        return C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z){return true;}
            public C2NavGrid.Cell sample(int x,int y,int z){
                return new C2NavGrid.Cell(x,y,z,y+1,
                    y==68?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    private static C2TraversabilityGraph.Node at(C2TraversabilityGraph g,int x,int z) {
        return g.nodes().stream().filter(n->n.x()==x&&n.z()==z).findFirst().orElseThrow();
    }
    private static C1F2ContinuousWalk.Point point(double x,double z) {
        return new C1F2ContinuousWalk.Point(x,69,z);
    }
    public static void main(String[] args) {
        var snap=flat();
        var start=new C2AStarPlanner.Target(0,68,0);
        // The tempting nearest 4 ring positions are unreachable across a
        // graph fence; a lower-ranked ring destination IS physically connected.
        var partitioned=C2TraversabilityGraph.build(snap,
                (a,b)->a.x()<=1 && b.x()<=1);
        var ranked=new ArrayList<>(partitioned.nodes().stream().filter(n-> {
            double d=Math.hypot(n.x()+.5-4.5,n.z()+.5-.5);
            return d>=1.8 && d<=3.35;
        }).toList());
        ranked.sort(Comparator.comparingDouble((C2TraversabilityGraph.Node n)->
                    Math.abs(Math.hypot(n.x()+.5-4.5,n.z()+.5-.5)-2.6))
                .thenComparingDouble(n->Math.hypot(n.x(),n.z()))
                .thenComparingInt(C2TraversabilityGraph.Node::x)
                .thenComparingInt(C2TraversabilityGraph.Node::z));
        ok(ranked.size()>4,"fixture has alternate targets");
        ok(ranked.subList(0,4).stream().noneMatch(n->n.x()<=1),
                "top 4 ring ranking disconnected from actor");
        var choice=C2FollowPlanner.select(partitioned,snap,start,.5,.5,4.5,69,.5);
        ok(choice.found(),"reachable alternate found after avoiding false negative");
        ok(choice.checked()<=4,"strict search quota respected");
        ok(choice.plan().path().get(choice.plan().path().size()-1).x()<=1,
                "selected goal really connected");
        var alreadyThere=C2FollowPlanner.select(partitioned,snap,start,.5,.5,3.5,69,.5);
        ok(alreadyThere.found() && alreadyThere.plan().path().size()==1,
                "if already in safe ring, retain zero-step HOLD instead of wandering");
        var banned=choice.plan().path().get(choice.plan().path().size()-1);
        var alternate=C2FollowPlanner.select(partitioned,snap,start,.5,.5,4.5,69,.5,
                Set.of(banned),null);
        ok(!alternate.found() ||
                !alternate.plan().path().get(alternate.plan().path().size()-1).equals(banned),
                "avoid previously failed terminal");
        var rolling=C2FollowPlanner.selectRolling(partitioned,snap,start,.5,69,.5,
                25.5,69,.5,Set.of());
        ok(rolling.checked()<=4,"rolling reachability respects quota");
        var preferred=C2FollowPlanner.select(partitioned,snap,start,.5,.5,4.5,69,.5,
                Set.of(),banned);
        ok(preferred.found(),"valid preferred goal still selects a path");
        var hysteresis=new C2FollowAlternatives();
        var route=C2WaypointExecution.followSmoothed(choice.plan().path());
        hysteresis.planned(route);
        ok(hysteresis.preferred().equals(banned),"stable prior goal remembered");
        hysteresis.failed(route);
        ok(hysteresis.avoidedCount()>=1 && hysteresis.avoided().contains(banned),
                "failed target excluded from next plan");
        hysteresis.verifiedProgress();
        ok(hysteresis.avoidedCount()==0,"verified real progress clears failed edges");
        hysteresis.failed(route);
        hysteresis.targetRelocated();
        ok(hysteresis.avoidedCount()==0 && hysteresis.preferred()==null,
                "moving player clears stale local rejections");
        var policy=new C2FollowPolicy();
        policy.planned(100,4.5,.5);
        ok(policy.failed(102),"one failed attempt eligible");
        ok(policy.failed(123),"second failed attempt eligible");
        ok(!policy.failed(144),"third exhausts failure budget");
        ok(!policy.recoverOnce(144+119),"stationary wait before one dynamic reprobe");
        ok(policy.recoverOnce(144+120),"single fresh snapshot reprobe after timeout");
        ok(policy.failed(265) && policy.failed(286) && !policy.failed(307),
                "additional failed tries remain bounded");
        ok(!policy.recoverOnce(500),"no unlimited repeated stationary retries");
        policy.targetRelocated();
        ok(policy.failures()==0,"target movement unblocks recovery state");
        // Sharp corner target remains the graph-verified point; it is NOT the
        // interpolated chord between two waypoint legs.
        var turn=new C1F2ContinuousWalk(.5,69,.5,
                List.of(point(1.5,.5),point(1.5,2.5)));
        var near=turn.next(1.05,69,.5,-90);
        ok(near!=null && near.targetX()==1.5 && near.targetZ()==.5,
                "no unverified near-turn steering chord");
        var fast=new C1VanillaMoveControlAdapter();
        var slow=new C1VanillaMoveControlAdapter();
        for(int i=0;i<5;i++) {fast.tick(-90,-90,2,false,false);slow.tick(-90,-90,2,false,true);}
        var full=fast.tick(-90,-90,.7,false,false);
        var corner=slow.tick(-90,-90,.7,false,true);
        ok(corner.forward()<full.forward(),"sharp turn slows near waypoint");
        var pass=new C1F2ContinuousWalk(.5,69,.5,
                List.of(point(1.5,.5),point(2.5,.5)));
        pass.next(1.17,69,.5,-90);
        pass.next(1.82,69,.5,-90);
        ok(pass.consumed()==1,"sampled crossing within bounded sweep consumes waypoint");
        var last=new C1F2ContinuousWalk(.5,69,.5,List.of(point(1.5,.5)));
        last.next(1.17,69,.5,-90);
        last.next(1.82,69,.5,-90);
        ok(last.moving() && last.consumed()==0,
                "cannot complete final receipt merely by sweeping past endpoint");
        turn.cancel();
        ok(turn.next(1.5,69,.5,-90)==null,"cancel cannot resurrect steering");
        System.out.println("PASS C2-F2 Adaptive Follow B: "+assertions+" assertions (pure JVM)");
    }
}
