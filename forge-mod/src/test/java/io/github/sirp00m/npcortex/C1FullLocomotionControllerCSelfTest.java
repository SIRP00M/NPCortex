package io.github.sirp00m.npcortex;

/** C-L1..L4 dependency-free safety/pose regression. Physics/Forge must be tested separately. */
public final class C1FullLocomotionControllerCSelfTest {
    private static int assertions;
    private static void ok(boolean truth, String label) {
        assertions++;
        if (!truth) throw new AssertionError(label);
    }
    private static void near(float actual,float target,float epsilon,String label) {
        ok(Math.abs(C1LocomotionPresentation.difference(actual,target))<=epsilon,label);
    }
    public static void main(String[] args) {
        var easing = new C1VanillaMoveControlAdapter();
        var sharpDrive = easing.tick(90,-90,3,false);
        ok(Math.abs(C1LocomotionPresentation.difference(90,sharpDrive.yaw()))<=24.01,
                "large-turn yaw no longer snaps 32 degrees per tick");
        var fineDrive = new C1VanillaMoveControlAdapter().tick(0,4,3,false);
        ok(Math.abs(C1LocomotionPresentation.difference(0,fineDrive.yaw()))<=4.001,
                "small heading error does not overshoot");
        C1LocomotionPresentation p = new C1LocomotionPresentation(0,0);
        ok(p.phase()==C1LocomotionPresentation.Phase.IDLE,"starts idle");
        var idle=p.tick(0,0,0,false,false,false);
        ok(idle.phase()==C1LocomotionPresentation.Phase.IDLE,"stays idle");
        near(idle.bodyYaw(),0,.01f,"idle body stable");
        var start=p.tick(32,120,.08,true,false,false);
        ok(start.phase()==C1LocomotionPresentation.Phase.STARTING,"starting state");
        near(start.bodyYaw(),11,.01f,"body step bounded");
        ok(Math.abs(C1LocomotionPresentation.difference(start.bodyYaw(),start.headYaw()))<=55.01,
                "head/body twist clamped");
        for(int i=0;i<5;i++) {
            var pose=p.tick(32,120,.12,true,false,false);
            ok(Math.abs(C1LocomotionPresentation.difference(pose.bodyYaw(),pose.headYaw()))<=55.01,
                    "ongoing twist cap "+i);
        }
        ok(p.phase()==C1LocomotionPresentation.Phase.WALKING,"walking state");
        var corner=p.tick(48,100,.09,true,true,false);
        ok(corner.phase()==C1LocomotionPresentation.Phase.CORNERING,"corner state");
        var interrupted=p.tick(48,100,.21,true,true,true);
        ok(interrupted.phase()==C1LocomotionPresentation.Phase.INTERRUPTED,"combat priority interruption");
        near(interrupted.bodyYaw(),corner.bodyYaw(),.01f,"combat does not rotate body");
        var stopping=p.tick(48,48,0,false,false,false);
        ok(stopping.phase()==C1LocomotionPresentation.Phase.STOPPING,"interrupted enters stop presentation");
        for(int i=0;i<12;i++) p.tick(48,48,0,false,false,false);
        ok(p.settled(48),"returns stable idle after stop");
        var resume=p.tick(48,48,.10,true,false,false);
        ok(resume.phase()==C1LocomotionPresentation.Phase.STARTING,"restart after stop");
        var reverse=new C1LocomotionPresentation(170,170);
        float last=170;
        for(int i=0;i<9;i++) {
            var pose=reverse.tick(-170,-170,.12,true,false,false);
            ok(Math.abs(C1LocomotionPresentation.difference(last,pose.bodyYaw()))<=11.001,
                    "cross 180 shortest arc body "+i);
            last=pose.bodyYaw();
        }
        near(last,-170,.8f,"cross 180 completes without full spin");
        float a=C1LocomotionPresentation.approach(-179,179,11);
        near(a,179,.01f,"wrap shortest arc");
        C1ClientRotationBlend blend=new C1ClientRotationBlend();
        ok(!blend.ready(),"viewer waits for verified snapshot");
        blend.accept(170,170);
        var frame=blend.advance();
        near(frame.head(),170,.001f,"initial snapshot restored head");
        near(frame.body(),170,.001f,"initial snapshot restored body");
        blend.accept(-120,-130);
        var next=blend.advance();
        ok(Math.abs(C1LocomotionPresentation.difference(frame.head(),next.head()))<=16.001,
                "network head smoothing bounded");
        ok(Math.abs(C1LocomotionPresentation.difference(frame.body(),next.body()))<=13.001,
                "network body smoothing bounded");
        for(int i=0;i<25;i++) blend.advance();
        var settled=blend.advance();
        near(settled.head(),-120,.01f,"network head target converges");
        near(settled.body(),-130,.01f,"network body target converges");
        C1ClientRotationBlend independent=new C1ClientRotationBlend();
        independent.accept(42,-42);
        near(independent.advance().head(),42,.001f,"per-NPC independent head");
        near(blend.advance().head(),-120,.01f,"first NPC remains independent");
        for(int n=0;n<10;n++) {
            C1LocomotionPresentation another=new C1LocomotionPresentation(n*10,n*10);
            var pose=another.tick(n*10,n*10,0,false,false,false);
            near(pose.bodyYaw(),n*10,.001f,"10 NPC pose isolation "+n);
        }
        try{p.tick(Float.NaN,0,0,false,false,false);throw new AssertionError("NaN yaw accepted");}
        catch(IllegalArgumentException expected){assertions++;}
        try{blend.accept(Float.NaN,0);throw new AssertionError("NaN packet accepted");}
        catch(IllegalArgumentException expected){assertions++;}
        System.out.println("PASS C-L1/L2/L3/L4 Full Locomotion Controller C: "
                + assertions + " assertions (pure JVM)");
    }
}
