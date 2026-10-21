package io.github.sirp00m.npcortex;

import java.util.ArrayList;
import java.util.List;

/** Terrain support and typed sensor-contract tests, dependency-free Java 17. */
public final class C2TerrainGeometrySelfTest {
    private static int assertions;
    private static final double HALF = .31;
    private static void check(boolean condition,String name) {
        assertions++;
        if (!condition) throw new AssertionError(name);
    }
    private static void approx(double expected,double actual,String name) {
        check(Math.abs(expected-actual) < 1e-5,name+" expected="+expected+" got="+actual);
    }
    private static void throwsIAE(Runnable runnable,String name) {
        assertions++;
        try { runnable.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(name);
    }
    private static C2TerrainGeometry.Box box(double x0,double y0,double z0,double x1,double y1,double z1) {
        return new C2TerrainGeometry.Box(x0,y0,z0,x1,y1,z1);
    }
    private static C2TerrainGeometry.Assessment test(String name,List<C2TerrainGeometry.Box> boxes,
            C2TerrainGeometry.Support expected,double y,double fraction) {
        var result=C2TerrainGeometry.assess(boxes,HALF);
        check(result.support()==expected,name+" support");
        approx(y,result.localHeight(),name+" height");
        approx(fraction,result.coveredFraction(),name+" fraction");
        check(result.collisionBoxes()==boxes.size(),name+" count");
        return result;
    }
    public static void main(String[] args) {
        test("air",List.of(),C2TerrainGeometry.Support.MISSING,0,0);
        test("full",List.of(box(0,0,0,1,1,1)),C2TerrainGeometry.Support.FULL,1,1);
        test("bottom slab",List.of(box(0,0,0,1,.5,1)),C2TerrainGeometry.Support.FULL,.5,1);
        test("top slab",List.of(box(0,.5,0,1,1,1)),C2TerrainGeometry.Support.FULL,1,1);
        test("double slab",List.of(box(0,0,0,1,.5,1),box(0,.5,0,1,1,1)),
                C2TerrainGeometry.Support.FULL,1,1);
        test("two aligned half boxes",List.of(box(0,0,0,.5,1,1),box(.5,0,0,1,1,1)),
                C2TerrainGeometry.Support.FULL,1,1);
        test("four quadrants",List.of(box(0,0,0,.5,1,.5),box(.5,0,0,1,1,.5),
                box(0,0,.5,.5,1,1),box(.5,0,.5,1,1,1)),
                C2TerrainGeometry.Support.FULL,1,1);
        test("duplicated overlap",List.of(box(0,0,0,1,1,1),box(.1,0,.1,.9,1,.9)),
                C2TerrainGeometry.Support.FULL,1,1);
        test("half stair upper tread",List.of(box(0,0,0,1,.5,1),box(0,.5,0,.5,1,1)),
                C2TerrainGeometry.Support.PARTIAL,1,.5);
        test("missing half",List.of(box(0,0,0,.5,1,1)),C2TerrainGeometry.Support.PARTIAL,1,.5);
        test("thin fence",List.of(box(.4,0,.4,.6,1.5,.6)),
                C2TerrainGeometry.Support.PARTIAL,1.5,.04/(.62*.62));
        test("low carpet",List.of(box(0,0,0,1,.0625,1)),
                C2TerrainGeometry.Support.PARTIAL,.0625,1);
        test("narrow platform",List.of(box(.3,0,.3,.7,1,.7)),
                C2TerrainGeometry.Support.PARTIAL,1,.16/(.62*.62));
        double gap=.05;
        test("real gap",List.of(box(0,0,0,.5-gap/2,1,1),box(.5+gap/2,0,0,1,1,1)),
                C2TerrainGeometry.Support.PARTIAL,1,1-gap/.62);
        test("zero area",List.of(box(.5,0,.5,.5,1,.5)),
                C2TerrainGeometry.Support.PARTIAL,1,0);
        // Defensive copies: caller cannot rewrite shape observations after assessment.
        List<C2TerrainGeometry.Box> src=new ArrayList<>();
        src.add(box(0,0,0,1,1,1));
        var assessment=C2TerrainGeometry.assess(src,HALF);
        src.clear();
        check(assessment.support()==C2TerrainGeometry.Support.FULL,"immutable assessment");
        throwsIAE(()->C2TerrainGeometry.assess(List.of(),Double.NaN),"reject NaN width");
        throwsIAE(()->C2TerrainGeometry.assess(List.of(),.5),"reject 0.5 width");
        throwsIAE(()->C2TerrainGeometry.assess(List.of(),0),"reject zero width");
        throwsIAE(()->box(1,0,0,0,1,1),"reject inverted shape");
        throwsIAE(()->box(Double.NaN,0,0,1,1,1),"reject NaN shape");
        throwsIAE(()->new C2TerrainGeometry.Assessment(1,C2TerrainGeometry.Support.FULL,1.1,1),
                "reject impossible coverage");
        C2NavGrid.Cell walkable=new C2NavGrid.Cell(3,69,4,70,
                C2NavGrid.Condition.WALKABLE);
        C2TerrainObservation observed=new C2TerrainObservation(walkable,C2TerrainGeometry.Support.FULL,
                C2TerrainObservation.Clearance.CLEAR,C2TerrainObservation.Risk.NONE,
                C2TerrainObservation.Reason.STANDING_SURFACE,1,1);
        check(observed.cell().footY()==70,"evidence ties standing coordinate to footY");
        throwsIAE(()->new C2TerrainObservation(walkable,C2TerrainGeometry.Support.PARTIAL,
                C2TerrainObservation.Clearance.CLEAR,C2TerrainObservation.Risk.NONE,
                C2TerrainObservation.Reason.STANDING_SURFACE,.5,2),"reject partial promoted to walkable");
        throwsIAE(()->new C2TerrainObservation(walkable,C2TerrainGeometry.Support.FULL,
                C2TerrainObservation.Clearance.BLOCKED,C2TerrainObservation.Risk.NONE,
                C2TerrainObservation.Reason.STANDING_SURFACE,1,1),"reject blocked promoted to walkable");
        throwsIAE(()->new C2TerrainObservation(walkable,C2TerrainGeometry.Support.FULL,
                C2TerrainObservation.Clearance.CLEAR,C2TerrainObservation.Risk.HAZARD,
                C2TerrainObservation.Reason.STANDING_SURFACE,1,1),"reject hazard promoted to walkable");
        throwsIAE(()->new C2TerrainObservation(walkable,C2TerrainGeometry.Support.FULL,
                C2TerrainObservation.Clearance.CLEAR,C2TerrainObservation.Risk.UNKNOWN,
                C2TerrainObservation.Reason.STANDING_SURFACE,1,1),"reject unknown promoted to walkable");
        long begin=System.nanoTime();
        List<C2TerrainGeometry.Box> many=List.of(box(0,0,0,.5,1,1),box(.5,0,0,1,1,1));
        for(int i=0;i<1000;i++) check(C2TerrainGeometry.assess(many,HALF).support()==C2TerrainGeometry.Support.FULL,
                "repeat union");
        double ms=(System.nanoTime()-begin)/1_000_000.0;
        check(Double.isFinite(ms) && ms>=0,"finite timing");
        System.out.printf(java.util.Locale.ROOT,
                "PASS Sprint 1 Terrain Geometry: %d assertions; 1000 union evaluations=%.2fms (pure Java, NOT Forge MSPT)%n",
                assertions,ms);
    }
}
