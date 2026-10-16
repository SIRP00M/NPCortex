package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** P1-A terrain planning data structure tests. Pure Java, no Forge/world assumptions. */
public final class C2NavGridSelfTest {
    private static int assertions;
    private static void check(boolean ok, String why) {
        assertions++;
        if (!ok) throw new AssertionError(why);
    }
    private static void reject(Runnable action) {
        assertions++;
        try { action.run(); }
        catch (IllegalArgumentException good) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
    public static void main(String[] args) {
        AtomicInteger loadedCalls = new AtomicInteger(), sampleCalls = new AtomicInteger();
        C2NavGrid.Probe probe = new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) {
                loadedCalls.incrementAndGet();
                return x != 1; // entire column is unknown and MUST NOT be sampled
            }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) {
                sampleCalls.incrementAndGet();
                C2NavGrid.Condition condition = switch (z) {
                    case -1 -> C2NavGrid.Condition.HAZARDOUS;
                    case 1 -> C2NavGrid.Condition.OBSTRUCTED;
                    default -> y == 69 || y == 71
                            ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED;
                };
                return new C2NavGrid.Cell(x,y,z,y+1.0,condition);
            }
        };
        C2NavGrid.Snapshot scan = C2NavGrid.scan(0,69,0,1,probe);
        check(scan.radius()==1,"radius");
        check(scan.columnCount()==9,"column cap");
        check(loadedCalls.get()==9,"exactly one loaded check per column");
        check(sampleCalls.get()==6*5,"no unloaded column sampled");
        check(scan.cells().size()==33,"unknown cells use sentinel only");
        check(scan.count(C2NavGrid.Condition.UNLOADED)==3,"unknown not promoted to air");
        check(scan.count(C2NavGrid.Condition.WALKABLE)==4,"two discrete elevations in each safe column");
        check(scan.count(C2NavGrid.Condition.HAZARDOUS)==10,"hazard preserved");
        check(scan.count(C2NavGrid.Condition.OBSTRUCTED)==10,"obstruction preserved");
        check(scan.count(C2NavGrid.Condition.UNSUPPORTED)==6,"unknown floor not counted walkable");
        check(scan.walkableAt(0,0).size()==2,"3D multi-height cells not flattened");
        check(scan.walkableAt(1,0).isEmpty(),"unloaded column has no standing nodes");
        check(scan.walkableAt(0,1).isEmpty(),"blocked column has no standing nodes");
        check(scan.walkableAt(0,0).get(0).supportY()==69,"preserve absolute floor Y");
        check(scan.walkableAt(0,0).get(1).supportY()==71,"preserve second floor Y");
        check(scan.cells().stream().allMatch(c -> c.x() >= -1 && c.x()<=1 && c.z() >= -1 && c.z() <= 1),"bounded XY");
        reject(()->C2NavGrid.scan(0,69,0,0,probe));
        reject(()->C2NavGrid.scan(0,69,0,7,probe));
        reject(()->C2NavGrid.scan(Integer.MAX_VALUE,69,0,1,probe));
        reject(()->new C2NavGrid.Cell(0,0,0,Double.NaN,C2NavGrid.Condition.WALKABLE));
        reject(()->C2NavGrid.scan(0,69,0,1,new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return true; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) { return new C2NavGrid.Cell(x+1,y,z,y+1,C2NavGrid.Condition.WALKABLE); }
        }));
        reject(()->C2NavGrid.scan(0,69,0,1,new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return true; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) { return null; }
        }));
        C2NavGrid.Probe safe = new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return true; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1,C2NavGrid.Condition.WALKABLE);
            }
        };
        C2NavGrid.Snapshot max = C2NavGrid.scan(0,69,0,C2NavGrid.MAX_RADIUS,safe);
        check(max.cells().size()==C2NavGrid.MAX_CANDIDATES,"strict scan cap");
        check(max.count(C2NavGrid.Condition.WALKABLE)==C2NavGrid.MAX_CANDIDATES,"all loaded safe candidates");
        List<C2NavGrid.Cell> source = new ArrayList<>();
        source.add(new C2NavGrid.Cell(0,0,0,1,C2NavGrid.Condition.WALKABLE));
        C2NavGrid.Snapshot immutable = new C2NavGrid.Snapshot(0,0,0,1,source);
        source.clear();
        check(immutable.cells().size()==1,"defensive copy from input");
        assertions++;
        try { immutable.cells().clear();throw new AssertionError("snapshot should be immutable"); }
        catch (UnsupportedOperationException good) { /* expected */ }
        // Pure Java allocation/performance microcheck, *not* Minecraft Server MSPT validation.
        long started=System.nanoTime();
        for (int i=0;i<200;i++) C2NavGrid.scan(0,69,0,C2NavGrid.MAX_RADIUS,safe);
        double ms=(System.nanoTime()-started)/1_000_000.0;
        check(ms>=0 && Double.isFinite(ms),"finite pure Java timing");
        System.out.printf(java.util.Locale.ROOT,
                "PASS C2 P1-A NavGrid: %d assertions; max=%d cells, pure Java 200 scans=%.2fms (not Forge MSPT)%n",
                assertions,C2NavGrid.MAX_CANDIDATES,ms);
    }
}
