package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** S1-S4 pure-Java guards. World/collision/Forge gameplay remain separate gates. */
public final class C2SmoothFollowHardeningCSelfTest {
    private static int assertions;
    private static void ok(boolean condition,String why) {
        assertions++;
        if(!condition) throw new AssertionError(why);
    }
    private static C2TraversabilityGraph.Node node(int x,int z) {
        return new C2TraversabilityGraph.Node(x,68,z,69);
    }
    public static void main(String[] args) {
        var anchor=node(2,2);
        var forward=node(3,2);
        var hint=new C2FollowPreparedContinuation();
        UUID token=UUID.randomUUID();
        UUID foreign=UUID.randomUUID();
        ok(!hint.pending(),"no speculative route by default");
        ok(!hint.offer(token,anchor,List.of(forward),30,10,10,"WALK"),"reject missing root");
        ok(!hint.offer(token,anchor,List.of(anchor),30,10,10,"WALK"),"reject zero progress");
        ok(hint.offer(token,anchor,List.of(anchor,forward),30,10,10,"WALK"),"store hint");
        ok(hint.pending(),"hint present");
        ok(hint.consume(foreign,anchor,31,10,10)==null,"foreign motion token rejected");
        ok(!hint.pending(),"failed consume discards stale hint");
        ok(hint.offer(token,anchor,List.of(anchor,forward),30,10,10,"WALK"),"reoffer");
        ok(hint.consume(token,node(3,3),31,10,10)==null,"wrong physical endpoint rejected");
        ok(hint.offer(token,anchor,List.of(anchor,forward),30,10,10,"WALK"),"reoffer for relocation");
        ok(hint.consume(token,anchor,31,12,10)==null,"moving target invalidates hint");
        ok(hint.offer(token,anchor,List.of(anchor,forward),30,10,10,"WALK"),"reoffer for expiry");
        ok(hint.consume(token,anchor,131,10,10)==null,"stale snapshot rejected");
        ok(hint.offer(token,anchor,List.of(anchor,forward),30,10,10,"WALK"),"reoffer valid");
        var accepted=hint.consume(token,anchor,31,10.25,10);
        ok(accepted!=null && accepted.size()==2 && accepted.get(0).equals(anchor),"fresh receipt can access hint");
        ok(!hint.pending() && hint.consume(token,anchor,32,10,10)==null,"single-use, no replay");
        hint.offer(token,anchor,List.of(anchor,forward),40,10,10,"3D");
        hint.clear();
        ok(!hint.pending(),"cancel clears speculative plan");

        var metrics=new C2FollowSmoothMetrics();
        metrics.sample("MOVING");metrics.sample("MOVING");
        metrics.sample("TURNING");metrics.sample("COOLDOWN");
        metrics.sample("WAIT_GROUND");metrics.sample("MOVING");
        metrics.sample("HOLD");metrics.sample("PAUSED_UNREACHABLE");
        metrics.sample("PAUSED_UNREACHABLE");metrics.sample("HOLD");
        metrics.terminalHandoff();metrics.preparedHit();metrics.preparedMiss();
        metrics.preplanAttempt(true);metrics.preplanAttempt(false);
        String status=metrics.summary();
        ok(status.contains("smoothMotionTicks=3"),"track moving time");
        ok(status.contains("smoothWaitTicks=5"),"track avoidable wait time");
        ok(status.contains("smoothHoldTicks=2"),"hold separate from stuck time");
        ok(status.contains("maxWaitTicks=3"),"longest repeated wait window");
        ok(status.contains("turnWait=1") && status.contains("cooldownWait=1"),"separate turn and cooldown ticks");
        ok(status.contains("groundWait=1") && status.contains("recoveryWait=2"),"separate ground from recovery wait");
        ok(status.contains("ticketHandoffs=1"),"ticket termination tracked");
        ok(status.contains("preparedHits=1") && status.contains("preparedMisses=1"),"cache acceptance telemetry");
        ok(status.contains("preplanProbes=2") && status.contains("preplanFound=1"),"bounded planning probe telemetry");
        var stability=new C2FollowDetourStability();
        ok(stability.shouldReset(0,0),"no detour chosen yet");
        stability.chosen(0,0);
        ok(!stability.shouldReset(1.6,0),"small retarget preserves side");
        ok(!stability.shouldReset(5.9,0),"intermediate retarget preserves side");
        ok(stability.shouldReset(6.1,0),"large relocation releases old detour preference");
        stability.clear();
        ok(stability.shouldReset(0,0),"reset releases detour choice");

        System.out.println("PASS C Smooth Follow Hardening: "+assertions+" assertions (pure JVM)");
    }
}
