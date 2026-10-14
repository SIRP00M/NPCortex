package io.github.sirp00m.npcortex.agent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.sirp00m.npcortex.agent.Navigation3D.Cell;

/** Executable Java 17 tests; mock voxel geometry, not a Minecraft benchmark. */
public final class RuntimeCoreTest {
    private static int assertions;
    private static void check(boolean value,String message) {
        assertions++; if(!value) throw new AssertionError(message);
    }
    private static final class World implements Navigation3D.World {
        final Set<Cell> blocks=new HashSet<>();
        final Set<Cell> forbidden=new HashSet<>();
        Cell goal;
        World(Cell goal) { this.goal=goal; }
        public boolean allowed(Cell p) { return Math.abs(p.x())<=8 && Math.abs(p.z())<=2 && p.y()>=0 && p.y()<=14 && !forbidden.contains(p); }
        public boolean clear(Cell p) { return !blocks.contains(p); }
        public boolean support(Cell p) { return blocks.contains(p); }
        public boolean goal(Cell p) { return goal.equals(p); }
        public double estimate(Cell p) { return Math.abs(goal.x()-p.x())+Math.abs(goal.y()-p.y())+Math.abs(goal.z()-p.z()); }
        void floor() { for(int x=-8;x<=8;x++) for(int z=-2;z<=2;z++) blocks.add(new Cell(x,0,z)); }
    }
    private static void replay(World w,Cell start,Navigation3D.Plan plan) {
        check(plan!=null,"expected route");
        Set<Cell> blocks=new HashSet<>(w.blocks), initial=Set.copyOf(w.blocks);
        Cell at=start;
        for(var step:plan.steps()) {
            check(step.from().equals(at),"route continuity");
            if(step.placed()!=null) {
                check(!blocks.contains(step.placed()),"no overwrite");
                if(step.move()==Navigation3D.Move.BRIDGE) check(blocks.contains(at.add(0,-1,0)),"bridge support");
                if(step.move()==Navigation3D.Move.PILLAR) check(step.placed().equals(at),"pillar under jump");
                blocks.add(step.placed());
            }
            at=step.to();
            check(!blocks.contains(at)&&!blocks.contains(at.add(0,1,0)),"clear body");
            check(blocks.contains(at.add(0,-1,0)),"floor support");
        }
        check(w.goal(at),"reached goal");
        for(int i=plan.steps().size()-1;i>=0;i--) {
            var step=plan.steps().get(i);
            if(step.placed()!=null) blocks.remove(step.placed());
            at=step.from();
            check(blocks.contains(at.add(0,-1,0)),"reverse support");
        }
        check(at.equals(start)&&blocks.equals(initial),"inverse route restores initial geometry");
    }
    private static void navigation() {
        Cell start=new Cell(0,1,0);
        World flat=new World(new Cell(5,1,0)); flat.floor();
        var p=Navigation3D.plan(flat,start,0); replay(flat,start,p); check(p.materials()==0,"walk consumes no blocks");
        World pillar=new World(new Cell(0,8,0)); pillar.floor();
        p=Navigation3D.plan(pillar,start,7); replay(pillar,start,p); check(p.materials()==7,"tall pillar beyond legacy 6");
        check(Navigation3D.plan(pillar,start,6)==null,"insufficient building supplies");
        World bridge=new World(new Cell(4,1,0)); bridge.blocks.add(start.add(0,-1,0));
        p=Navigation3D.plan(bridge,start,4); replay(bridge,start,p); check(p.materials()==4,"bridge across gap");
        check(Navigation3D.plan(bridge,start,0)==null,"no unsupported walking");
        World mixed=new World(new Cell(3,4,0)); mixed.blocks.add(start.add(0,-1,0));
        p=Navigation3D.plan(mixed,start,6); replay(mixed,start,p);
        check(p.steps().stream().anyMatch(s->s.move()==Navigation3D.Move.PILLAR),"mixed route climbs");
        check(p.steps().stream().anyMatch(s->s.move()==Navigation3D.Move.BRIDGE),"mixed route extends sideways");
        World unloaded=new World(new Cell(1,1,0)); unloaded.floor(); unloaded.forbidden.add(unloaded.goal);
        check(Navigation3D.plan(unloaded,start,12)==null,"unloaded/forbidden target");
        World roof=new World(new Cell(0,4,0)); roof.floor();
        for(int x=-8;x<=8;x++)for(int z=-2;z<=2;z++)roof.blocks.add(new Cell(x,3,z));
        check(Navigation3D.plan(roof,start,12)==null,"cannot pillar through ceiling");
        World inside=new World(new Cell(2,1,0)); inside.floor(); inside.blocks.add(start);
        check(Navigation3D.plan(inside,start,12)==null,"collision at initial feet");
        World step=new World(new Cell(2,2,0)); step.floor(); step.blocks.add(new Cell(2,1,0));
        p=Navigation3D.plan(step,start,0); replay(step,start,p);
        check(p.materials()==0,"walk up existing one-block step");
        World empty=new World(start);
        check(Navigation3D.plan(empty,start,12)==null,"airborne start rejected");
        World max=new World(new Cell(0,14,0)); max.floor();
        check(Navigation3D.plan(max,start,999)==null,"hard material cap");
        World same=new World(start); same.floor();
        check(Navigation3D.plan(same,start,0).steps().isEmpty(),"already reached no-op");
    }
    private static void lifecycle() {
        GoalState state=GoalState.READY;
        for(var next:new GoalState[]{GoalState.THINKING,GoalState.EXECUTING,GoalState.VERIFYING,GoalState.READY,
                GoalState.THINKING,GoalState.PAUSED,GoalState.CLEANING,GoalState.VERIFYING,GoalState.CANCELLED}) {
            check(state.permits(next),"valid lifecycle "+state+" -> "+next); state=next;
        }
        check(!GoalState.CANCELLED.permits(GoalState.EXECUTING),"cancel is terminal");
        check(!GoalState.SUCCEEDED.permits(GoalState.READY),"success is terminal");
        check(!GoalState.THINKING.permits(GoalState.SUCCEEDED),"model cannot report success");
        check(!GoalState.READY.permits(GoalState.SUCCEEDED),"verification required");
    }
    private static void registry() {
        SkillRegistry<AtomicInteger> r=new SkillRegistry<>();
        var skill=new SkillRegistry.Skill<AtomicInteger>("increment",SkillRegistry.Target.NONE,"Test verified effect",
                (c,t)->t.isEmpty(),(c,t)->c.incrementAndGet(),(c,t)->c.get()>0,(c,t)->c.set(0));
        r.register(skill);
        try {r.register(skill);throw new AssertionError("duplicate accepted");}catch(IllegalArgumentException expected){assertions++;}
        r.freeze();
        try {r.register(skill);throw new AssertionError("mutable registry");}catch(IllegalStateException expected){assertions++;}
        var c=new AtomicInteger();
        check(!r.execute("invented",c,""),"unknown skill rejected");
        check(!r.execute("increment",c,"unobserved"),"failed precondition prevents effect");
        check(c.get()==0,"no accidental execution");
        check(r.execute("increment",c,""),"registered effect executed");
        check(r.verify("increment",c,""),"verify effect");
        r.recover("increment",c,""); check(c.get()==0,"recovery callback");
        check(!r.verify("increment",c,""),"verification can fail");
    }
    private static void events() throws Exception {
        EventBus bus=new EventBus(); AtomicInteger delivered=new AtomicInteger();
        AutoCloseable sub=bus.subscribe(e->delivered.incrementAndGet());
        bus.subscribe(e->{throw new IllegalStateException("observer must not break executor");});
        UUID npc=UUID.randomUUID();
        for(int i=0;i<200;i++)bus.publish(new EventBus.Event(npc,"goal","result","ok",i));
        check(bus.history().size()==128,"bounded event history");
        check(delivered.get()==200 && bus.listenerErrors()==200,"isolated event consumers");
        sub.close(); bus.publish(new EventBus.Event(npc,"goal","cancel","ok",201));
        check(delivered.get()==200,"unsubscribe");
        bus.clear(); check(bus.history().isEmpty(),"server restart clears transient history");
    }
    private static void failures() {
        FailureHistory memory=new FailureHistory(java.util.List.of());
        memory.record("mine/block/123",7,"blocked line of sight");
        check(!memory.blocked("mine/block/123",7),"one transient failure permits retry");
        memory.record("approach/block/123",7,"no path");
        memory.record("mine/block/123",7,"blocked again");
        check(memory.blocked("mine/block/123",7),"interleaved failures remembered");
        check(!memory.blocked("mine/block/124",7),"different target is independent");
        check(!memory.blocked("mine/block/123",8),"changed physical evidence permits retry");
        check(!memory.blocked("climb/block/123",7),"alternate skill remains available");
        memory.record("navigate_3d_drop/drop/uuid-d0", 70,
                "[Action failed: no safe 3D pickup route found within limits.]");
        check(memory.blocked("navigate_3d_drop/drop/uuid-d0",70),
                "impossible drop route withheld immediately from the same world state");
        check(!memory.blocked("navigate_3d_drop/drop/uuid-d0",71),
                "changed pose/materials/terrain re-enables drop route");
        memory.record("navigate_3d/block/555", 80,
                "[Action failed: bounded 3D search found no safe supported route with current materials; search=NO_PATH; expanded=3.]");
        check(memory.blocked("navigate_3d/block/555",80),
                "unchanged unreachable log route withheld after one actual search");
        check(!memory.blocked("navigate_3d/block/555",81),
                "changed viewpoint or material permits a fresh route hypothesis");
        check(!memory.blocked("navigate_3d/block/556",80),
                "other goal logs remain independently navigable");
        memory.record("navigate_3d/block/557",80,"[Action failed: transient movement obstruction.]");
        check(!memory.blocked("navigate_3d/block/557",80),
                "transient 3D route errors still permit a retry");
        memory.record("navigate_3d_drop/drop/uuid-d1",70,
                "[Action failed: route changed during movement.]");
        check(!memory.blocked("navigate_3d_drop/drop/uuid-d1",70),
                "transient drop route failure still permits one retry");
        check(!memory.blocked("navigate_3d_drop/drop/uuid-d2",70),
                "another drop is not blocked");
        memory=new FailureHistory(memory.entries());
        check(memory.blocked("mine/block/123",7),"save/reload preserves evidence");
        for(int i=0;i<100;i++)memory.record("target/"+i,i,"x".repeat(300));
        check(memory.entries().size()==32,"failure memory bounded");
        check(memory.entries().get(0).reason().length()==180,"failure reason bounded");
    }
    private static void safeRetreat() {
        check(SafeRetreatPolicy.shouldDescend(true, java.util.List.of("pause","descend")),
                "dead-end temporary pillar must descend");
        check(SafeRetreatPolicy.shouldDescend(true, java.util.List.of("pause","descend","wait")),
                "waiting does not fix a stranded temporary pillar");
        check(SafeRetreatPolicy.shouldDescend(true, java.util.List.of("pause","restore","descend")),
                "descend before restore and owner intervention");
        check(!SafeRetreatPolicy.shouldDescend(false, java.util.List.of("pause","descend")),
                "never force descent without recorded supports");
        check(!SafeRetreatPolicy.shouldDescend(true, java.util.List.of("pause","descend","mine:b0")),
                "Qwen retains control when real mining is possible");
        check(!SafeRetreatPolicy.shouldDescend(true, java.util.List.of("pause","descend","navigate_3d:b0")),
                "Qwen retains control when 3D access is possible");
        check(!SafeRetreatPolicy.shouldDescend(true, java.util.List.of("pause")),
                "never attempt an unauthorized unoffered descent");
        check(SafeRetreatPolicy.offerHarvestRetreat(true,4,true,true,"NO_PATH",0),
                "blocked tree access offers Qwen a guarded reposition via descent");
        check(SafeRetreatPolicy.offerHarvestRetreat(true,2,true,true,"SEARCH_BUDGET",1),
                "budget-exhausted route also permits second reposition attempt");
        check(!SafeRetreatPolicy.offerHarvestRetreat(true,4,false,true,"NO_PATH",0),
                "no owned supports means no destructive descent");
        check(!SafeRetreatPolicy.offerHarvestRetreat(true,4,true,false,"NO_PATH",0),
                "do not descend without actual failed harvest navigation");
        check(!SafeRetreatPolicy.offerHarvestRetreat(true,0,true,true,"NO_PATH",0),
                "final tree recovery uses normal post-harvest cleanup");
        check(!SafeRetreatPolicy.offerHarvestRetreat(true,4,true,true,"COMPLETE",0),
                "a successful route must not unlock recovery demolition");
        check(!SafeRetreatPolicy.offerHarvestRetreat(true,4,true,true,"NO_PATH",2),
                "two retreats without goal mining exhaust safe reposition");
        check(!SafeRetreatPolicy.offerHarvestRetreat(false,4,true,true,"NO_PATH",0),
                "reposition only committed work tree");
        check(!SafeRetreatPolicy.pauseAfterRetreat(1),"first descent should replan");
        check(SafeRetreatPolicy.pauseAfterRetreat(2),"second unproductive descent pauses safely");
    }
    public static void main(String[] args)throws Exception {
        navigation(); lifecycle(); registry(); failures(); events(); safeRetreat();
        System.out.println("Runtime core: "+assertions+" assertions passed (simulated voxels; NOT in-game).");
    }
}
