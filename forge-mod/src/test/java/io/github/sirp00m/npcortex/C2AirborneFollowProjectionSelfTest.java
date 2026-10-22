package io.github.sirp00m.npcortex;

import java.util.Set;

/** Pure Java airborne-target regression. Does NOT replace Forge flight tests. */
public final class C2AirborneFollowProjectionSelfTest {
    private static int assertions;
    private static void ok(boolean condition,String description) {
        assertions++;
        if(!condition) throw new AssertionError(description);
    }
    private static C2TraversabilityGraph graph(int kind) {
        var scan=C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) {return true;}
            public C2NavGrid.Cell sample(int x,int y,int z) {
                boolean walkable=switch(kind) {
                    case 1 -> (y==68 && x<=0) || (y==69 && x>=1);
                    case 2 -> y==68 && (x<=0 || x>=4); // gap between actor and target
                    case 3 -> (x<=0 && y==68) || (x>=2 && y==69); // separated ledge
                    default -> y==68;
                };
                return new C2NavGrid.Cell(x,y,z,y+1,
                        walkable?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.UNSUPPORTED);
            }
        });
        return C2TraversabilityGraph.build3D(scan,(a,b,move) -> true);
    }
    private static C2TraversabilityGraph.Node root(C2TraversabilityGraph graph) {
        return graph.nodes().stream().filter(n -> n.x()==0 && n.supportY()==68 && n.z()==0)
                .findFirst().orElseThrow();
    }
    private static void modeAndHysteresis() {
        var target=new C2FollowTargetProjection();
        ok(!target.airborne(),"default grounded");
        ok(!target.observe(false,false),"normal jump is not Creative flight");
        ok(!target.observe(false,true),"landing normal jump still grounded target mode");
        ok(target.followingDistance(0,120,69)==51,"grounded Follow keeps 3D stop distance");
        ok(target.outsideVerticalReach(120,69,3),"grounded far-vertical remains gated");
        ok(!target.observe(true,false),"one flying tick does not immediately switch");
        ok(!target.airborne(),"airborne mode debounced");
        ok(target.observe(true,false),"second flying tick switches mode");
        ok(target.airborne(),"flying mode active");
        ok(target.followingDistance(0,120,69)==0,"hover directly above ground NPC is within follow stop ring");
        ok(target.followingDistance(6,120,69)==6,"airborne player far XZ still followed");
        ok(!target.outsideVerticalReach(120,69,3),"airborne aerial Y does not trigger unreachable-height pause");
        ok(!target.observe(true,false),"stable mode produces no repeated transition");
        ok(!target.observe(true,true),"brief touch down does not flicker");
        ok(!target.observe(true,true),"landing stays debounced");
        ok(!target.observe(true,true),"third landing tick stays airborne");
        ok(target.observe(true,true),"sustained grounded state exits airborne even if flight toggle remains on");
        ok(!target.airborne(),"ground mode restored");
        ok(!target.observe(true,false),"new flight start is also debounced");
        ok(target.observe(true,false),"new flight start reenters mode");
        ok(!target.observe(false,false),"brief flight toggle release does not immediately flicker");
        ok(target.airborne(),"flight still held during debounce");
        ok(!target.observe(false,false),"second exit tick");
        ok(!target.observe(false,false),"third exit tick");
        ok(target.observe(false,false),"fourth exit tick clears airborne");
    }
    private static void projection() {
        var target=new C2FollowTargetProjection();
        var flat=graph(0);var from=root(flat);
        ok(target.effectiveY(flat,from,3.5,115,.5)==115,"grounded target retains actual 3D Y");
        target.observe(true,false);target.observe(true,false);
        ok(target.effectiveY(flat,from,3.5,115,.5)==69,"airborne target chooses actual supported ground");
        ok(target.effectiveY(flat,from,3.5,78,.5)==69,"vertical-only flying movement does not retarget ground Y");
        ok(target.effectiveY(flat,from,3.5,98,.5)==69,"flight-height changes keep stable projected floor");
        ok("AIRBORNE_REACHABLE_SUPPORT".equals(target.source()),"local projection source exposed");
        ok(target.effectiveY(flat,from,24.5,115,.5)==69,"far-away flight never guesses distant ground");
        ok("AIRBORNE_REMOTE_NEUTRAL".equals(target.source()),"outside scanned window neutral target Y");
        var plateau=graph(1);
        ok(target.effectiveY(plateau,root(plateau),4.5,130,.5)==70,
                "reachable elevated terrain chosen below flying player");
        ok(target.effectiveY(plateau,root(plateau),4.5,69,.5)==69,
                "support must not be above flying player's feet");
        var detached=graph(3);
        ok(target.effectiveY(detached,root(detached),4.5,130,.5)==69,
                "disconnected high ledge never projected as reachable support");
        ok("AIRBORNE_NO_REACHABLE_SUPPORT".equals(target.source()),
                "unreachable ground gives neutral Y rather than unsafe path");
        var gap=graph(2);
        ok(target.effectiveY(gap,root(gap),2.5,100,.5)==69,
                "no support above void results in neutral actor level");
        ok("AIRBORNE_NO_REACHABLE_SUPPORT".equals(target.source()),"no phantom standing surface");
        boolean threw=false;
        try {target.effectiveY(flat,from,Double.NaN,95,.5);}catch(IllegalArgumentException expected){threw=true;}
        ok(threw,"reject invalid coordinates");
        threw=false;
        try {target.effectiveY(flat,new C2TraversabilityGraph.Node(80,68,80,69),2,95,2);}
        catch(IllegalArgumentException expected){threw=true;}
        ok(threw,"reject uncertified graph start");
    }
    private static void plannerIntegration() {
        var terrain=graph(1);
        var target=new C2FollowTargetProjection();target.observe(true,false);target.observe(true,false);
        double effective=target.effectiveY(terrain,root(terrain),4.5,128,.5);
        var selected=C2TerrainNativeFollowPlanner.select(terrain,new C2AStarPlanner.Target(0,68,0),
                .5,69,.5,4.5,effective,.5,Set.of(),null);
        ok(selected.found(),"projected target still reaches a verified terrain route");
        ok(selected.plan().path().stream().allMatch(terrain::contains),"every path node is real");
        ok(selected.plan().path().stream().anyMatch(n->n.footY()==70),
                "3D walk-to-higher-ground remains available during flight");
        var grounded=new C2FollowTargetProjection();
        ok(grounded.effectiveY(terrain,root(terrain),4.5,128,.5)==128,
                "not-flying target never silently projected");
    }
    public static void main(String[] args) {
        modeAndHysteresis();projection();plannerIntegration();
        System.out.println("PASS Airborne Follow Target Projection: "+assertions+" assertions (pure Java)");
    }
}
