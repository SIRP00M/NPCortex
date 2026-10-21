package io.github.sirp00m.npcortex;

import java.util.List;
import java.util.UUID;

/** Pure-Java S4 regression. Not Forge MSPT, real C1 physics, or crash proof. */
public final class C2S4RecoverySelfTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static C2NavGrid.Snapshot terrain(boolean obstruction) {
        return C2NavGrid.scan(0,0,0,6,new C2NavGrid.Probe() {
            public boolean loaded(int x,int z) { return true; }
            public C2NavGrid.Cell sample(int x,int y,int z) {
                // Synthetic 3D fixture: a climbable one-block high platform.
                int sy=(x >= 0 && z==0) ? 1 : 0;
                return new C2NavGrid.Cell(x,y,z,sy+1,
                        y==sy && !(obstruction && x==1 && z==0)
                            ? C2NavGrid.Condition.WALKABLE:C2NavGrid.Condition.OBSTRUCTED);
            }
        });
    }
    public static void main(String[] args) {
        var vertical=C2TraversabilityGraph.MoveKind.JUMP_UP;
        var flat=C2TraversabilityGraph.MoveKind.WALK;
        String[] recoverable={"TRAVERSAL_BLOCKED","TRAVERSAL_TIMEOUT",
                "TRAVERSAL_LANDING_MISMATCH","TRAVERSAL_HORIZONTAL_MISMATCH",
                "TRAVERSAL_LANDING_CHANGED_OR_UNSAFE","TRAVERSAL_JUMP_NOT_OBSERVED",
                "TRAVERSAL_DESCENT_NOT_OBSERVED","TRAVERSAL_INTERRUPTED_COMBAT",
                "TRAVERSAL_NOT_GROUNDED"};
        for (String failure:recoverable) {
            check(C2S4RecoveryPolicy.classify(vertical,true,false,failure,false)
                    == C2S4RecoveryPolicy.Verdict.RETRY_3D,"recoverable terminal: "+failure);
            check(C2S4RecoveryPolicy.classify(vertical,false,false,failure,false)
                    == C2S4RecoveryPolicy.Verdict.FOREIGN_TICKET,"foreign ticket fenced: "+failure);
            check(C2S4RecoveryPolicy.classify(vertical,true,true,failure,false)
                    == C2S4RecoveryPolicy.Verdict.WAIT_FOR_C1,"active C1 cannot replan: "+failure);
        }
        for(var kind: C2TraversabilityGraph.MoveKind.values()) {
            if(kind==flat) continue;
            check(C2S4RecoveryPolicy.classify(kind,true,false,
                    "TRAVERSAL_COMPLETE_"+kind.name(),false)==C2S4RecoveryPolicy.Verdict.RETRY_3D,
                    "missed center needs fresh path, never false success");
            check(C2S4RecoveryPolicy.classify(kind,true,false,
                    "TRAVERSAL_COMPLETE_"+kind.name(),true)==C2S4RecoveryPolicy.Verdict.FAIL_CLOSED,
                    "already-successful segment is handled by waypoint state machine");
        }
        for(String failure:List.of("CANCELLED","TRAVERSAL_INVALID_POSITION","CHUNK_UNLOADED",
                "UNKNOWN","TRAVERSAL_COMPLETE_STEP_UP")) {
            check(C2S4RecoveryPolicy.classify(vertical,true,false,failure,false)
                    ==C2S4RecoveryPolicy.Verdict.FAIL_CLOSED,"fatal/not-matching terminal: "+failure);
        }
        check(C2S4RecoveryPolicy.classify(flat,true,false,"BLOCKED",false)
                ==C2S4RecoveryPolicy.Verdict.RETRY_FLAT,"legacy flat recovery preserved");
        check(C2S4RecoveryPolicy.classify(flat,true,false,"COMPLETE",false)
                ==C2S4RecoveryPolicy.Verdict.RETRY_FLAT,"missed flat center preserved");
        check(C2S4RecoveryPolicy.classify(flat,true,false,"COMPLETE",true)
                ==C2S4RecoveryPolicy.Verdict.FAIL_CLOSED,"correct flat waypoint receipt");
        for(String detail:List.of("S3 C1 traversal refused: S2 LANDING_COLLISION; no movement",
                "S3 C1 traversal refused: S2 LANDING_HEIGHT_MISMATCH actual=70 requested=69.5",
                "S3 C1 traversal refused: S2 UNSAFE_LANDING OBSTRUCTED",
                "S3 C1 traversal refused: S2 JUMP_HEADROOM_BLOCKED; no movement")) {
            check(C2S4RecoveryPolicy.recoverableStartRefusal(detail),"TOCTOU preflight: "+detail);
        }
        for(String detail:List.of("S3 C1 traversal refused: S2 CHUNK_UNLOADED",
                "S3 C1 traversal refused: S2 NPC lifecycle unavailable",
                "S3 C1 traversal refused: S2 movement busy","P1-C foreign token","")) {
            check(!C2S4RecoveryPolicy.recoverableStartRefusal(detail),"unsafe preflight refused: "+detail);
        }
        // A clean recovery requires loaded, stable standing, and full C1 release.
        for(int stable=0;stable<=5;stable++) {
            for(int bits=0;bits<64;bits++) {
                boolean live=(bits&1)!=0,loaded=(bits&2)!=0,grounded=(bits&4)!=0;
                boolean active=(bits&8)!=0,combat=(bits&16)!=0,supported=(bits&32)!=0;
                boolean expect=live&&loaded&&grounded&&!active&&!combat&&supported&&stable>=3;
                check(C2S4RecoveryPolicy.safeToPlan(live,loaded,grounded,active,combat,supported,stable)==expect,
                        "stable / C1 ownership matrix");
            }
        }
        for(String state:List.of("MOVING","REPLANNING","RESULT_PENDING","DECIDED",
                "REQUESTED","HTTP_PENDING","CANCEL_PENDING","COMPLETED","FAILED","INTERRUPTED")) {
            boolean allowed=List.of("MOVING","REPLANNING","RESULT_PENDING","DECIDED").contains(state);
            check(C2S4RecoveryPolicy.restartCandidate(state,true,true)==allowed,"restart state: "+state);
            check(!C2S4RecoveryPolicy.restartCandidate(state,true,false),"action is required");
            check(!C2S4RecoveryPolicy.restartCandidate(state,false,true),"goal is required");
        }
        // Tickets and replanning budgets are per actor; a stale receipt must not
        // unlock any other actor or generate a new C0 Action.
        var nodes=List.of(new C2TraversabilityGraph.Node(0,0,0,1.0),
                new C2TraversabilityGraph.Node(1,0,0,2.0));
        for(int groupSize:List.of(1,3,5,10)) {
            var routes=new C2WaypointExecution[groupSize];
            var replans=new C2ReplanningPolicy[groupSize];
            var ids=new UUID[groupSize];
            for(int i=0;i<groupSize;i++) {
                routes[i]=new C2WaypointExecution(nodes);
                ids[i]=UUID.randomUUID();
                replans[i]=new C2ReplanningPolicy();
                routes[i].started(ids[i]);
            }
            for(int i=0;i<groupSize;i++) {
                check(routes[i].acceptsTicket(ids[i]),"actor ticket accepted");
                check(routes[i].observe(ids[i],false,"TRAVERSAL_LANDING_MISMATCH",false)
                        ==C2WaypointExecution.Update.FAILED,"terminal consumed");
                check(replans[i].request(C2ReplanningPolicy.Trigger.VERTICAL_TERMINAL,0),"replan gated");
                check(replans[i].gate(19)==C2ReplanningPolicy.Gate.WAIT,"cooldown");
                check(replans[i].gate(20)==C2ReplanningPolicy.Gate.READY,"eligible after cooldown");
                replans[i].evaluated(replans[i].budget(),1,"NO_PATH",false,20);
                check(replans[i].attempts()==1,"one counted attempt");
                for(int j=0;j<groupSize;j++) if(j!=i) {
                    check(!routes[j].acceptsTicket(ids[i]),"no cross-actor ticket");
                }
            }
        }
        System.out.printf("PASS S4 Recovery safety/state/correlation: %d assertions (pure Java; NOT Forge/MSPT)%n",checks);
    }
}
