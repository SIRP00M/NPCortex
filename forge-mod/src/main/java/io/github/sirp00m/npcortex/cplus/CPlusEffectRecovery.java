package io.github.sirp00m.npcortex.cplus;

import java.util.Objects;

/** C-F1 D+: pure effect/reconciliation contract. Not connected to the live world or executor. */
public final class CPlusEffectRecovery {
    private CPlusEffectRecovery() {}
    public enum Outcome { VERIFIED, PARTIAL, UNKNOWN, EXTERNALLY_CONSUMED }
    public enum Action { COMPLETE, REPLAN_REMAINING, HOLD_FOR_EVIDENCE }
    public record Observation(boolean authoritative, int targetQuantity, int verifiedInActorInventory,
                              int verifiedExternalQuantity, int unaccountedQuantity, String evidenceId) {
        public Observation {
            if (targetQuantity < 0 || verifiedInActorInventory < 0 || verifiedExternalQuantity < 0
                    || unaccountedQuantity < 0 || verifiedInActorInventory > targetQuantity
                    || verifiedExternalQuantity > targetQuantity || unaccountedQuantity > targetQuantity
                    || (long)verifiedInActorInventory + verifiedExternalQuantity + unaccountedQuantity > targetQuantity
                    || evidenceId == null || evidenceId.length() > 160)
                throw new IllegalArgumentException("invalid observed quantities or evidence");
            if (authoritative && evidenceId.isBlank())
                throw new IllegalArgumentException("authoritative observation requires evidence identifier");
        }
    }
    public record Resolution(Outcome outcome, Action next, int credited, int remaining, String evidenceId) {}

    /** No amount observed only as a drop or by another actor counts toward this actor's inventory goal. */
    public static Resolution resolve(Observation obs) {
        Objects.requireNonNull(obs, "observation");
        if (!obs.authoritative())
            return new Resolution(Outcome.UNKNOWN, Action.HOLD_FOR_EVIDENCE, 0, obs.targetQuantity(), "");
        int missing = obs.targetQuantity() - obs.verifiedInActorInventory();
        if (obs.unaccountedQuantity() > 0)
            return new Resolution(Outcome.UNKNOWN, Action.HOLD_FOR_EVIDENCE,
                    obs.verifiedInActorInventory(), missing, obs.evidenceId());
        if (missing == 0)
            return new Resolution(Outcome.VERIFIED, Action.COMPLETE, obs.verifiedInActorInventory(), 0, obs.evidenceId());
        return new Resolution(obs.verifiedExternalQuantity() > 0 ? Outcome.EXTERNALLY_CONSUMED : Outcome.PARTIAL,
                Action.REPLAN_REMAINING, obs.verifiedInActorInventory(), missing, obs.evidenceId());
    }
}
