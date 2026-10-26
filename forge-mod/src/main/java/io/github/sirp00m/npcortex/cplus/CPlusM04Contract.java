package io.github.sirp00m.npcortex.cplus;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** M04 D-Lite contract. Pure Java; NO dispatcher, scheduler or world mutation.
 *  Each step needs a separately verified witness; an HTTP ACK is not proof. */
public final class CPlusM04Contract {
    private CPlusM04Contract() {}
    public static final int SCHEMA = 1;
    public enum Step {
        OBSERVE, EQUIP, NAVIGATE, REOBSERVE, BREAK, VERIFY_BLOCK,
        OBSERVE_DROP, MOVE_TO_DROP, PICKUP, VERIFY_INVENTORY, TERMINAL
    }
    public enum Effect { NONE, VERIFIED, PARTIAL, UNKNOWN, EXTERNALLY_CONSUMED }
    public enum Lifecycle { PLANNED, ACTIVE, HOLD, COMPLETED, CANCELLED, FAILED }
    public static final List<Step> ORDER = List.of(Step.values());

    public record Identity(UUID world, UUID actor, UUID session, UUID requester,
                           UUID task, UUID attempt) {
        public Identity {
            Objects.requireNonNull(world);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(session);
            Objects.requireNonNull(requester);
            Objects.requireNonNull(task);
            Objects.requireNonNull(attempt);
        }
    }
    public record StepWitness(Step step, UUID observation, long serverTick,
                              String evidenceHash, Effect effect) {
        public StepWitness {
            Objects.requireNonNull(step);
            Objects.requireNonNull(observation);
            Objects.requireNonNull(effect);
            if (serverTick < 0 || evidenceHash == null
                    || !evidenceHash.matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("unverifiable witness");
        }
    }
    public record State(Identity identity, int nextStep, Lifecycle lifecycle,
                        Effect effect, List<StepWitness> witnesses) {
        public State {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(lifecycle);
            Objects.requireNonNull(effect);
            witnesses = List.copyOf(witnesses);
            if (nextStep < 0 || nextStep > ORDER.size() || witnesses.size() != nextStep)
                throw new IllegalArgumentException("invalid step cursor");
            for (int i=0;i<witnesses.size();i++)
                if (witnesses.get(i).step() != ORDER.get(i))
                    throw new IllegalArgumentException("out-of-order witness");
            if (lifecycle == Lifecycle.COMPLETED
                    && (nextStep != ORDER.size() || effect != Effect.VERIFIED))
                throw new IllegalArgumentException("completion requires full verification");
        }
    }
    public static State newTask(Identity identity) {
        return new State(identity, 0, Lifecycle.PLANNED, Effect.NONE, List.of());
    }
    /** No step is advanced on an instruction, timer, or unverified metadata ACK. */
    public static State acceptVerified(State previous, Identity identity, StepWitness witness) {
        if (!previous.identity().equals(identity)) throw new IllegalArgumentException("wrong actor/session/task");
        if (previous.lifecycle() != Lifecycle.PLANNED && previous.lifecycle() != Lifecycle.ACTIVE)
            throw new IllegalStateException("nonexecuting lifecycle");
        if (previous.nextStep() >= ORDER.size() || ORDER.get(previous.nextStep()) != witness.step())
            throw new IllegalStateException("out-of-order step");
        if (witness.effect() == Effect.UNKNOWN || witness.effect() == Effect.PARTIAL
                || witness.effect() == Effect.EXTERNALLY_CONSUMED)
            throw new IllegalStateException("uncertain or partial effect must HOLD");
        if ((witness.step() == Step.VERIFY_BLOCK || witness.step() == Step.PICKUP
                || witness.step() == Step.VERIFY_INVENTORY || witness.step() == Step.TERMINAL)
                && witness.effect() != Effect.VERIFIED)
            throw new IllegalStateException("inventory custody not verified");
        var next = new java.util.ArrayList<>(previous.witnesses());
        next.add(witness);
        boolean terminal = next.size() == ORDER.size();
        return new State(identity, next.size(), terminal ? Lifecycle.COMPLETED : Lifecycle.ACTIVE,
                terminal ? Effect.VERIFIED : witness.effect(), next);
    }
    /** HOLD preserves exact position and evidence. It never unlocks or retries effects. */
    public static State hold(State previous, Identity identity, Effect observed) {
        if (!previous.identity().equals(identity)) throw new IllegalArgumentException("wrong identity");
        if (previous.lifecycle() == Lifecycle.COMPLETED || previous.lifecycle() == Lifecycle.CANCELLED)
            throw new IllegalStateException("terminal immutable");
        return new State(identity, previous.nextStep(), Lifecycle.HOLD, observed, previous.witnesses());
    }
    /** Metadata retry is a no-op on execution state: no mutation replay. */
    public static State metadataRetry(State state, Identity identity) {
        if (!state.identity().equals(identity)) throw new IllegalArgumentException("wrong identity");
        return state;
    }
}
