package io.github.sirp00m.npcortex;
/** No game or network needed. Run via scripts/test-test5-harness.ps1. */
public final class CPlusTest5DecisionSelfTest {
    private static void check(String backend,String c2,boolean active,boolean witness,boolean near,boolean cancelled,CPlusTest5Decision.Next expected) {
        var actual=CPlusTest5Decision.choose(backend,c2,active,witness,near,cancelled);
        if(actual!=expected)throw new AssertionError(backend+" "+c2+": "+actual+" != "+expected);
    }
    public static void main(String[] args) {
        var N=CPlusTest5Decision.Next.class;
        check("C2_ACCEPTED","NAVIGATING",true,false,true,false,CPlusTest5Decision.Next.CANCEL);
        check("C2_ACCEPTED","NAVIGATING",true,false,false,false,CPlusTest5Decision.Next.WAIT);
        check("CANCEL_PENDING","NAVIGATING",true,false,true,true,CPlusTest5Decision.Next.WAIT);
        check("CANCEL_PENDING","CANCELLED",false,false,false,true,CPlusTest5Decision.Next.RECONCILE);
        check("C2_ACCEPTED","CANCELLED",false,false,false,true,CPlusTest5Decision.Next.WAIT);
        check("CANCEL_PENDING","COMPLETED",false,true,false,true,CPlusTest5Decision.Next.FINALIZE);
        check("C2_ACCEPTED","COMPLETED",false,true,false,false,CPlusTest5Decision.Next.FINALIZE);
        check("C2_ACCEPTED","COMPLETED",false,false,false,true,CPlusTest5Decision.Next.HOLD);
        check("CANCEL_PENDING","INTERRUPTED",false,false,false,true,CPlusTest5Decision.Next.HOLD);
        check("HOLD","NAVIGATING",true,false,true,true,CPlusTest5Decision.Next.HOLD);
        check("RESERVED","NONE",false,false,false,false,CPlusTest5Decision.Next.WAIT);
        check("COMPLETED","COMPLETED",false,true,false,true,CPlusTest5Decision.Next.TERMINAL);
        check("CANCELLED","CANCELLED",false,false,false,true,CPlusTest5Decision.Next.TERMINAL);
        check("C2_ACCEPTED","NONE",false,false,false,false,CPlusTest5Decision.Next.HOLD);
        System.out.println("Test5 DecisionSelfTest PASS (14 cases)");
    }
}
