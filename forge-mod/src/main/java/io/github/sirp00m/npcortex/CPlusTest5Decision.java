package io.github.sirp00m.npcortex;

/** Pure decision function for the temporary Transport E race-test harness.
 * HOLD is intentional on ambiguous observations. This is NOT a C2/C0 policy.
 */
final class CPlusTest5Decision {
    enum Next { WAIT, CANCEL, FINALIZE, RECONCILE, TERMINAL, HOLD }
    private CPlusTest5Decision() {}

    static Next choose(String backend, String c2, boolean activeExactGoal,
                       boolean terminalWitness, boolean near, boolean cancelIssued) {
        if ("HOLD".equals(backend) || "RESERVED".equals(backend))
            return "HOLD".equals(backend) ? Next.HOLD : Next.WAIT;
        if ("COMPLETED".equals(backend) || "CANCELLED".equals(backend))
            return Next.TERMINAL;
        if (!"C2_ACCEPTED".equals(backend) && !"CANCEL_PENDING".equals(backend))
            return Next.HOLD;
        if ("COMPLETED".equals(c2))
            return terminalWitness ? Next.FINALIZE : Next.HOLD;
        if ("CANCELLED".equals(c2))
            return "CANCEL_PENDING".equals(backend) ? Next.RECONCILE : Next.WAIT;
        if ("FAILED".equals(c2) || "INTERRUPTED".equals(c2) || "HOLD".equals(c2))
            return Next.HOLD;
        if (activeExactGoal && near && !cancelIssued && "C2_ACCEPTED".equals(backend))
            return Next.CANCEL;
        if (activeExactGoal || cancelIssued) return Next.WAIT;
        return Next.HOLD; // Missing owner/terminal proof: never reissue move.
    }
}
