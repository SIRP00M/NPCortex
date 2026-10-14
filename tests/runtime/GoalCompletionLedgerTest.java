package io.github.sirp00m.npcortex.agent;

/** Pure server ledger tests, no Minecraft runtime. */
public final class GoalCompletionLedgerTest {
    private static int checks;
    private static void check(boolean ok,String label) {
        checks++; if(!ok) throw new AssertionError(label);
    }
    private static GoalCompletionLedger.Phase phase(boolean active,int left,int trees,int mined,int bag,int borrowed,int goal,int temp) {
        return GoalCompletionLedger.phase(active,left,trees,mined,bag,borrowed,goal,temp);
    }
    public static void main(String[] args) {
        var p=GoalCompletionLedger.Phase.class;
        check(phase(false,0,0,0,0,0,6,0)==GoalCompletionLedger.Phase.FIND_TREE,"new task");
        check(phase(true,8,0,2,2,0,6,0)==GoalCompletionLedger.Phase.HARVEST,"remaining logs cannot finish");
        check(phase(true,0,0,20,2,12,6,12)==GoalCompletionLedger.Phase.RECLAIM_ACCESS,"recover borrowed logs");
        check(phase(true,0,0,20,19,0,6,0)==GoalCompletionLedger.Phase.RECOVER_DROPS,"19/20 not complete");
        check(GoalCompletionLedger.missing(20,19,0)==1,"missing exactly one, never invent");
        check(GoalCompletionLedger.missing(20,2,12)==6,"borrowed logs are not missing");
        check(phase(true,0,0,4,4,0,6,0)==GoalCompletionLedger.Phase.VERIFY_TREE,"small tree must verify before next");
        check(phase(false,0,1,4,4,0,6,0)==GoalCompletionLedger.Phase.FIND_TREE,"find next after verified small tree");
        check(phase(false,0,1,20,20,0,6,0)==GoalCompletionLedger.Phase.READY_TO_FINISH,"final verified tree");
        check(phase(false,0,1,20,19,0,6,0)==GoalCompletionLedger.Phase.RECOVER_DROPS,"completed tree but inventory deficit");
        check(phase(false,0,1,20,20,0,6,1)==GoalCompletionLedger.Phase.RECLAIM_ACCESS,"terrain changes prevent finish");
        check(!GoalCompletionLedger.recoveryExhausted(GoalCompletionLedger.Phase.HARVEST,100),"long tree harvest allowed");
        check(!GoalCompletionLedger.recoveryExhausted(GoalCompletionLedger.Phase.RECOVER_DROPS,17),"allow bounded retry");
        check(GoalCompletionLedger.recoveryExhausted(GoalCompletionLedger.Phase.RECOVER_DROPS,18),"bound missing-item cycle");
        System.out.println("Goal completion/recovery: "+checks+" assertions passed.");
    }
}
