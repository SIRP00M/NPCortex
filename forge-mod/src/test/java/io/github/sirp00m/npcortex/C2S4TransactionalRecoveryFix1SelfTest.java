package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** Source-independent policy contract for Sprint 4 Fix #1.
 * Gameplay and actual Forge movement remain separate Windows acceptance gates.
 */
public final class C2S4TransactionalRecoveryFix1SelfTest {
    private static int assertions;
    private static void ok(boolean condition,String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        var policy=C2S4RecoveryPolicy.class;
        var origins=C2S4RecoveryPolicy.MotionOrigin.values();
        for(var origin:origins) {
            boolean passive=origin==C2S4RecoveryPolicy.MotionOrigin.AUTO_SETTLING
                    || origin==C2S4RecoveryPolicy.MotionOrigin.AUTO_COMBAT;
            for(boolean active:List.of(false,true)) for(boolean combat:List.of(false,true))
                    for(boolean changed:List.of(false,true)) {
                var observed=C2S4RecoveryPolicy.replanOwnership(active,combat,origin,changed);
                var expected=active
                        ? (passive ? C2S4RecoveryPolicy.ReplanOwnership.WAIT_PASSIVE_PHYSICS
                                   : C2S4RecoveryPolicy.ReplanOwnership.FOREIGN_OWNER)
                        : changed && !passive
                                ? C2S4RecoveryPolicy.ReplanOwnership.FOREIGN_OWNER
                                : C2S4RecoveryPolicy.ReplanOwnership.RELEASED;
                ok(observed==expected,"origin="+origin+", active="+active+", combat="+combat+", changed="+changed);
            }
        }
        ok(C2S4RecoveryPolicy.replanOwnership(false,false,null,true)
                ==C2S4RecoveryPolicy.ReplanOwnership.FOREIGN_OWNER,"null origin fails closed");
        // Regression: C1 wakes up with auto-gravity while C2 is REPLANNING.
        var gravity=C2S4RecoveryPolicy.MotionOrigin.AUTO_SETTLING;
        ok(C2S4RecoveryPolicy.replanOwnership(true,false,gravity,true)
                ==C2S4RecoveryPolicy.ReplanOwnership.WAIT_PASSIVE_PHYSICS,"actual bug: auto settle waits");
        ok(C2S4RecoveryPolicy.replanOwnership(false,false,gravity,true)
                ==C2S4RecoveryPolicy.ReplanOwnership.RELEASED,"auto settle ended: permit loaded/grounded checks");
        ok(!C2S4RecoveryPolicy.safeToPlan(true,true,true,true,false,true,3),
                "auto settle ACTIVE must not start a new path");
        ok(C2S4RecoveryPolicy.safeToPlan(true,true,true,false,false,true,3),
                "auto settle TERMINAL + 3 ticks safe");
        // A manually requested settle/jump is NOT auto-settling.
        ok(C2S4RecoveryPolicy.replanOwnership(true,false,
                C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND,true)
                ==C2S4RecoveryPolicy.ReplanOwnership.FOREIGN_OWNER,"operator jump is foreign");
        ok(C2S4RecoveryPolicy.replanOwnership(false,false,
                C2S4RecoveryPolicy.MotionOrigin.OPERATOR_COMMAND,true)
                ==C2S4RecoveryPolicy.ReplanOwnership.FOREIGN_OWNER,"completed foreign input still fenced");
        // Existing on-disk legacy INTERRUPTED is migrated only for three known
        // replan failure messages with BOTH goal and action identities.
        var signatures=List.of("P1-D C1 motion resumed during replanning; fail closed",
                "P1-D movement owner changed during replanning",
                "P1-D foreign/stale motion ticket; operator reconciliation required");
        for(String msg:signatures) for(String state:List.of("INTERRUPTED","REPLANNING","COMPLETED","FAILED"))
                for(boolean goal:List.of(true,false)) for(boolean action:List.of(true,false)) {
            boolean expected=state.equals("INTERRUPTED") && goal && action;
            ok(C2S4RecoveryPolicy.legacyReplanInterrupted(state,msg,goal,action)==expected,
                    "legacy journal scope: "+state+" / "+msg);
        }
        for(String msg:List.of("HTTP: timeout","Server shutdown","Unknown failure",
                "P1-D C1 motion resumed", "Operator cancelled; C0 goal may have a pending action", "")) {
            ok(!C2S4RecoveryPolicy.legacyReplanInterrupted("INTERRUPTED",msg,true,true),
                    "unknown legacy interrupt must NOT be reset: "+msg);
        }
        ok(!C2S4RecoveryPolicy.legacyReplanInterrupted("INTERRUPTED",null,true,true),"null message");
        // Exhausted budgets / unsafe positions may never bypass the existing S4 gate.
        for(int stable=0;stable<3;stable++)
            ok(!C2S4RecoveryPolicy.safeToPlan(true,true,true,false,false,true,stable),"stable-ground requirement");
        for(int n:List.of(1,3,5,10)) {
            var ids=new UUID[n];
            var policyState=new C2ReplanningPolicy[n];
            for(int i=0;i<n;i++) {
                ids[i]=UUID.randomUUID();
                policyState[i]=new C2ReplanningPolicy();
                ok(policyState[i].request(C2ReplanningPolicy.Trigger.EDGE_INVALIDATED,0),"replan authorized");
                ok(policyState[i].gate(0)==C2ReplanningPolicy.Gate.WAIT,"no immediate replan");
            }
            for(int i=0;i<n;i++) for(int j=0;j<n;j++) if(i!=j)
                ok(!ids[i].equals(ids[j]),"distinct actors cannot share keys");
        }
        System.out.println("PASS Sprint4 Fix1 Ownership/Recovery: "+assertions+" assertions (pure Java)");
    }
}
