package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure Java B1 contracts; NOT a Forge collision/MSPT or real-gameplay test. */
public final class C2TraversabilityGraphSelfTest {
    private static int checks;
    private static void ok(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void reject(Runnable runnable, String message) {
        checks++;
        try { runnable.run(); }
        catch (IllegalArgumentException good) { return; }
        throw new AssertionError("Expected IllegalArgumentException: " + message);
    }
    private static C2NavGrid.Snapshot grid(int radius, C2NavGrid.Probe probe) {
        return C2NavGrid.scan(0,0,0,radius,probe);
    }
    private static C2NavGrid.Probe terrain(Terrain condition) {
        return new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return true; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) {
                C2NavGrid.Condition c= condition.at(x,y,z);
                return new C2NavGrid.Cell(x,y,z,y+1.0,c);
            }
        };
    }
    private interface Terrain { C2NavGrid.Condition at(int x,int y,int z); }
    private static C2NavGrid.Condition floor(int x,int y,int z) {
        return y == 0 ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED;
    }
    private static final C2TraversabilityGraph.ClearanceProbe CLEAR = (a,b)->true;
    private static C2TraversabilityGraph.Node node(int x,int y,int z) {
        return new C2TraversabilityGraph.Node(x,y,z,y+1.0);
    }

    public static void main(String[] args) {
        C2NavGrid.Snapshot flat = grid(1,terrain(C2TraversabilityGraphSelfTest::floor));
        C2TraversabilityGraph graph = C2TraversabilityGraph.build(flat,CLEAR);
        ok(graph.nodes().size() == 9,"nine flat standing nodes");
        ok(graph.edges().size() == 24,"12 orthogonal pairs -> 24 directed edges");
        ok(graph.neighbors(node(0,0,0)).size() == 4,"center has four cardinal neighbors");
        ok(graph.neighbors(node(-1,0,-1)).size() == 2,"corner has two neighbors");
        ok(graph.neighbors(node(0,1,0)).isEmpty(),"non-existing heights cannot form edges");
        ok(graph.edges().stream().allMatch(e -> e.kind() == C2TraversabilityGraph.MoveKind.WALK),"walk only");
        ok(graph.edges().stream().allMatch(e -> e.cost()==1.0),"unit cost future A* can consume");
        ok(graph.edges().stream().allMatch(e ->
                Math.abs(e.from().x()-e.to().x())+Math.abs(e.from().z()-e.to().z())==1),"no diagonals");
        ok(graph.edges().stream().allMatch(e -> e.from().footY()==e.to().footY()),"all transitions stay level");

        C2NavGrid.Snapshot wall=grid(1,terrain((x,y,z) ->
                y==0 && x==0 ? C2NavGrid.Condition.OBSTRUCTED : floor(x,y,z)));
        C2TraversabilityGraph w=C2TraversabilityGraph.build(wall,CLEAR);
        ok(w.nodes().size()==6,"walls remove standing nodes");
        ok(w.edges().size()==8,"wall splits into two 3-cell strips (8 directed edges)");
        ok(w.neighbors(node(-1,0,0)).stream().noneMatch(e -> e.to().x()>=0),"no edges through wall");
        ok(w.neighbors(node(1,0,0)).stream().noneMatch(e -> e.to().x()<=0),"no reverse wall edge");

        C2NavGrid.Snapshot various=grid(1,terrain((x,y,z)->{
            if (y!=0) return C2NavGrid.Condition.UNSUPPORTED;
            if (x==0 && z==0) return C2NavGrid.Condition.PARTIAL_SUPPORT;
            if (x==1 && z==0) return C2NavGrid.Condition.HAZARDOUS;
            if (x==0 && z==1) return C2NavGrid.Condition.OBSTRUCTED;
            return C2NavGrid.Condition.WALKABLE;
        }));
        C2TraversabilityGraph v=C2TraversabilityGraph.build(various,CLEAR);
        ok(v.nodes().size()==6,"partial, hazard and blocked excluded");
        ok(!v.contains(node(0,0,0)),"unknown partial support must not be promoted");
        ok(!v.contains(node(1,0,0)),"hazard cannot be a vertex");
        ok(!v.contains(node(0,0,1)),"obstructed cannot be vertex");

        C2NavGrid.Snapshot columns=grid(1,new C2NavGrid.Probe() {
            @Override public boolean loaded(int x,int z) { return x!=1; }
            @Override public C2NavGrid.Cell sample(int x,int y,int z) {
                return new C2NavGrid.Cell(x,y,z,y+1.0, floor(x,y,z));
            }
        });
        C2TraversabilityGraph col=C2TraversabilityGraph.build(columns,CLEAR);
        ok(col.nodes().size()==6,"unloaded column has zero vertices");
        ok(col.edges().size()==14,"loaded 2x3 rectangle has fourteen directed edges");
        ok(col.nodes().stream().noneMatch(n->n.x()==1),"no unloaded target");

        C2NavGrid.Snapshot twoLevels=grid(1,terrain((x,y,z)->
                y==0 || y==2 ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED));
        C2TraversabilityGraph height=C2TraversabilityGraph.build(twoLevels,CLEAR);
        ok(height.nodes().size()==18,"different standing heights retained");
        ok(height.edges().size()==48,"two independent floors");
        ok(height.neighbors(node(0,0,0)).stream().noneMatch(e->e.to().supportY()!=0),"no vertical teleport edge");
        ok(height.neighbors(node(0,2,0)).stream().noneMatch(e->e.to().supportY()!=2),"no invalid drop");

        C2NavGrid.Snapshot oneStep=grid(1,terrain((x,y,z)->
                (x<0 && y==0) || (x>=0 && y==1)
                        ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED));
        C2TraversabilityGraph sloped=C2TraversabilityGraph.build(oneStep,CLEAR);
        ok(sloped.edges().stream().noneMatch(e->e.from().x()<0 && e.to().x()>=0),"step up not approved");
        ok(sloped.edges().stream().noneMatch(e->e.from().x()>=0 && e.to().x()<0),"drop not approved");

        C2NavGrid.Snapshot twoDiagonal=grid(1,terrain((x,y,z)->
                y==0 && ((x==0 && z==0)||(x==1 && z==1))
                        ? C2NavGrid.Condition.WALKABLE : C2NavGrid.Condition.UNSUPPORTED));
        ok(C2TraversabilityGraph.build(twoDiagonal,CLEAR).edges().isEmpty(),"no diagonal corner cut");

        AtomicInteger validated = new AtomicInteger();
        C2TraversabilityGraph veto=C2TraversabilityGraph.build(flat,(a,b)->{
            validated.incrementAndGet();
            return a.x()!=0 && b.x()!=0;
        });
        ok(validated.get()>0,"must invoke safety probe");
        ok(veto.edges().stream().noneMatch(e->e.from().x()==0 || e.to().x()==0),"veto removes both directions");
        ok(C2TraversabilityGraph.build(flat,(a,b)->false).edges().isEmpty(),"fail closed without clearance");
        C2TraversabilityGraph oneWay=C2TraversabilityGraph.build(flat,(a,b)->a.x()<=b.x());
        ok(oneWay.edges().stream().noneMatch(e->e.from().x()!=e.to().x()),"asymmetric safety blocks both directions");

        checks++;
        try { graph.nodes().clear();throw new AssertionError("mutable nodes"); }
        catch (UnsupportedOperationException expected) { }
        checks++;
        try { graph.edges().clear();throw new AssertionError("mutable edges"); }
        catch (UnsupportedOperationException expected) { }
        checks++;
        try { graph.neighbors(node(0,0,0)).clear();throw new AssertionError("mutable adjacency"); }
        catch (UnsupportedOperationException expected) { }
        ok(C2TraversabilityGraph.build(flat,CLEAR).edges().equals(graph.edges()),"stable deterministic edge order");
        reject(()->C2TraversabilityGraph.build(flat,null),"cannot omit clearance probe");
        reject(()->C2TraversabilityGraph.build(null,CLEAR),"cannot omit terrain data");
        reject(()->C2TraversabilityGraph.build(
                new C2NavGrid.Snapshot(0,0,0,0,flat.cells()),CLEAR),"invalid radius");
        List<C2NavGrid.Cell> duplicated = new ArrayList<>(flat.cells());
        duplicated.add(flat.cells().get(0));
        reject(()->C2TraversabilityGraph.build(new C2NavGrid.Snapshot(0,0,0,1,duplicated),CLEAR),"duplicates rejected");
        reject(()->C2TraversabilityGraph.build(new C2NavGrid.Snapshot(0,0,0,1,List.of(
                new C2NavGrid.Cell(100,0,0,1,C2NavGrid.Condition.WALKABLE))),CLEAR),"out-of-bounds");
        List<C2NavGrid.Cell> forged = new ArrayList<>(flat.cells());
        forged.removeIf(c->c.x()==1 && c.z()==0);
        forged.add(new C2NavGrid.Cell(1,0,0,1,C2NavGrid.Condition.WALKABLE));
        forged.add(new C2NavGrid.Cell(1,1,0,2,C2NavGrid.Condition.UNLOADED));
        C2TraversabilityGraph failclosed=C2TraversabilityGraph.build(
                new C2NavGrid.Snapshot(0,0,0,1,forged),CLEAR);
        ok(!failclosed.contains(node(1,0,0)),"unloaded mark blacklists whole column");
        C2NavGrid.Snapshot maximum=grid(C2NavGrid.MAX_RADIUS,terrain(C2TraversabilityGraphSelfTest::floor));
        C2TraversabilityGraph max=C2TraversabilityGraph.build(maximum,CLEAR);
        ok(max.nodes().size()==169,"max radius yields bounded 169 walkable points on one floor");
        ok(max.edges().size()==624,"max radius yields bounded directed cardinal edges");
        long start=System.nanoTime();
        for (int i=0;i<100;i++) C2TraversabilityGraph.build(maximum,CLEAR);
        double elapsed=(System.nanoTime()-start)/1_000_000.0;
        ok(Double.isFinite(elapsed) && elapsed>=0,"bounded timing");
        System.out.printf(java.util.Locale.ROOT,
                "PASS C2 P1-B1 TraversabilityGraph: %d assertions; maxFlat=169 nodes/624 edges; pure Java 100 builds=%.2fms (NOT Forge MSPT)%n",
                checks,elapsed);
    }
}
