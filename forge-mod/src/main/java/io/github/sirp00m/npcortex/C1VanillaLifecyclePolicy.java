package io.github.sirp00m.npcortex;

/**
 * R0 ownership contract. This is a policy, not a substitute for the Minecraft
 * ServerPlayer lifecycle. Every player-like phase has exactly one owner.
 *
 * WORLD: vanilla ServerPlayer.tick (existing P1-C Fix #1 behavior)
 * CONNECTION: disabled, because a passive listener is not a logged-in client
 * MOTION: existing bounded P1-A controller, never also doTick/travel
 * COMBAT: Vanilla calculates impulse; NPCortex schedules the one travel owner
 * DEATH: existing P1-C fail-closed state machine
 * TRACKING: existing P0 two-phase viewer synchronization
 */
final class C1VanillaLifecyclePolicy {
    enum Phase { WORLD, CONNECTION, MOTION, COMBAT, DEATH, TRACKING }
    enum Owner { VANILLA, NPCORTEX, DISABLED }

    private C1VanillaLifecyclePolicy() {}

    static Owner owner(Phase phase) {
        return switch (phase) {
            case WORLD -> Owner.VANILLA;
            case CONNECTION -> Owner.DISABLED;
            case MOTION, COMBAT, DEATH, TRACKING -> Owner.NPCORTEX;
        };
    }

    /** A detached, dead or mid-transition avatar must not advance world ticks. */
    static boolean mayRunWorldTick(boolean registeredLive, boolean removed, boolean lifecycleAlive) {
        return registeredLive && !removed && lifecycleAlive;
    }

}
