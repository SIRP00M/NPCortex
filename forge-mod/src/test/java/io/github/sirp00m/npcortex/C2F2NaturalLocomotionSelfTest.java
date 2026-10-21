package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** F2 deterministic, no Forge: diagonal safety, smooth routing, rolling local following. */
public final class C2F2NaturalLocomotionSelfTest {
    private static int assertions;
    private static void ok(boolean condition,String label) {
        assertions++;if(!condition) throw new AssertionError(label);
    }
    private static C2NavGrid.Snapshot flat(int radius) {
        return C2NavGrid.scan(0,68,0,radius,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,
                        y==68?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    private static C2TraversabilityGraph.Node node(C2TraversabilityGraph graph,int x,int z) {
        return graph.nodes().stream().filter(n->n.x()==x && n.z()==z).findFirst().orElseThrow();
    }
    public static void main(String[] args) {
        var snapshot=flat(6);
        var graph=C2TraversabilityGraph.build3D(snapshot,(a,b,k)->k==C2TraversabilityGraph.MoveKind.WALK);
        var from=node(graph,0,0);
        var diagonal=node(graph,1,1);
        ok(graph.neighbors(from).stream().anyMatch(e->e.to().equals(diagonal)
                && e.kind()==C2TraversabilityGraph.MoveKind.WALK
                && Math.abs(e.cost()-Math.sqrt(2))<1e-10),"8-way diagonal with Euclidean cost");
        var start=new C2AStarPlanner.Target(0,68,0);
        var goal=new C2AStarPlanner.Target(4,68,4);
        var b2=C2AStarPlanner.plan(graph,snapshot,start,goal,169);
        var b3=C2HierarchicalPlanner.plan(graph,snapshot,start,goal,4,9000);
        ok(b2.found() && b3.found(),"diagonal B2/B3 path");
        ok(Math.abs(b2.cost()-Math.sqrt(2)*4)<1e-7,"diagonal cost optimal");
        ok(Math.abs(b2.cost()-b3.cost())<1e-7,"B3 diagonal optimum matches B2");
        ok(b2.path().size()==5,"uses four diagonal edges, not eight cardinal");
        var old=C2TraversabilityGraph.build(snapshot,(a,b)->true);
        ok(old.edges().stream().allMatch(e->Math.abs(e.from().x()-e.to().x())
                +Math.abs(e.from().z()-e.to().z())==1),"legacy graph remains 4-way");
        var blocked=C2TraversabilityGraph.build3D(snapshot,(a,b,k)->
                !(k==C2TraversabilityGraph.MoveKind.WALK
                        && ((a.x()==0 && a.z()==0 && b.x()==1 && b.z()==0)
                         || (a.x()==1 && a.z()==0 && b.x()==0 && b.z()==0))));
        ok(blocked.neighbors(node(blocked,0,0)).stream().noneMatch(e -> e.to().x()==1
                && e.to().z()==1),"corner cut denied when orthogonal route blocked");
        var blockedDiag=C2TraversabilityGraph.build3D(snapshot,(a,b,k)->
                !(Math.abs(a.x()-b.x())==1 && Math.abs(a.z()-b.z())==1));
        ok(blockedDiag.neighbors(node(blockedDiag,0,0)).stream().noneMatch(e -> e.to().x()==1
                && e.to().z()==1),"swept diagonal collision denial");
        var smoothed=C2F2Locomotion.smooth(b2.path());
        ok(smoothed.size()==3,"four diagonal walks combine into <=3-step segments");
        ok(smoothed.get(0).equals(from) && smoothed.get(smoothed.size()-1).equals(node(graph,4,4)),
                "smooth route preserves endpoints");
        try {new C2WaypointExecution(smoothed);throw new AssertionError("baseline skip accepted");}
        catch(IllegalArgumentException expected) {assertions++;}
        var route=C2WaypointExecution.followSmoothed(smoothed);
        ok(route.edgeAllowed(graph),"all omitted diagonal edges verified on fresh graph");
        ok(!route.edgeAllowed(blocked),"dynamic obstruction invalidates coalesced corridor");
        UUID token=UUID.randomUUID(),oldTicket=UUID.randomUUID();
        route.started(token);
        ok(!route.acceptsTicket(oldTicket),"coalesced segments reject stale ticket");
        ok(route.observe(token,false,"COMPLETE",true)==C2WaypointExecution.Update.NEXT,
                "coalesced C1 verified receipt advances exactly one segment");
        ok(route.edgeAllowed(graph),"next coalesced edge still live");
        ok(C2F2Locomotion.smooth(List.of(from)).equals(List.of(from)),"no-op route stable");
        var up=new C2TraversabilityGraph.Node(1,69,0,70.0);
        ok(C2F2Locomotion.smooth(List.of(from,up)).size()==2,"3D primitive not merged");
        ok(Math.abs(C2F2Locomotion.angularDelta(179,-179)-2)<1e-4,"yaw wraps shortest path");
        float yaw=179;
        for(int i=0;i<15;i++) {
            float next=C2F2Locomotion.approach(yaw,-90);
            ok(Math.abs(C2F2Locomotion.angularDelta(yaw,next))<=18.0001,"bounded turn step");
            yaw=next;
        }
        ok(C2F2Locomotion.aligned(yaw,-90),"heading converges");
        var ring=C2FollowPlanner.select(graph,snapshot,start,.5,.5,4.5,69,.5);
        ok(ring.found(),"F2 8-way near-target follow-ring still reachable within budget");
        var rolling=C2FollowPlanner.selectRolling(graph,snapshot,start,.5,69,.5,22.5,69,.5);
        ok(rolling.found(),"far target yields local rolling waypoint");
        var endpoint=rolling.plan().path().get(rolling.plan().path().size()-1);
        ok(endpoint.x()>0 && endpoint.x()<=6,"rolling uses local graph not unseen far goal");
        ok(rolling.checked()<=C2FollowPolicy.MAX_CANDIDATE_PLANS,"bounded candidate cost");
        for(int i=0;i<10;i++) {
            var s=new C2FollowPolicy();
            var result=C2FollowPlanner.selectRolling(graph,snapshot,start,.5,69,.5,25+i*7,69,.5);
            ok(result.checked()<=C2FollowPolicy.MAX_CANDIDATE_PLANS,"10 actors budget isolated "+i);
            if(result.found()) {s.planned(i,25+i*7,.5);ok(s.generation()==1,"actor own version "+i);}
        }
        var fence=C2TraversabilityGraph.build3D(snapshot,(a,b,k)->false);
        ok(!C2FollowPlanner.selectRolling(fence,snapshot,start,.5,69,.5,90,69,.5).found(),
                "unreachable rolling route stops, no teleport");
        for(var wrong:List.of(Double.NaN,Double.POSITIVE_INFINITY)) {
            try {C2FollowPlanner.selectRolling(graph,snapshot,start,.5,69,.5,wrong,69,.5);
                throw new AssertionError("Invalid target accepted");}
            catch(IllegalArgumentException expected) {assertions++;}
        }
        System.out.println("PASS F2 natural locomotion/diagonal/rolling: "+assertions+" assertions (pure JVM)");
    }
}
