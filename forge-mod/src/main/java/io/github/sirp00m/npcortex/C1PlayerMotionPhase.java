package io.github.sirp00m.npcortex;

/**
 * C1-R2 Fix #2 movement lifecycle: one authoritative movement phase per avatar
 * per server END tick. Combat takes precedence over commanded locomotion;
 * settling owns the remaining gravity/jump/cancellation frames.
 *
 * This is deliberately NOT ServerPlayer.doTick: synthetic actors still have no
 * logged-in client movement stream. No phase can issue a second travel step.
 */
enum C1PlayerMotionPhase {
    COMBAT,
    COMMANDED,
    SETTLING;

    static C1PlayerMotionPhase choose(int combatTicksRemaining, boolean planMoving) {
        if (combatTicksRemaining > 0) return COMBAT;
        return planMoving ? COMMANDED : SETTLING;
    }
}
