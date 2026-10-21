package io.github.sirp00m.npcortex;

final class C2CancellationProtocolSelfTest {
    static int n;
    static void check(boolean test) { n++; if (!test) throw new AssertionError("cancellation policy #"+n); }
    public static void main(String[] args) {
        check(C2CancellationProtocol.terminal(true,true,"failed"));
        check(C2CancellationProtocol.terminal(true,true,"completed"));
        check(!C2CancellationProtocol.terminal(false,true,"failed"));
        check(!C2CancellationProtocol.terminal(true,false,"failed"));
        check(!C2CancellationProtocol.terminal(true,true,"active"));
        check(!C2CancellationProtocol.terminal(true,true,"pending"));
        check(!C2CancellationProtocol.terminal(true,true,"blocked"));
        check("CANCELLED".equals(C2CancellationProtocol.journalState(true,"failed")));
        check("FAILED".equals(C2CancellationProtocol.journalState(false,"failed")));
        check("COMPLETED".equals(C2CancellationProtocol.journalState(false,"completed")));
        check("COMPLETED".equals(C2CancellationProtocol.journalState(true,"completed")));
        System.out.println("PASS C2 P1-C Fix #1 CancellationPolicy: "+n+" assertions");
    }
}
