package io.github.sirp00m.npcortex;

/** Timed-mining accumulator, pure Java. The per-tick rate comes from the game (BlockState.getDestroyProgress for the avatar's
 * CURRENT tool), so hardness, tool tier, enchantments and effects are the game's own. This class only adds up the rate, once
 * per game tick, and starts again when the tool changes (the vanilla client also restarts when the held stack changes).
 *
 * It never calls anything: it cannot break a block, and it cannot start a native effect. "Ready" is only an input to the flow,
 * which still needs the durable EXECUTION_STARTED marker and a final server-thread validation.
 */
final class MiningProgress {
    /** Stall bound: expected ticks times this factor plus a constant. A rate that never completes cannot hold an actor forever. */
    static final int STALL_FACTOR = 4;
    static final int STALL_SLACK = 40;
    static final int HARD_LIMIT = 24_000;

    private float progress;
    private String tool;
    private long lastTick = Long.MIN_VALUE;
    private int steps;
    private int limit = -1;

    float progress() { return progress; }

    /** Fingerprint of the tool the progress was earned with (null before the first step). */
    String tool() { return tool; }

    int steps() { return steps; }

    /** 0..9 crack stage for the animation, -1 when nothing has been mined yet. */
    int stage() { return progress <= 0f ? -1 : Math.min(9, (int) (progress * 10f)); }

    boolean ready() { return progress >= 1.0f; }

    /** @return false when the stall bound is exceeded (the caller ends the attempt without any effect). */
    boolean withinBound() { return steps <= (limit < 0 ? 200 : limit); }

    /**
     * @param gameTick the server's game tick; a second call in the same tick is ignored (two tick hooks must never double the rate)
     * @param tool     fingerprint of the held tool; a change resets the progress
     * @param perTick  the game's progress for this tick; non-finite or non-positive means "cannot mine"
     * @return false when this call changed nothing (same tick or an unusable rate)
     */
    boolean advance(long gameTick, String tool, float perTick) {
        if (gameTick == lastTick) return false;
        lastTick = gameTick;
        if (this.tool != null && !this.tool.equals(tool)) {
            progress = 0f;
            steps = 0;
            limit = -1;
        }
        this.tool = tool;
        if (!Float.isFinite(perTick) || perTick <= 0f) {
            steps++;
            return false;
        }
        if (limit < 0) limit = (int) Math.min(HARD_LIMIT, Math.ceil(1.0 / perTick) * STALL_FACTOR + STALL_SLACK);
        progress += perTick;
        steps++;
        return true;
    }
}
