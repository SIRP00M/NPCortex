package io.github.sirp00m.npcortex;

import java.util.UUID;

/** Pure Java 17 P0 geometry/event bus contract tests. Forge/Ollama are NOT run here. */
public final class C2FoundationSelfTest {
    private static int checks;
    static void check(boolean ok,String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    static void reject(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException good) { checks++;return; }
        throw new AssertionError("Invalid movement must be rejected");
    }
    public static void main(String[] args) {
        double[][] targets={{0,0,3},{3,0,0},{-3,0,0},{0,0,-4},
                {3,0,4},{-3,0,-4},{2.3,0,-3.3}};
        for (double[] target:targets) {
            C2MoveGeometry.Plan p=C2MoveGeometry.plan(0,0,0,target[0],target[1],target[2]);
            double rad=Math.toRadians(p.yaw());
            check(Math.abs(-Math.sin(rad)*p.distance()-target[0])<1e-5,"forward x");
            check(Math.abs(Math.cos(rad)*p.distance()-target[2])<1e-5,"forward z");
        }
        reject(()->C2MoveGeometry.plan(0,0,0,9,0,0));
        reject(()->C2MoveGeometry.plan(0,0,0,0.09,0,0));
        C2MoveGeometry.Plan precision=C2MoveGeometry.plan(79.5,69,-65.98,79.9,69,-65.98);
        check(Math.abs(precision.distance()-0.4)<1e-6,"sub-half-block movement supported");
        check(Math.abs(precision.yaw()+90)<1e-4,"precision yaw points east");
        check(Math.abs(C2MoveGeometry.plan(0,0,0,0.10,0,0).distance()-0.1)<1e-8,"minimum precision segment");
        check(C2MoveGeometry.reached(0,0,0,0.19,0,0,0.20),"inside explicit tolerance");
        check(!C2MoveGeometry.reached(0,0,0,0.25,0,0,0.20),"outside strict tolerance");
        check(C2MoveGeometry.reached(0,0,0,0.3,0,0,C2MoveGeometry.DEFAULT_TOLERANCE),"default goal tolerance");
        reject(()->C2MoveGeometry.validatedTolerance(0.19));
        reject(()->C2MoveGeometry.validatedTolerance(0.76));
        reject(()->C2MoveGeometry.validatedTolerance(Double.NaN));
        reject(()->C2MoveGeometry.validatedTolerance(Double.POSITIVE_INFINITY));
        reject(()->C2MoveGeometry.plan(0,0,0,2,2,2));
        reject(()->C2MoveGeometry.plan(0,0,0,Double.NaN,0,0));
        check(C2MoveGeometry.near(0,0,0,0.5,0,0,1),"near success");
        check(!C2MoveGeometry.near(0,0,0,2,0,0,1),"far failure");
        C2Scheduler<String> scheduler=new C2Scheduler<>(2);
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), c=UUID.randomUUID();
        check(scheduler.claim(a,"one"),"first actor claimed");
        check(!scheduler.claim(a,"two"),"duplicate actor rejected");
        check(scheduler.claim(b,"other"),"parallel actor allowed");
        check(!scheduler.claim(c,"third"),"capacity enforced");
        check(!scheduler.release(a,"not-owner"),"ownership required");
        check(scheduler.release(a,"one"),"owner can release");
        check(scheduler.claim(c,"third"),"capacity freed");
        C2Events bus=new C2Events();
        java.util.List<C2Events.Event> collected=new java.util.ArrayList<>();
        bus.subscribe(collected::add);
        UUID actor=UUID.randomUUID();
        for (int i=0;i<250;i++) bus.publish(C2Events.Kind.REQUESTED,actor,null,null,"test"+i);
        check(bus.recent().size()==128,"bounded event history");
        check(collected.size()==250,"event subscribers see all transitions");
        check(bus.recent().get(0).detail().equals("test122"),"drops oldest events");
        check(bus.recent().get(127).detail().equals("test249"),"retains newest events");
        bus.clear();
        check(bus.recent().isEmpty(),"history clears on shutdown");
        System.out.println("PASS C2 Foundation: "+checks+" assertions (pure JVM)");
    }
}
