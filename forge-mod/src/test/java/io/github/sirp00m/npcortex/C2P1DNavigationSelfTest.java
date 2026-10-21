package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** Synthetic terrain + real B2/B3 planner + waypoint protocol. NOT Forge gameplay. */
public final class C2P1DNavigationSelfTest {
    private static int checks;
    private static void ok(boolean v,String msg) {checks++;if(!v)throw new AssertionError(msg);}
    private static C2NavGrid.Snapshot scan(java.util.function.BiPredicate<Integer,Integer> blocked) {
        return C2NavGrid.scan(0,0,0,5,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z){return true;}
            public C2NavGrid.Cell sample(int x,int y,int z){
                return new C2NavGrid.Cell(x,y,z,y+1,y==0&&!blocked.test(x,z)
                        ?C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    private static C2TraversabilityGraph graph(C2NavGrid.Snapshot s) {
        return C2TraversabilityGraph.build(s,(a,b)->true);
    }
    private static C2AStarPlanner.Target t(int x,int z){return new C2AStarPlanner.Target(x,0,z);}
    private static C2HierarchicalPlanner.Result plan(C2NavGrid.Snapshot s,int a,int b,int budget) {
        return C2HierarchicalPlanner.plan(graph(s),s,t(a,0),t(b,0),4,budget);
    }
    public static void main(String[] args) {
        var clear=scan((x,z)->false);
        var direct=plan(clear,-4,4,12000);
        ok(direct.found() && direct.cost()==8.0,"straight route");
        var oldRoute=new C2WaypointExecution(direct.path());
        UUID oldTicket=UUID.randomUUID();oldRoute.started(oldTicket);
        ok(oldRoute.observe(oldTicket,false,"BLOCKED",false)==C2WaypointExecution.Update.FAILED,"blocked terminal");
        ok(!oldRoute.waiting(),"terminal ticket consumed once");
        var wall=scan((x,z)->x==0 && Math.abs(z)<4);
        var blockedGraph=graph(wall);
        var rerouted=C2HierarchicalPlanner.plan(blockedGraph,wall,t(-4,0),t(4,0),4,12000);
        var ref=C2AStarPlanner.plan(blockedGraph,wall,t(-4,0),t(4,0),845);
        ok(rerouted.found() && ref.found(),"wall with opening permits detour");
        ok(Math.abs(ref.cost()-rerouted.cost())<1e-7 && rerouted.cost()>direct.cost(),"detour optimal and longer");
        ok(rerouted.path().stream().noneMatch(n->n.x()==0 && Math.abs(n.z())<4),"never traverse obstructed wall");
        var changed=new C2WaypointExecution(rerouted.path());
        ok(changed.edgeAllowed(blockedGraph),"first new edge valid");
        ok(!changed.acceptsTicket(oldTicket),"old token cannot verify new route");
        ok(changed.observe(oldTicket,false,"COMPLETE",true)==C2WaypointExecution.Update.FAILED,"stale result rejected");
        ok(!changed.waiting(),"route remains idle after stale result");
        var p=new C2ReplanningPolicy();
        ok(p.request(C2ReplanningPolicy.Trigger.BLOCKED,20),"schedule after C1 terminal");
        ok(p.gate(39)==C2ReplanningPolicy.Gate.WAIT,"cooldown");
        p.evaluated(p.budget(),rerouted.expandedNodes(),rerouted.status().name(),true,40);
        ok(p.generation()==1 && p.attempts()==1 && !p.pending(),"replace one route generation only");
        var noPassage=scan((x,z)->x==0);
        var noRoute=plan(noPassage,-4,4,12000);
        ok(noRoute.status()==C2HierarchicalPlanner.Status.NO_PATH,"fully sealed barrier");
        var opening=scan((x,z)->x==0 && z!=0);
        var available=plan(opening,-4,4,12000);
        ok(available.found(),"opening wall produces path without restarting C0 action");
        ok(available.path().stream().anyMatch(n->n.x()==0&&n.z()==0),"path uses actual portal opening");
        var changing=new C2ReplanningPolicy();
        ok(changing.request(C2ReplanningPolicy.Trigger.EDGE_INVALIDATED,0),"edge failed mid-route");
        changing.evaluated(12000,noRoute.expandedNodes(),noRoute.status().name(),false,20);
        ok(changing.retryable() && changing.generation()==0,"NO_PATH is not a false completion");
        changing.evaluated(12000,available.expandedNodes(),available.status().name(),true,40);
        ok(changing.generation()==1 && changing.attempts()==2,"route reopens within bounded attempts");
        var cancelled=new C2ReplanningPolicy();
        ok(cancelled.request(C2ReplanningPolicy.Trigger.DEVIATED,0),"pending work");
        cancelled.stop();
        ok(!cancelled.pending() && cancelled.generation()==0,"cancellation blocks stale generation");
        for(int i=0;i<5;i++) {
            var same=plan(wall,-4,4,12000);
            ok(same.path().equals(rerouted.path()) && same.cost()==rerouted.cost(),"deterministic fresh replan");
        }
        long begin=System.nanoTime();
        for(int i=0;i<5;i++) plan(wall,-4,4,12000);
        double elapsedMs=(System.nanoTime()-begin)/1_000_000.0;
        ok(Double.isFinite(elapsedMs) && elapsedMs>=0,"bounded benchmark");
        System.out.printf(java.util.Locale.ROOT,
                "PASS C2-P1-D Reroute Integration: %d assertions; pure JVM 5 plans=%.2fms (NOT Forge MSPT)%n",
                checks,elapsedMs);
    }
}
