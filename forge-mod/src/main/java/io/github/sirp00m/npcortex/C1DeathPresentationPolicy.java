package io.github.sirp00m.npcortex;

/** R1: keep a dead player avatar only long enough for Vanilla client death rendering.
 *  The death is already committed to SavedData; this is NEVER a second death.
 */
final class C1DeathPresentationPolicy {
    static final int PRESENTATION_TICKS = 26; // Vanilla fall-over uses ~20 ticks; 6 ticks network margin.
    // R1-A: one noticeable mob-like white smoke burst at the moment of removal.
    // ServerLevel.sendParticles broadcasts Vanilla particles; no custom packet or render layer.
    static final int VANISH_POOF_COUNT = 26;
    static final int VANISH_CLOUD_COUNT = 10;
    private C1DeathPresentationPolicy() {}

    static boolean mayPresent(boolean deadPersisted, boolean liveAvatar, boolean removed) {
        return deadPersisted && liveAvatar && !removed;
    }
    static boolean shouldDetach(long nowTick, long deadlineTick, boolean sameAvatar, boolean removed) {
        return !sameAvatar || removed || nowTick >= deadlineTick;
    }

    /** Only natural completion of a confirmed death may emit the one-shot burst.
     * Premature detach, stale avatar, invalid lifecycle, and shutdown emit nothing.
     */
    static boolean shouldEmitVanishPoof(long nowTick, long deadlineTick,
                                      boolean sameAvatar, boolean removed, boolean deadPersisted) {
        return nowTick >= deadlineTick && sameAvatar && !removed && deadPersisted;
    }
}
