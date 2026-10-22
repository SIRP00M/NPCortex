package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.Set;

/** Pure Java structural regression for terrain-native 3D Follow selection and
 * bounded repeated recovery. Forge physics/gameplay are separately pending. */
public final class C2TerrainNativeFollowCSelfTest {
    private static int assertions;
    private static void ok(boolean valid,String label) {
        assertions++;if(!valid) throw new AssertionError(label);
    }
    private static C2NavGrid.Snapshot fixture(int mode) {
        return C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                boolean hole=(mode==1 && x>=1 && x<=3 && z>=-2 && z<=2)
                        || (mode==4 && x>=1);
                int floor=mode==2 && x>=1?69:68;
                boolean safe=!hole && y==floor;
                double risk=mode==3 && z==0 && x>=1 && x<=3 && safe?8.0:0;
                return new C2NavGrid.Cell(x,y,z,y+1,
                        safe?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.UNSUPPORTED,risk);
            }
        });
    }
    private static C2TraversabilityGraph graph(C2NavGrid.Snapshot scan) {
        return C2TraversabilityGraph.build3DWithRisk(scan,(a,b,k)->
                C2TraversalCost.Assessment.clear());
    }
    private static C2TerrainNativeFollowPlanner.Selection choose(C2TraversabilityGraph g,
            double px,double py,double pz) {
        return C2TerrainNativeFollowPlanner.select(g,new C2AStarPlanner.Target(0,68,0),
                .5,69,.5,px,py,pz,Set.of(),null);
    }
    private static boolean everyEdgeExists(C2TraversabilityGraph g,
            List<C2TraversabilityGraph.Node> path) {
        for(int i=1;i<path.size();i++) {
            var a=path.get(i-1);var b=path.get(i);
            if(g.neighbors(a).stream().noneMatch(e->e.to().equals(b)))return false;
        }
        return true;
    }
    public static void main(String[] args) {
        var flat=graph(fixture(0));
        var far=choose(flat,20.5,69,.5);
        ok(far.found(),"open flat ground has verified rolling corridor");
        ok(far.expanded()<=3000,"strict bounded graph search");
        ok(far.plan().source()==C2HierarchicalPlanner.Source.FOLLOW_TERRAIN_NATIVE,
                "terrain-native source distinguished from operator C2");
        ok(everyEdgeExists(flat,far.plan().path()),"flat corridor only real graph edges");
        ok(far.plan().path().get(far.plan().path().size()-1).x()>0,
                "rolling corridor makes forward progress");

        var ravine=graph(fixture(1));
        var around=choose(ravine,5.5,69,.5);
        ok(around.found(),"seek safe ring by routing AROUND ravine");
        ok(everyEdgeExists(ravine,around.plan().path()),"ravine path follows actual directed edges");
        ok(around.plan().path().stream().noneMatch(n->n.x()>=1 && n.x()<=3 && Math.abs(n.z())<=2),
                "never invent support above gap");
        ok(around.plan().path().stream().anyMatch(n->Math.abs(n.z())>=3),
                "detour exists outside ravine edge");
        ok(C2F2Locomotion.smooth(ravine,around.plan().path()).get(0).equals(around.plan().path().get(0)),
                "safe smoothing retains physical origin");

        var cliff=graph(fixture(4));
        var tangent=C2TerrainNativeFollowPlanner.select(cliff,
                new C2AStarPlanner.Target(0,68,0),.5,69,.5,
                20.5,69,.5,Set.of(),null,1,true);
        ok(tangent.found() && tangent.reason().equals("VERIFIED_BOUNDED_LATERAL_DETOUR"),
                "allow bounded lateral escape when no forward node is reachable");
        ok(tangent.detourSide()==1,"keep previous left/right detour decision stable");
        ok(everyEdgeExists(cliff,tangent.plan().path()),"tangent never invents cliff support");
        ok(tangent.plan().path().stream().allMatch(n->n.x()<=0),"sideways detour stays on grounded rim");
        ok(!C2TerrainNativeFollowPlanner.select(cliff,
                new C2AStarPlanner.Target(0,68,0),.5,69,.5,
                20.5,69,.5,Set.of(),null,1,false).found(),
                "six-hop guard forbids endless lateral search");

        var plateau=graph(fixture(2));
        var raised=choose(plateau,5.5,70,.5);
        ok(raised.found(),"verified 3D rising path chosen");
        ok(everyEdgeExists(plateau,raised.plan().path()),"vertical stage is real directed edge");
        ok(raised.plan().path().stream().anyMatch(n->n.footY()==70),
                "uphill path reaches higher support");
        ok(raised.plan().path().stream().anyMatch(n->n.footY()==69),
                "uphill path starts at physical original height");
        ok(raised.plan().path().stream().anyMatch(n->n.footY()==70)
                && raised.plan().path().stream().anyMatch(n->n.footY()==69),"height discontinuity maintained");

        var risky=graph(fixture(3));
        var safe=choose(risky,20.5,69,.5);
        ok(safe.found(),"risk-aware rolling corridor found");
        ok(safe.plan().path().stream().noneMatch(n->n.z()==0 && n.x()>=1 && n.x()<=3),
                "prefer safe detour over penalized straight line");
        var only= C2TraversabilityGraph.build3D(fixture(0),(a,b,k)->false);
        ok(!choose(only,20.5,69,.5).found(),"unreachable target fails closed");

        var alternative=C2TerrainNativeFollowPlanner.select(flat,
                new C2AStarPlanner.Target(0,68,0),.5,69,.5,
                20.5,69,.5,Set.of(far.plan().path().get(1)),null);
        ok(alternative.found(),"failed waypoint memory does not declare open space blocked");
        ok(everyEdgeExists(flat,alternative.plan().path()),"alternative uses only real edges");

        var policy=new C2FollowPolicy();
        ok(policy.failed(0) && policy.failed(20) && !policy.failed(40),"three failures trigger bounded pause");
        ok(!policy.terrainReprobe(79),"initial delay prevents busy spin");
        ok(policy.terrainReprobe(80),"first stationary world refresh after 2 seconds");
        ok(policy.terrainReprobes()==1,"reprobe counter tracked");
        ok(policy.failed(80) && policy.failed(100) && !policy.failed(120),"next failure window bounded");
        ok(!policy.terrainReprobe(199),"second delay grows with repeat failures");
        ok(policy.terrainReprobe(200),"second fresh re-probe permitted");
        ok(policy.failed(200) && policy.failed(220) && !policy.failed(240),"further failures remain bounded");
        ok(!policy.terrainReprobe(359),"third delay caps at 6 seconds");
        ok(policy.terrainReprobe(360),"periodic re-probe allows terrain mutations without player movement");
        policy.resetFailureWindow();
        ok(policy.terrainReprobes()==0,"verified physical progress resets backoff");
        System.out.println("PASS C Terrain-Native Follow and bounded recovery: "+assertions+" assertions (pure JVM)");
    }
}
