package io.github.sirp00m.npcortex;

import java.util.List;

/** Vanilla-inspired Adapter A: deterministic authority and input tests.
 * No assertion here claims visual parity or true Forge/Client validation. */
public final class C2F2VanillaInspiredFollowASelfTest {
    private static int checks;
    private static void ok(boolean result,String why) {
        checks++; if(!result)throw new AssertionError(why);
    }
    private static C1F2ContinuousWalk.Point point(double x,double z) {
        return new C1F2ContinuousWalk.Point(x,69,z);
    }
    public static void main(String[] args) {
        C1VanillaMoveControlAdapter control=new C1VanillaMoveControlAdapter();
        var first=control.tick(0,-20,3,false);
        ok(Math.abs(C2F2Locomotion.angularDelta(0,first.yaw()))<=18.01,"bounded minor turn");
        ok(first.forward()>0 && first.forward()<=.24001,"speed input accelerates gradually");
        var second=control.tick(first.yaw(),-20,3,false);
        ok(second.forward()>first.forward(),"accelerates across ticks");
        ok(second.forward()<=.48001,"bounded acceleration");
        var sharp=new C1VanillaMoveControlAdapter();
        var reverse=sharp.tick(90,-90,2,false);
        ok(reverse.forward()==0,"target behind = immediate zero forward input");
        ok(Math.abs(C2F2Locomotion.angularDelta(90,reverse.yaw()))<=32.01,"sharp turn bounded");
        float turnYaw=reverse.yaw();
        boolean resumed=false;
        for(int i=0;i<10;i++) {
            var drive=sharp.tick(turnYaw,-90,3,false);
            turnYaw=drive.yaw();
            if(drive.forward()>0) { resumed=true;break; }
        }
        ok(resumed,"sharp heading eventually resumes without new ticket");
        var decel=control.tick(second.yaw(),110,3,false);
        ok(decel.forward()>=0 && decel.forward()<second.forward(),"hard turn brakes");
        var last=new C1VanillaMoveControlAdapter();
        for(int i=0;i<5;i++) last.tick(-90,-90,3,false);
        ok(last.tick(-90,-90,.25,true).forward()<.7,"terminal approach slows forward input");
        var independent=new C1VanillaMoveControlAdapter();
        ok(independent.tick(-90,-90,3,false).forward()<=.24,"no shared NPC steering state");
        try {control.tick(Float.NaN,0,2,false);throw new AssertionError("nan accepted");}
        catch(IllegalArgumentException expected) { checks++; }
        try {control.tick(0,0,-2,false);throw new AssertionError("negative accepted");}
        catch(IllegalArgumentException expected) { checks++; }
        var walk=new C1F2ContinuousWalk(.5,69,.5,List.of(point(1.5,.5),point(2.5,.5)));
        var intent=walk.next(.5,69,.5,-70);
        ok(intent!=null && intent.forward()>0,"C1 real intent uses adapter");
        ok(walk.next(1.5,69,.5,intent.yaw())!=null && walk.consumed()==1,
                "intermediate waypoint stays same owner");
        walk.cancel();
        ok(walk.next(2.5,69,.5,-90)==null,"cancel never restarts adapter");
        var policy=new C2FollowPolicy();
        policy.planned(100,3,4);
        ok(!policy.ready(103),"base cooldown protects initial plans");
        policy.afterVerifiedRoute(105);
        ok(!policy.ready(106) && policy.ready(107),"verified progress fast follow replan with floor");
        var fails=new C2FollowPolicy();
        fails.failed(100);
        ok(!fails.ready(103),"failure backoff unchanged");
        for(int i=0;i<10;i++) {
            var npc=new C1VanillaMoveControlAdapter();
            ok(npc.tick(0,0,2,false).forward()==first.forward(),"10 NPC input isolation "+i);
        }
        System.out.println("PASS Vanilla-inspired Follow Adapter A: "+checks+" assertions (pure JVM)");
    }
}
