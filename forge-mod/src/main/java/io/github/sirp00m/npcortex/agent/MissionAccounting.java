package io.github.sirp00m.npcortex.agent;

/** Cleanup may be needed to reach ground drops after harvesting. It is NOT
 * completion: the final verifier still requires every item in the bag. */
public final class MissionAccounting {
    private MissionAccounting() {}
    public static boolean cleanupReady(int remaining, int stored, int borrowed, int goal, int mined) {
        return remaining==0 && stored>=0 && borrowed>=0 && goal>0 && mined>=goal;
    }
}
