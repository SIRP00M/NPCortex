package io.github.sirp00m.npcortex.cplus;

import java.util.Map;
import java.util.Set;

/** Fail-closed transition policy. A contract model, not a live scheduler or durable journal. */
public final class CPlusLifecycle {
    private CPlusLifecycle() {}
    public enum State {
        REQUESTED, VALIDATED, WAIT_RESOURCE, PREPARED, EXECUTING, VERIFYING,
        CANCEL_PENDING, IN_DOUBT, COMPLETED, CANCELLED, FAILED
    }
    public enum ProofKind { NONE, VERIFIED_WORLD_RECEIPT, CANCEL_ACKNOWLEDGED, TRUSTED_RECONCILIATION }
    public record Proof(ProofKind kind, String receiptId) {
        public Proof {
            if (kind == null || receiptId == null || receiptId.length() > 160)
                throw new IllegalArgumentException("invalid proof");
            if (kind != ProofKind.NONE && receiptId.isBlank())
                throw new IllegalArgumentException("proof requires evidence identifier");
            if (kind == ProofKind.NONE && !receiptId.isEmpty())
                throw new IllegalArgumentException("unverified evidence is not allowed");
        }
        public static Proof none() { return new Proof(ProofKind.NONE, ""); }
    }
    private static final Map<State,Set<State>> EDGES = Map.ofEntries(
            Map.entry(State.REQUESTED, Set.of(State.VALIDATED, State.CANCELLED, State.FAILED)),
            Map.entry(State.VALIDATED, Set.of(State.PREPARED, State.WAIT_RESOURCE, State.CANCEL_PENDING, State.FAILED)),
            Map.entry(State.WAIT_RESOURCE, Set.of(State.PREPARED, State.CANCEL_PENDING, State.FAILED)),
            Map.entry(State.PREPARED, Set.of(State.EXECUTING, State.WAIT_RESOURCE, State.CANCEL_PENDING, State.FAILED, State.IN_DOUBT)),
            Map.entry(State.EXECUTING, Set.of(State.VERIFYING, State.CANCEL_PENDING, State.IN_DOUBT, State.FAILED)),
            Map.entry(State.VERIFYING, Set.of(State.COMPLETED, State.CANCEL_PENDING, State.IN_DOUBT, State.FAILED)),
            Map.entry(State.CANCEL_PENDING, Set.of(State.CANCELLED, State.IN_DOUBT, State.VERIFYING)),
            Map.entry(State.IN_DOUBT, Set.of(State.VERIFYING, State.FAILED)));
    public static State transition(State from, State to, Proof proof) {
        if (from == null || to == null || proof == null || !EDGES.getOrDefault(from, Set.of()).contains(to))
            throw new IllegalStateException("invalid C+ action transition");
        if (to == State.COMPLETED && proof.kind() != ProofKind.VERIFIED_WORLD_RECEIPT)
            throw new IllegalStateException("cannot report success without authoritative verified world receipt");
        if (to == State.CANCELLED && from != State.REQUESTED && proof.kind() != ProofKind.CANCEL_ACKNOWLEDGED)
            throw new IllegalStateException("cancelled without acknowledgement");
        if ((from == State.IN_DOUBT || (from == State.CANCEL_PENDING && to == State.VERIFYING))
                && proof.kind() != ProofKind.TRUSTED_RECONCILIATION)
            throw new IllegalStateException("uncertain action must reconcile before progressing");
        if (to == State.VERIFYING && from == State.EXECUTING && proof.kind() != ProofKind.NONE)
            throw new IllegalStateException("execution receipt is not verified world proof");
        return to;
    }
    public static boolean terminal(State s) {
        return s == State.COMPLETED || s == State.CANCELLED || s == State.FAILED;
    }
    /** A failed or uncertain world mutation cannot be automatically replayed, even on a new attempt ID. */
    public static boolean canAutomaticallyRetry(State s, boolean hasSideEffects) {
        return !hasSideEffects && (s == State.FAILED || s == State.CANCELLED);
    }
}
