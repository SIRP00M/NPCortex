package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A bounded, ephemeral, single-C1-token mixed WALK/3D Follow lease.
 * No movement is authorized here: the server must revalidate each stage against
 * the live directed graph, terrain, Vanilla body collision, and landing before
 * arm/advance. Never persisted or resumed across world restarts. */
final class C1Persistent3DChain {
    static final int MAX_STAGES=6;
    static final int MAX_TOTAL_TICKS=420;
    record Stage(C2TraversabilityGraph.Node from,C2TraversabilityGraph.Node to,
                 C2TraversabilityGraph.MoveKind kind) {
        Stage {
            Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(kind);
            if (from.equals(to) || C2TraversabilityGraph.kind(to.footY()-from.footY())!=kind)
                throw new IllegalArgumentException("Invalid mixed stage elevation");
            long dx=Math.abs((long)to.x()-from.x()),dz=Math.abs((long)to.z()-from.z());
            if (Math.max(dx,dz)<1 || Math.max(dx,dz)>(kind==C2TraversabilityGraph.MoveKind.WALK?3:1)
                    || (kind!=C2TraversabilityGraph.MoveKind.WALK && dx+dz!=1))
                throw new IllegalArgumentException("Mixed stage geometry unsafe");
        }
    }
    private final List<Stage> stages;
    private int current;
    private int age;
    private int stageAge;
    C1Persistent3DChain(List<Stage> input) {
        if(input==null || input.isEmpty() || input.size()>MAX_STAGES)
            throw new IllegalArgumentException("Mixed stage budget exceeded");
        ArrayList<Stage> checked=new ArrayList<>();
        boolean vertical=false;
        for(Stage step:input) {
            Objects.requireNonNull(step);
            if(!checked.isEmpty() && !checked.get(checked.size()-1).to().equals(step.from()))
                throw new IllegalArgumentException("Discontinuous mixed route");
            vertical|=step.kind()!=C2TraversabilityGraph.MoveKind.WALK;
            checked.add(step);
        }
        if(!vertical) throw new IllegalArgumentException("Flat route must use existing WALK controller");
        stages=List.copyOf(checked);
    }
    Stage current() {return stages.get(current);}
    int count() {return stages.size();}
    int consumed() {return current;}
    int stageAge() {return stageAge;}
    boolean tick() {age++;stageAge++;return age<=MAX_TOTAL_TICKS;}
    boolean advance() {
        if(current+1>=stages.size()) return false;
        current++;stageAge=0;return true;
    }
}
