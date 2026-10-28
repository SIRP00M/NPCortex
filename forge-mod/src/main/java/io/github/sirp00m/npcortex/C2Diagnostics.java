package io.github.sirp00m.npcortex;

import java.util.UUID;

/**
 * Read-only log formats of the two Rev6 diagnostics (D1). Pure strings: nothing here reads or changes C2 state, and the call sites only pass
 * values that already exist. The G5 collector parses exactly these lines, so the field order is a contract.
 */
final class C2Diagnostics {
    static final int MAX_REASON = 160;

    private C2Diagnostics() {}

    private static String id(UUID id) { return id == null ? "none" : id.toString(); }

    /** The reason is one line of production text; control characters never reach the log and the length is bounded. */
    static String clean(String text) {
        if (text == null) return "none";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_REASON));
        for (int i = 0; i < text.length() && out.length() < MAX_REASON; i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString();
    }

    /** Read-only views of a run for the call sites: the call sites in C2AgentRuntime stay free of any state-specific token (see C2LongGoalBoundarySelfTest). */
    static String cancel(C2AgentRuntime.Run run) { return cancel(run.actor, run.goal, run.phase, run.longState); }

    static String result(C2AgentRuntime.Run run, boolean succeeded, String reason) { return result(run.actor, run.goal, succeeded, run.phase, run.longState, reason); }

    /** D1-B: the state seen by C2AgentRuntime.cancel() at invocation (before it mutates anything). */
    static String cancel(UUID actor, UUID goal, String phase, Object longState) {
        return "NPCortex C2 diag cancel actor=" + id(actor) + " goal=" + id(goal) + " phase=" + clean(phase) + " long=" + longState;
    }

    /** D1-A: the exact result reason, before finish() replaces the journal message with the C0 detail. */
    static String result(UUID actor, UUID goal, boolean succeeded, String phase, Object longState, String reason) {
        return "NPCortex C2 diag result actor=" + id(actor) + " goal=" + id(goal) + " succeeded=" + succeeded
                + " phase=" + clean(phase) + " long=" + longState + " reason=" + clean(reason);
    }
}
