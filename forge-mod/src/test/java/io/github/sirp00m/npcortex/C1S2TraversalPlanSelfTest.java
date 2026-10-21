package io.github.sirp00m.npcortex;

/** Pure Java proof of Sprint 2 action verification rules; not proof of Forge physics. */
public final class C1S2TraversalPlanSelfTest {
    private static int assertions;
    private static void check(boolean ok, String name) {
        assertions++;
        if (!ok) throw new AssertionError(name);
    }
    private static void throwsInvalid(Runnable run) {
        boolean caught = false;
        try { run.run(); } catch (IllegalArgumentException exception) { caught = true; }
        check(caught,"invalid parameter must fail closed");
    }
    private static C1S2TraversalPlan make(C1S2TraversalPlan.Kind kind) {
        return new C1S2TraversalPlan(kind,0.5,69.0,0.5,1.5,0.5,1.0);
    }
    public static void main(String[] args) {
        for (C1S2TraversalPlan.Kind kind : C1S2TraversalPlan.Kind.values()) {
            check(C1S2TraversalPlan.parse(kind.name().toLowerCase())==kind,"parse "+kind);
            C1S2TraversalPlan p=make(kind);
            check(Math.abs(p.targetY-69-kind.height)<1.0e-8,"targetY");
            check(p.verify(1.5,p.targetY,0.5,true,"COMPLETE").startsWith("TRAVERSAL_") ,"terminology");
            check(p.verify(1.5,p.targetY,0.5,false,"COMPLETE").equals("TRAVERSAL_NOT_GROUNDED"),"not grounded");
            check(p.verify(1.5,p.targetY,0.5,true,"BLOCKED").equals("TRAVERSAL_BLOCKED"),"blocked");
            check(p.verify(1.5,p.targetY,0.5,true,"CANCELLED").equals("TRAVERSAL_CANCELLED"),"cancel");
            check(p.verify(1.5,p.targetY+0.4,0.5,true,"COMPLETE").startsWith("TRAVERSAL_") ,"height reject");
            check(p.verify(1.1,p.targetY,0.5,true,"COMPLETE").equals(kind.jump?"TRAVERSAL_JUMP_NOT_OBSERVED":
                    kind==C1S2TraversalPlan.Kind.SAFE_DESCEND?"TRAVERSAL_DESCENT_NOT_OBSERVED":"TRAVERSAL_HORIZONTAL_MISMATCH"),"horizontal reject");
            p.onTick(1.4, p.targetY+0.3, 0.5, false,false);
            check(p.leftGround,"airborne evidence");
            check(p.verify(1.5,p.targetY,0.5,true,"COMPLETE").equals("TRAVERSAL_COMPLETE_"+kind.name()),"good landing");
            check(p.verify(1.5,p.targetY+0.4,0.5,true,"COMPLETE").equals("TRAVERSAL_LANDING_MISMATCH"),"wrong elevation");
            check(p.verify(1.5,p.targetY,0.1,true,"COMPLETE").equals("TRAVERSAL_HORIZONTAL_MISMATCH"),"wrong XZ");
            p.onTick(1.5,p.targetY,0.5,true,true);
            check(p.verify(1.5,p.targetY,0.5,true,"COMPLETE").equals("TRAVERSAL_INTERRUPTED_COMBAT"),"combat wins");
        }
        C1S2TraversalPlan flat=make(C1S2TraversalPlan.Kind.STEP_UP);
        check(flat.verify(1.5,flat.targetY,0.5,true,"COMPLETE").equals("TRAVERSAL_COMPLETE_STEP_UP"),"ground step allowed");
        check(make(C1S2TraversalPlan.Kind.JUMP_UP).verify(1.5,70.0,0.5,true,"COMPLETE")
                .equals("TRAVERSAL_JUMP_NOT_OBSERVED"),"jump evidence mandatory");
        check(make(C1S2TraversalPlan.Kind.SAFE_DESCEND).verify(1.5,68.0,0.5,true,"COMPLETE")
                .equals("TRAVERSAL_DESCENT_NOT_OBSERVED"),"descent evidence mandatory");
        throwsInvalid(() -> C1S2TraversalPlan.parse("fly"));
        throwsInvalid(() -> C1S2TraversalPlan.parse(null));
        for (double d : new double[] {Double.NaN,Double.POSITIVE_INFINITY,0.1,10.0})
            throwsInvalid(() -> new C1S2TraversalPlan(C1S2TraversalPlan.Kind.JUMP_UP,0,69,0,d,0,d));
        throwsInvalid(() -> new C1S2TraversalPlan(null,0,69,0,1,0,1));
        throwsInvalid(() -> new C1S2TraversalPlan(C1S2TraversalPlan.Kind.JUMP_UP,0,69,0,1,0,0.9));
        throwsInvalid(() -> new C1S2TraversalPlan(C1S2TraversalPlan.Kind.JUMP_UP,0,Double.NaN,0,1,0,1));
        System.out.println("PASS S2 Verified Vanilla 3D Traversal Contract: " + assertions + " assertions");
    }
}
