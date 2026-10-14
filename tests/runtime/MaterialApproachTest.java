package io.github.sirp00m.npcortex.agent;

import java.util.HashSet;
import java.util.Set;
import io.github.sirp00m.npcortex.agent.Navigation3D.Cell;

/** Regression for the walk-onto-soil -> source-ineligible -> walk-again loop. */
public final class MaterialApproachTest {
    static int assertions;
    static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
    static boolean reachable(Cell feet, Cell soil, double eyeHeight) {
        return MaterialApproach.reachable(feet.x()+0.5,feet.y(),feet.z()+0.5,
                eyeHeight,1.8,0.6,soil.x(),soil.y(),soil.z(),4.5*4.5);
    }
    public static void main(String[] args) {
        Cell soil=new Cell(0,0,0);
        for(double eye:new double[]{1.53,1.62}) {
            check(!reachable(new Cell(0,1,0),soil,eye),"cannot stand on source");
            for(Cell neighbor:new Cell[]{new Cell(1,1,0),new Cell(-1,1,0),new Cell(0,1,1),new Cell(0,1,-1)}) {
                check(reachable(neighbor,soil,eye),"adjacent soil can actually be mined");
                check(1+(eye+0.5)*(eye+0.5)>5,"reproduces the old too-short eye range");
            }
            check(!reachable(new Cell(3,1,0),soil,eye),"mining reach alone does not prove pickup proximity");
        }
        check(!MaterialApproach.reachable(0.9,1,0.5,1.53,1.8,0.6,0,0,0,20.25),"reject body over source edge");
        check(!reachable(new Cell(0,4,0),soil,1.53),"distant vertical source outside pickup reach");

        // Replay approach, excavation and replanning for four different sources.
        // This verifies the actual planner + shared interaction geometry; it does
        // not simulate Minecraft item entities or claim their collection.
        Set<Cell> terrain=new HashSet<>();
        for(int x=-2;x<=12;x++) for(int z=-3;z<=3;z++) terrain.add(new Cell(x,0,z));
        Cell at=new Cell(0,1,0);
        int mined=0;
        for(Cell target:new Cell[]{new Cell(6,0,0),new Cell(8,0,0),new Cell(10,0,0),new Cell(11,0,1)}) {
            Navigation3D.World w=new Navigation3D.World() {
                public boolean allowed(Cell p) { return p.x()>=-2 && p.x()<=12 && Math.abs(p.z())<=3 && p.y()>=0 && p.y()<=3; }
                public boolean clear(Cell p) { return !terrain.contains(p); }
                public boolean support(Cell p) { return !p.equals(target) && terrain.contains(p); }
                public boolean goal(Cell p) { return reachable(p,target,1.53); }
                public double estimate(Cell p) { return Math.max(0,Math.sqrt(
                        Math.pow(p.x()-target.x(),2)+Math.pow(p.y()-target.y(),2)+Math.pow(p.z()-target.z(),2))-1); }
            };
            var result=Navigation3D.search(w,at,0,true);
            check(result.status()==Navigation3D.Status.COMPLETE,"soil route reaches a usable stand");
            for(var step:result.route().steps()) {
                check(step.move()==Navigation3D.Move.WALK,"flat resource search spends no blocks");
                check(!step.to().add(0,-1,0).equals(target),"never traverse the target support");
                at=step.to();
            }
            check(reachable(at,target,1.53),"arrival satisfies actual acquisition geometry");
            check(terrain.remove(target),"observed source mined once"); mined++;
            check(terrain.contains(at.add(0,-1,0)),"excavation preserves NPC support");
        }
        check(mined==4,"multiple soil acquisitions without moving onto next source");
        System.out.println("Material approach regression: "+assertions+" assertions passed (geometry/planner only).");
    }
}
