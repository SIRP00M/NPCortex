package io.github.sirp00m.npcortex;

import java.util.UUID;

/** Pure-Java acceptance for C2-F1 hysteresis, bounds, target threshold,
 * transaction ticket ownership, and non-infinite replanning.
 * This does NOT prove the Forge gameplay or MSPT quality gate.
 */
public final class C2F1FollowPolicySelfTest {
    private static int assertions;
    private static void ok(boolean yes,String reason) {
        assertions++;
        if(!yes) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        C2FollowPolicy policy=new C2FollowPolicy();
        ok(policy.distance(3.0)==C2FollowPolicy.DistanceGate.KEEP,"initial follow band holds");
        ok(policy.distance(4.0)==C2FollowPolicy.DistanceGate.FOLLOW,"start at resume distance");
        ok(policy.following(),"state follows");
        ok(policy.distance(3.0)==C2FollowPolicy.DistanceGate.FOLLOW,"hysteresis follows in middle");
        ok(policy.distance(2.5)==C2FollowPolicy.DistanceGate.HOLD,"stop at close threshold");
        ok(!policy.following(),"hold stops following");
        ok(policy.distance(3.5)==C2FollowPolicy.DistanceGate.KEEP,"don't oscillate");
        ok(policy.distance(6.0)==C2FollowPolicy.DistanceGate.FOLLOW,"resume");
        policy.holdUntilResumeDistance();
        ok(policy.distance(3.0)==C2FollowPolicy.DistanceGate.KEEP,"verified ring holds without planner loop");
        ok(policy.distance(4.1)==C2FollowPolicy.DistanceGate.FOLLOW,"verified ring resumes after target leaves");
        for(double d:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,-1}) {
            try {policy.distance(d);throw new AssertionError("distance accepted: "+d);}
            catch(IllegalArgumentException expected) { assertions++; }
        }
        ok(policy.ready(0),"initial immediate plan");
        policy.planned(10,1,2);
        ok(policy.generation()==1,"route version");
        ok(!policy.ready(29),"cooldown holds");
        ok(policy.ready(30),"bounded cooldown");
        ok(!policy.targetMoved(1.5,2.1),"no target jitter route churn");
        ok(policy.targetMoved(2.5,2),"target moved threshold");
        policy.markTarget(4,5);
        ok(!policy.targetMoved(4,5),"mark attempted target even after failure");
        ok(policy.failed(31),"failure retry #1");
        policy.planned(40,4,5);
        ok(policy.failures()==1,"successful planning does not hide failed physical traversal");
        ok(policy.failed(52),"failure retry #2");
        ok(!policy.failed(73),"failure #3 budget stop");
        ok(policy.failures()==3,"bounded failure count");
        policy.resetFailureWindow();
        ok(policy.failures()==0,"moved target unlocks fresh budget");
        UUID expected=UUID.randomUUID(), foreign=UUID.randomUUID();
        var c2=C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND;
        for(boolean active:new boolean[]{false,true}) for(boolean same:new boolean[]{false,true}) {
            for(var origin:C2S4RecoveryPolicy.MotionOrigin.values()) {
                UUID token=same?expected:foreign;
                boolean owning=active && same && origin==c2;
                ok(C2FollowPolicy.ownedTicket(expected,token,active,origin)==owning,
                        "owner condition "+origin+" active="+active+" same="+same);
                ok(C2FollowPolicy.foreignActive(expected,token,active,origin)==(active&&!owning),
                        "foreign fencing condition "+origin);
            }
        }
        ok(!C2FollowPolicy.ownedTicket(null,null,true,c2),"no null token ownership");
        ok(C2FollowPolicy.foreignActive(null,null,true,c2),"unknown live ticket foreign");
        // A single JVM can host 10 independent actor follow policies; no static
        // failure counters, cooldown or target pose may leak between them.
        C2FollowPolicy[] policies=new C2FollowPolicy[10];
        for(int i=0;i<10;i++) {
            policies[i]=new C2FollowPolicy();
            policies[i].planned(i*20,i*1.25,i*2.5);
            ok(policies[i].generation()==1,"independent generation NPC="+i);
        }
        for(int i=0;i<10;i++) for(int j=0;j<10;j++) {
            boolean moved=policies[i].targetMoved(j*1.25,j*2.5);
            ok(moved==(i!=j),"independent target memory "+i+"/"+j);
        }
        ok(C2FollowPolicy.STOP_DISTANCE<C2FollowPolicy.RESUME_DISTANCE,"valid hysteresis");
        ok(C2FollowPolicy.MAX_CANDIDATE_PLANS*C2FollowPolicy.PER_PLAN_BUDGET<=12000,
                "bounded search cost per NPC plan batch");
        System.out.println("PASS C2-F1 follow policy: "+assertions+" assertions");
    }
}
