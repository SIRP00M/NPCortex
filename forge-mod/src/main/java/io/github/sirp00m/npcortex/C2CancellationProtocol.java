package io.github.sirp00m.npcortex;

/** Pure terminal reconciliation policy. HTTP protocol parsing and identity checks
 * are separate: a reply cannot release a journal without matching goal/actor IDs.
 */
final class C2CancellationProtocol {
    private C2CancellationProtocol() {}
    static boolean terminal(boolean attested, boolean noPendingAction, String backendStatus) {
        return attested && noPendingAction &&
                ("failed".equals(backendStatus) || "completed".equals(backendStatus));
    }
    static String journalState(boolean cancelled, String backendStatus) {
        if (cancelled && "failed".equals(backendStatus)) return "CANCELLED";
        if ("completed".equals(backendStatus)) return "COMPLETED";
        return "FAILED";
    }
}
