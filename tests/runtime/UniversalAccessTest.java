package io.github.sirp00m.npcortex.agent;

import java.util.*;
import io.github.sirp00m.npcortex.agent.Navigation3D.Cell;
import io.github.sirp00m.npcortex.AgenticTaskPolicy;

/** Replay real planner segments on mutable voxels. Not a Minecraft physics test. */
public final class UniversalAccessTest {
    static int assertions;
    static void check(boolean value,String message) { assertions++; if(!value) throw new AssertionError(message); }
    static final class World implements Navigation3D.World {
        Set<Cell> blocks=new HashSet<>(); Cell goal,origin;
        boolean tower, bridge; int radius=24;
        World(Cell goal) { this.goal=goal; }
        public boolean allowed(Cell p) {
            return p.y()>=0 && p.y()<=321 && Math.abs(p.z())<=1
                    && Math.abs(p.x()-origin.x())<=radius && Math.abs(p.y()-origin.y())<=32
                    && (!tower || p.x()==0 && p.z()==0)
                    && (!bridge || p.z()==0 && p.y()<=2);
        }
        public boolean clear(Cell p) { return !blocks.contains(p); }
        public boolean support(Cell p) { return blocks.contains(p); }
        public boolean goal(Cell p) { return goal.equals(p); }
        public double estimate(Cell p) { return Math.abs(p.x()-goal.x())+Math.abs(p.y()-goal.y())+Math.abs(p.z()-goal.z()); }
    }
    static void trip(boolean tower,boolean bridge) {
        Cell at=new Cell(0,1,0),start=at;
        World w=new World(tower?new Cell(0,90,0):new Cell(170,1,0)); w.tower=tower;w.bridge=bridge;
        if(tower || bridge) w.blocks.add(at.add(0,-1,0));
        else for(int x=-1;x<=171;x++) for(int z=-1;z<=1;z++) w.blocks.add(new Cell(x,0,z));
        Set<Cell> initial=Set.copyOf(w.blocks);
        var journal=new ArrayList<Navigation3D.Step>();
        int bag=256,used=0,segments=0;
        while(!at.equals(w.goal) && segments<100) {
            w.origin=at;
            var result=Navigation3D.search(w,at,bag,true);
            check(result.route()!=null,"segment exists"); var route=result.route();
            check(result.expanded()<=Navigation3D.MAX_NODES,"per-search expansion budget");
            check(route.materials()<=Navigation3D.MAX_EDITS,"per-segment edit budget");
            check(!route.steps().isEmpty(),"no no-op segment");
            for(var step:route.steps()) {
                check(step.from().equals(at),"segments connect physically");
                if(step.placed()!=null) {
                    check(bag>0 && w.clear(step.placed()),"inventory and no overwrite");
                    w.blocks.add(step.placed()); bag--; used++;
                }
                at=step.to();
                check(w.clear(at) && w.clear(at.add(0,1,0)) && w.support(at.add(0,-1,0)),"supported clear body");
                journal.add(step);
            }
            check(route.reached()==at.equals(w.goal),"partial cannot pretend arrival");
            check(256-bag==used,"placed blocks conserved across segments");
            segments++;
        }
        check(at.equals(w.goal),"distant objective actually reached");
        check(segments>1,"multiple bounded searches");
        if(tower || bridge) check(used>12,"lifetime build exceeds old cap");
        check(w.blocks.size()==initial.size()+used,"structures retained until finalization");
        for(int i=journal.size()-1;i>=0;i--) {
            var step=journal.get(i);
            if(step.placed()!=null) { check(w.blocks.remove(step.placed()),"only own journal edits removed");bag++; }
            at=step.from(); check(w.blocks.contains(at.add(0,-1,0)),"reverse step support");
        }
        check(at.equals(start) && w.blocks.equals(initial) && bag==256,"cleanup preserves terrain and all inventory");
    }
    static void terrainFrontiers() {
        Cell origin=new Cell(0,1,0);
        Navigation3D.World world=new Navigation3D.World() {
            public boolean allowed(Cell c) { return Math.abs(c.x())<=10
                    && Math.abs(c.z())<=10 && c.y()>=0 && c.y()<=3; }
            public boolean clear(Cell c) { return c.y()>0 && !(c.x()==2 && Math.abs(c.z())<=1); }
            public boolean support(Cell c) { return c.y()==0 && c.x()!=-4; }
            public boolean goal(Cell c) { return false; }
            public double estimate(Cell c) { return 0; }
        };
        var routes=Navigation3D.terrainRoutes(world,origin,10,32,1400);
        var reachable=routes.routeTo(new Cell(7,1,0));
        check(reachable!=null,"frontier around straight-line obstruction reachable");
        check(reachable.steps().size()>7,"connected route actually detours around wall");
        for(var step:reachable.steps()) {
            check(step.move()==Navigation3D.Move.WALK && step.placed()==null,"explore cannot build");
            check(world.clear(step.to()) && world.clear(step.to().add(0,1,0))
                && world.support(step.to().add(0,-1,0)),"connected walk terrain verified");
        }
        check(routes.routeTo(new Cell(-4,1,0))==null,"no frontier on unsupported stand");
        check(routes.routeTo(new Cell(50,1,0))==null,"bounded observation horizon");
        check(routes.routeTo(origin).steps().isEmpty(),"already-at target never advertised");
        Navigation3D.World moat=new Navigation3D.World() {
            public boolean allowed(Cell c) { return c.y()>=0 && c.y()<=3
                && Math.abs(c.x())<=10 && Math.abs(c.z())<=10; }
            public boolean clear(Cell c) { return c.y()>0; }
            public boolean support(Cell c) { return c.y()==0 && c.x()!=1; }
            public boolean goal(Cell c) { return false; }
            public double estimate(Cell c) { return 0; }
        };
        check(Navigation3D.terrainRoutes(moat,origin,10,32,1400)
                .routeTo(new Cell(8,1,0))==null,"island safe stand cannot be offered");
        // Walkable != placeable: simulated noncolliding vegetation is not
        // automatically destroyed by scaffold search.
        Navigation3D.World vegetation=new Navigation3D.World() {
            public boolean allowed(Cell c) { return c.y()>=0 && c.y()<=4 && Math.abs(c.x())<=1 && c.z()==0; }
            public boolean clear(Cell c) { return c.y()>0; }
            public boolean placeable(Cell c) { return clear(c) && !c.equals(origin); }
            public boolean support(Cell c) { return c.equals(new Cell(0,0,0)); }
            public boolean goal(Cell c) { return c.equals(new Cell(0,3,0)); }
            public double estimate(Cell c) { return 3-c.y(); }
        };
        check(Navigation3D.search(vegetation,origin,1,false).route()==null,
            "planner must not place a pillar into walkable vegetation");
    }
    public static void main(String[] args) {
        trip(true,false);trip(false,true);trip(false,false);
        terrainFrontiers();
        var w=new World(new Cell(0,50,0));w.tower=true;w.origin=new Cell(0,1,0);w.blocks.add(new Cell(0,0,0));
        check(Navigation3D.search(w,w.origin,0,true).route()==null,"no supplies cannot create blocks");
        w.blocks.add(new Cell(0,3,0));
        check(Navigation3D.search(w,w.origin,64,true).route()==null,"no jumping through roof");
        w.blocks.clear();
        check(Navigation3D.search(w,w.origin,64,true).status()==Navigation3D.Status.INVALID_START,"airborne unsupported start rejected");
        check(!MissionAccounting.cleanupReady(1,100,0,6,100),"unfinished tree retains structure");
        check(MissionAccounting.cleanupReady(0,2,4,6,6),"borrowed blocks do not block cleanup");
        check(MissionAccounting.cleanupReady(0,0,0,6,6),"may descend to physically collect ground drops");
        check(!AgenticTaskPolicy.verifiedComplete(2,0,6,4),"borrowed blocks cannot fake finish");
        check(!AgenticTaskPolicy.verifiedComplete(6,0,6,1),"finish requires cleanup");
        check(AgenticTaskPolicy.verifiedComplete(6,0,6,0),"real returned bag permits finish");
        check(AgenticTaskPolicy.canContinue(100000,0,0,0),"lifetime request count not termination");
        check(!AgenticTaskPolicy.canContinue(100000,8,0,0),"repeated failures still stop");
        check(!AgenticTaskPolicy.canContinue(100000,0,0,5),"no-progress still stops");
        for(var kind:SkillRegistry.Target.values()) for(String ref:new String[]{"","b0","b15","d0","d15","m0","m5","e0","e7"}) {
            boolean expected=switch(kind) {
                case NONE->ref.isEmpty();case BLOCK->ref.startsWith("b");case DROP->ref.startsWith("d");
                case ITEM->ref.startsWith("m");case WAYPOINT->ref.startsWith("e");
            };
            check(TargetReferences.valid(kind,ref)==expected,"typed reference contract "+kind+":"+ref);
        }
        check(!TargetReferences.valid(SkillRegistry.Target.ITEM,"m6"),"material upper bound");
        check(!TargetReferences.valid(SkillRegistry.Target.WAYPOINT,"e8"),"frontier upper bound");
        check(!TargetReferences.valid(SkillRegistry.Target.BLOCK,"b00"),"noncanonical ref rejected");
        System.out.println("Universal access: "+assertions+" assertions passed (voxel replay, not Minecraft).");
    }
}
