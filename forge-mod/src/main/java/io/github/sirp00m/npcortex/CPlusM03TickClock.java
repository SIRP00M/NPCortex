package io.github.sirp00m.npcortex;

/** Pairs a server-tick START with its END so a duration can never come from a stale or missing start.
 * Pure (no Minecraft types, caller supplies the clock) so the lifecycle is unit-testable. */
final class CPlusM03TickClock {
    private long start;
    private boolean open;
    private int unmatchedEnds;

    /** Measurement boundary: forget any START recorded before this point. */
    void reset() { open = false; start = 0L; }

    void start(long nowNanos) { start = nowNanos; open = true; }

    /** Duration in ms of the open START..END pair, or NaN when there is none or the pair is not sane.
     * An END whose START predates the measurement (the command that began it ran mid-tick) yields NaN, not a sample. */
    double end(long nowNanos) {
        if (!open) { unmatchedEnds++; return Double.NaN; }
        open = false;
        long elapsed = nowNanos - start;
        if (elapsed < 0L) { unmatchedEnds++; return Double.NaN; }
        return elapsed / 1e6;
    }

    int unmatchedEnds() { return unmatchedEnds; }
}
