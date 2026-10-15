package io.github.sirp00m.npcortex;

/** P1-C persistent lifecycle phases. TRANSITION is fail-closed after interruption. */
enum C1LifecyclePhase {
    ALIVE, DEAD, TRANSITION;

    boolean canMoveOrMutate() { return this == ALIVE; }
    boolean canRespawn() { return this == DEAD; }
    boolean canRestoreAutomatically() { return this == ALIVE; }
    static C1LifecyclePhase decode(String text) {
        try { return valueOf(text); } catch (IllegalArgumentException | NullPointerException ex) {
            return TRANSITION; // Unknown/future state must not spawn duplicate players.
        }
    }
}
