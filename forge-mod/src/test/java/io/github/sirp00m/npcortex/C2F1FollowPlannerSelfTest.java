package io.github.sirp00m.npcortex;

import java.util.List;

/** Synthetic graph integration; no Minecraft/Forge execution. */
public final class C2F1FollowPlannerSelfTest {
    private static int assertions;
    private static void ok(boolean yes,String m) { assertions++;if(!yes) throw new AssertionError(m); }
    public static void main(String[] args) {
        C2NavGrid.Snapshot flat=C2NavGrid.scan(0,68,0,6,new C2NavGrid.Probe() {
                    public boolean loaded(int x,int z) {return true;}
                    public C2NavGrid.Cell sample(int x,int y,int z) {
                        return new C2NavGrid.Cell(x,y,z,y+1.0,
                                y==68 ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.OBSTRUCTED);
                    }
                });
        var graph=C2TraversabilityGraph.build(flat,(from,to)->true);
        var from=new C2AStarPlanner.Target(0,68,0);
        var chosen=C2FollowPlanner.select(graph,flat,from,.5,.5,4.5,69.0,.5);
        ok(chosen.found(),"find safe follow ring in flat graph");
        ok(chosen.checked()>0 && chosen.checked()<=C2FollowPolicy.MAX_CANDIDATE_PLANS,"candidate budget");
        var target=chosen.plan().path().get(chosen.plan().path().size()-1);
        double d=Math.hypot(target.x()+.5-4.5,target.z()+.5-.5);
        ok(d>=1.8 && d<=3.35,"never path directly to player collision tile");
        ok(chosen.plan().expandedNodes()<=C2FollowPolicy.PER_PLAN_BUDGET,"per candidate budget");
        ok(chosen.plan().status()==C2HierarchicalPlanner.Status.PATH_FOUND,"B3 verified path");
        var farY=C2FollowPlanner.select(graph,flat,from,.5,.5,4.5,90.0,.5);
        ok(!farY.found() && farY.reason().equals("NO_SAFE_FOLLOW_RING"),"reject target without safe 3D ring");
        var fenced=C2TraversabilityGraph.build(flat,(a,b)->false);
        var noPath=C2FollowPlanner.select(fenced,flat,from,.5,.5,4.5,69,.5);
        ok(!noPath.found() && noPath.checked()<=4,"blocked graph bounded NO_PATH");
        for(int i=0;i<10;i++) {
            var state=new C2FollowPolicy();
            var plan=C2FollowPlanner.select(graph,flat,from,.5,.5,
                    3.5+(i%3),69,.5+(i%2));
            ok(plan.checked()<=4,"independent limited choice actor="+i);
            if(plan.found()) {
                state.planned(0,3.5+(i%3),.5+(i%2));
                ok(state.generation()==1,"independent route version actor="+i);
            }
        }
        for(var bad:List.of(Double.NaN,Double.POSITIVE_INFINITY)) {
            try {C2FollowPlanner.select(graph,flat,from,.5,.5,bad,69,.5);throw new AssertionError("nonfinite accepted");}
            catch(IllegalArgumentException expected) {assertions++;}
        }
        System.out.println("PASS C2-F1 bounded follow-ring planner: "+assertions+" assertions");
    }
}
