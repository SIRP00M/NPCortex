package io.github.sirp00m.npcortex;

/** Pure-JVM P1-D contract checks. Does not certify Minecraft gameplay or MSPT. */
public final class C2ReplanningPolicySelfTest {
    private static int checks;
    private static void ok(boolean pass,String message) {
        checks++; if (!pass) throw new AssertionError(message);
    }
    private static void rejects(Runnable run) {
        checks++; try {run.run();}catch(IllegalStateException expected){return;}
        throw new AssertionError("Expected fail-closed rejection");
    }
    public static void main(String[] args) {
        for(C2ReplanningPolicy.Trigger cause:C2ReplanningPolicy.Trigger.values()) {
            var policy=new C2ReplanningPolicy();
            ok(policy.generation()==0 && policy.attempts()==0,"new state");
            ok(policy.gate(0)==C2ReplanningPolicy.Gate.NOT_PENDING,"not pending");
            rejects(policy::budget);
            ok(policy.request(cause,10),"schedule replan");
            ok(!policy.request(cause,10),"do not schedule duplicate");
            ok(policy.gate(29)==C2ReplanningPolicy.Gate.WAIT,"20-tick delay");
            ok(policy.gate(30)==C2ReplanningPolicy.Gate.READY,"ready at deadline");
            ok(policy.budget()==12000,"bounded per replan");
            rejects(()->policy.evaluated(12000,0,"PATH_FOUND",true,29));
            rejects(()->policy.evaluated(12000,12001,"PATH_FOUND",true,30));
            ok(policy.generation()==0,"no stale generation");
            policy.evaluated(12000,32,"PATH_FOUND",true,30);
            ok(policy.generation()==1 && policy.attempts()==1 && policy.expanded()==32,"commit route generation");
            ok(policy.gate(31)==C2ReplanningPolicy.Gate.NOT_PENDING,"route is installed once");
            ok(policy.request(cause,31),"second route after success");
            ok(policy.gate(50)==C2ReplanningPolicy.Gate.WAIT,"cooldown per trigger");
            policy.evaluated(12000,999,"NO_PATH",false,51);
            ok(policy.retryable(),"NO_PATH may retry for world update");
            ok(policy.gate(70)==C2ReplanningPolicy.Gate.WAIT,"retry waits");
            ok(policy.gate(71)==C2ReplanningPolicy.Gate.READY,"retry ready");
            policy.evaluated(12000,1,"NO_PATH",false,71);
            ok(policy.attempts()==3 && policy.generation()==1 && !policy.retryable(),"bounded total attempts");
            ok(policy.gate(100)==C2ReplanningPolicy.Gate.EXHAUSTED,"cannot search forever");
            ok(!policy.request(cause,101),"cannot regenerate budget");
            policy.stop();
            ok(policy.gate(101)==C2ReplanningPolicy.Gate.NOT_PENDING,"stop is terminal");
        }
        var p = new C2ReplanningPolicy();
        ok(p.request(C2ReplanningPolicy.Trigger.BLOCKED,0),"request");
        p.evaluated(12000,12000,"BUDGET_EXCEEDED",false,20);
        ok(p.expanded()==12000 && p.attempts()==1,"full budget accounted");
        ok(p.gate(40)==C2ReplanningPolicy.Gate.READY,"retry after budget failure");
        p.evaluated(12000,12000,"BUDGET_EXCEEDED",false,40);
        p.evaluated(12000,12000,"BUDGET_EXCEEDED",false,60);
        ok(p.expanded()==36000 && !p.retryable(),"global search bound");
        var cancelled = new C2ReplanningPolicy();
        ok(cancelled.request(C2ReplanningPolicy.Trigger.EDGE_INVALIDATED,0),"pending before cancellation");
        cancelled.stop();
        ok(cancelled.gate(100)==C2ReplanningPolicy.Gate.NOT_PENDING,"cancel fences pending work");
        rejects(()->cancelled.evaluated(12000,0,"PATH_FOUND",true,100));
        ok(cancelled.generation()==0,"cancel cannot publish stale route");
        // Ten independent actors never share budgets or route generation.
        var independent=new C2ReplanningPolicy[10];
        for(int i=0;i<10;i++) {
            independent[i]=new C2ReplanningPolicy();
            ok(independent[i].request(C2ReplanningPolicy.Trigger.BLOCKED,0),"actor "+i);
        }
        independent[0].evaluated(12000,500,"PATH_FOUND",true,20);
        for(int i=1;i<10;i++)
            ok(independent[i].attempts()==0 && independent[i].generation()==0
                    && independent[i].pending(),"no cross-actor mutation");
        ok(independent[0].generation()==1 && independent[0].expanded()==500,"only owner commits");
        System.out.println("PASS C2-P1-D ReplanningPolicy: "+checks+" assertions (pure JVM)");
    }
}
