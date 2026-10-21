package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/** Pure Java Sprint 3 graph -> B2/B3 -> typed waypoint integration proof.
 * Synthetic geometry approvals are NOT evidence of Forge collision/physics.
 */
public final class C2S3NavigationSelfTest {
    private static int assertions;
    private static void check(boolean ok,String message) {
        assertions++;if(!ok) throw new AssertionError(message);
    }
    private static void reject(Runnable action,String message) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) {return;}
        throw new AssertionError(message);
    }
    private static C2NavGrid.Snapshot world(Map<Integer,Double> heights) {
        // X=0 through X=3, z=0, each world column has exactly one walkable
        // candidate at an exact support/footY; other sampled Y are blocked.
        return C2NavGrid.scan(1,0,0,4,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                Double h= z==0 ? heights.get(x) : null;
                boolean valid=h!=null && y==C2S3SupportY.at(h);
                return new C2NavGrid.Cell(x,y,z,valid?h:y+1,
                        valid ? C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    private static C2AStarPlanner.Target target(int x,double y) {
        return new C2AStarPlanner.Target(x,C2S3SupportY.at(y),0);
    }
    private static C2TraversabilityGraph.Node n(int x,double footY) {
        return new C2TraversabilityGraph.Node(x,C2S3SupportY.at(footY),0,footY);
    }
    private static boolean has(C2TraversabilityGraph graph, int a, double ay, int b,double by,
                               C2TraversabilityGraph.MoveKind kind) {
        return graph.neighbors(n(a,ay)).stream().anyMatch(e->e.to().equals(n(b,by)) && e.kind()==kind);
    }
    public static void main(String[] args) {
        check(C2S3SupportY.at(69)==68,"full-block foot support");
        check(C2S3SupportY.at(69.5)==69,"half-height bottom slab foot support");
        check(C2S3SupportY.at(-0.5)==-1,"negative height slab support");
        check(C2S3SupportY.at(-1)==-2,"negative full-block support");
        reject(()->C2S3SupportY.at(Double.NaN),"nonfinite floor denied");
        reject(()->C2S3SupportY.at(Double.POSITIVE_INFINITY),"infinite floor denied");
        // Only explicitly validated stair candidate may be plannable; an
        // ordinary partial slab/fence is never promoted to a graph vertex.
        check(C2NavGrid.plannable(C2NavGrid.Condition.STAIR_TRANSITION),"verified stair candidate usable");
        check(!C2NavGrid.plannable(C2NavGrid.Condition.PARTIAL_SUPPORT),"generic partial unsafe");
        var stairCell=new C2NavGrid.Cell(1,0,0,1,C2NavGrid.Condition.STAIR_TRANSITION);
        var stairEvidence=new C2TerrainObservation(stairCell,C2TerrainGeometry.Support.PARTIAL,
                C2TerrainObservation.Clearance.CLEAR,C2TerrainObservation.Risk.NONE,
                C2TerrainObservation.Reason.PARTIAL_FOOTPRINT,0.5,2);
        check(stairEvidence.supportedFraction()==0.5,"stair support stays explicitly partial");
        reject(()->new C2TerrainObservation(stairCell,C2TerrainGeometry.Support.PARTIAL,
                C2TerrainObservation.Clearance.NOT_CHECKED,C2TerrainObservation.Risk.NONE,
                C2TerrainObservation.Reason.PARTIAL_FOOTPRINT,0.5,2),
                "unchecked stair clearance rejected");
        reject(()->new C2TerrainObservation(stairCell,C2TerrainGeometry.Support.FULL,
                C2TerrainObservation.Clearance.CLEAR,C2TerrainObservation.Risk.NONE,
                C2TerrainObservation.Reason.PARTIAL_FOOTPRINT,1.0,2),"stair cannot fake FULL");
        var height=Map.of(0,1.0,1,1.5,2,2.5,3,1.5);
        var scan=world(height);
        var graph=C2TraversabilityGraph.build3D(scan,(a,b,k)->true);
        check(has(graph,0,1,1,1.5,C2TraversabilityGraph.MoveKind.STEP_UP),"step up directed");
        check(has(graph,1,1.5,0,1,C2TraversabilityGraph.MoveKind.STEP_DOWN),"step down directed");
        check(has(graph,1,1.5,2,2.5,C2TraversabilityGraph.MoveKind.JUMP_UP),"jump up directed");
        check(has(graph,2,2.5,3,1.5,C2TraversabilityGraph.MoveKind.SAFE_DESCEND),"safe descent directed");
        check(has(graph,2,2.5,1,1.5,C2TraversabilityGraph.MoveKind.SAFE_DESCEND),"reverse jump is safe descend");
        for (var e:graph.edges()) {
            check(e.cost()>=1,"3D edge has bounded nonzero cost");
            check(Math.abs(e.from().x()-e.to().x())+Math.abs(e.from().z()-e.to().z())==1,
                    "3D edge never diagonal");
        }
        var s=target(0,1.0);var goal=target(3,1.5);
        var b2=C2AStarPlanner.plan(graph,scan,s,goal,C2AStarPlanner.MAX_EXPANSION_BUDGET);
        var b3=C2HierarchicalPlanner.plan(graph,scan,s,goal,C2RegionGraph.DEFAULT_TILE_SIZE,
                C2HierarchicalPlanner.DEFAULT_BUDGET);
        check(b2.found() && b3.found(),"B2/B3 both find 3D path");
        check(Math.abs(b2.cost()-b3.cost()) < 1e-6,"B3 matches B2 directed weighted optimum");
        check(b3.path().equals(b2.path()),"B3 path matches B2 on unique fixture");
        check(b2.path().size()==4,"3D path includes all directed segments");
        var route=new C2WaypointExecution(b3.path());
        var expected=List.of(C2TraversabilityGraph.MoveKind.STEP_UP,
                C2TraversabilityGraph.MoveKind.JUMP_UP,C2TraversabilityGraph.MoveKind.SAFE_DESCEND);
        for(int i=0;i<expected.size();i++) {
            var kind=expected.get(i);
            check(route.currentKind()==kind,"typed step "+i);
            check(route.edgeAllowed(graph),"fresh directed edge exists step "+i);
            UUID t=UUID.randomUUID();route.started(t);
            check(route.observe(UUID.randomUUID(),false,"TRAVERSAL_COMPLETE_"+kind,true)
                    == C2WaypointExecution.Update.FAILED,"reject stale receipt");
            check(route.waiting(),"stale ticket cannot consume good receipt");
            check(route.observe(t,false,"COMPLETE",true)==C2WaypointExecution.Update.FAILED,
                    "flat COMPLETE cannot authorize vertical traversal");
            // Build a separate route to check each proper segment is accepted;
            // once a matching bad terminal arrives the route is intentionally failed.
            var next=new C2WaypointExecution(b3.path().subList(i,i+2));
            UUID good=UUID.randomUUID();next.started(good);
            check(next.observe(good,false,"TRAVERSAL_COMPLETE_"+kind,true)
                    ==C2WaypointExecution.Update.COMPLETE,"typed verified receipt accepted");
            // Consume the saved route using the actual ticket on a new fixture.
            if (i+1<expected.size()) route=new C2WaypointExecution(b3.path().subList(i+1,b3.path().size()));
        }
        // Position realignment must use a *separate* verified C1 WALK ticket,
        // without accidentally consuming the following vertical waypoint.
        var centered=new C2WaypointExecution(List.of(n(0,1),n(1,1.5)));
        UUID align=UUID.randomUUID();centered.startedCentering(align);
        check(centered.centering() && centered.receiptTarget().equals(n(0,1)),"centering owns origin only");
        check(centered.observe(UUID.randomUUID(),false,"COMPLETE",true)
                ==C2WaypointExecution.Update.FAILED,"centering ignores stale receipt");
        check(centered.waiting(),"stale centering does not steal ticket");
        check(centered.observe(align,false,"COMPLETE",true)
                ==C2WaypointExecution.Update.NEXT,"centering advances to same edge");
        check(centered.currentKind()==C2TraversabilityGraph.MoveKind.STEP_UP
                && centered.currentIndex()==1 && !centered.waiting(),"centering did not consume edge");
        UUID step=UUID.randomUUID();centered.started(step);
        check(centered.observe(step,false,"TRAVERSAL_COMPLETE_STEP_UP",true)
                ==C2WaypointExecution.Update.COMPLETE,"new independent vertical ticket verified");
        var failCenter=new C2WaypointExecution(List.of(n(0,1),n(1,1.5)));
        UUID badCenter=UUID.randomUUID();failCenter.startedCentering(badCenter);
        check(failCenter.observe(badCenter,false,"BLOCKED",false)==C2WaypointExecution.Update.FAILED,
                "failed centering never promotes path");
        var oneWay=C2TraversabilityGraph.build3D(scan,(a,b,k)->
                a.x()<b.x() && k!=C2TraversabilityGraph.MoveKind.WALK);
        check(has(oneWay,0,1,1,1.5,C2TraversabilityGraph.MoveKind.STEP_UP),"forward permission kept");
        check(!has(oneWay,1,1.5,0,1,C2TraversabilityGraph.MoveKind.STEP_DOWN),"no invented reverse edge");
        var forward=C2AStarPlanner.plan(oneWay,scan,s,goal,845);
        var reverse=C2AStarPlanner.plan(oneWay,scan,goal,s,845);
        check(forward.found() && !reverse.found(),"A* respects one-way terrain motion");
        var rForward=C2HierarchicalPlanner.plan(oneWay,scan,s,goal,4,12000);
        var rReverse=C2HierarchicalPlanner.plan(oneWay,scan,goal,s,4,12000);
        check(rForward.found() && !rReverse.found(),"B3 respects directed portals");
        check(Math.abs(rForward.cost()-forward.cost())<1e-6,"B3 directed portal cost agrees with B2");
        var blocked=C2TraversabilityGraph.build3D(scan,(a,b,k)->false);
        check(!new C2WaypointExecution(b3.path()).edgeAllowed(blocked),"fresh terrain invalidation blocks segment");
        var old=C2TraversabilityGraph.build(scan,(a,b)->true);
        check(old.edges().isEmpty(),"legacy B1 graph intentionally remains flat");
        check(C2TraversabilityGraph.kind(1.5)==null,"parkour excluded");
        check(C2TraversabilityGraph.kind(-2)==null,"unsafe fall excluded");
        // No shared run state: identical immutable routes may be owned by 10 NPCs.
        for(int actors : new int[]{1,3,5,10}) {
            var sessions=new java.util.ArrayList<C2WaypointExecution>();
            var tickets=new java.util.HashSet<UUID>();
            for(int i=0;i<actors;i++) {
                var x=new C2WaypointExecution(b3.path());
                UUID t=UUID.randomUUID();x.started(t);
                check(tickets.add(t),"unique actor ticket");sessions.add(x);
            }
            for(var x:sessions) check(x.waiting() && x.edgeAllowed(graph)==false,"owned segment not restarted");
        }
        System.out.println("PASS S3 3D Graph/B2/B3/Typed Waypoint: "+assertions+" assertions (pure Java)");
    }
}
