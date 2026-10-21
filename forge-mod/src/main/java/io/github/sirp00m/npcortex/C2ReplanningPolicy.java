package io.github.sirp00m.npcortex;

/**
 * C2-P1-D: pure Java, per-Goal bounded route-replacement policy.
 * This class never accesses Forge, network, or motion. Only its server-thread
 * owner may change it. One C0 action owns all route generations.
 */
final class C2ReplanningPolicy {
    static final int MAX_REPLANS = 3;
    static final int COOLDOWN_TICKS = 20;
    static final int TOTAL_SEARCH_BUDGET = 36000;
    static final int PER_REPLAN_BUDGET = 12000;

    enum Trigger { EDGE_INVALIDATED, DEVIATED, BLOCKED, ENDPOINT_MISSED, VERTICAL_TERMINAL, COMBAT_DISPLACED }
    enum Gate { WAIT, READY, EXHAUSTED, NOT_PENDING }
    private int generation;
    private int attempts;
    private int expanded;
    private long nextAttemptTick;
    private Trigger trigger;
    private boolean pending;
    private String lastStatus = "NONE";

    int generation() { return generation; }
    int attempts() { return attempts; }
    int expanded() { return expanded; }
    String lastStatus() { return lastStatus; }
    Trigger trigger() { return trigger; }
    boolean pending() { return pending; }

    /** A trigger never starts motion directly and never increases goal ownership. */
    boolean request(Trigger cause, long currentTick) {
        if (cause == null || pending || attempts >= MAX_REPLANS || expanded >= TOTAL_SEARCH_BUDGET) return false;
        trigger=cause;
        pending=true;
        nextAttemptTick=currentTick + COOLDOWN_TICKS;
        return true;
    }

    Gate gate(long currentTick) {
        if (!pending) return Gate.NOT_PENDING;
        if (attempts >= MAX_REPLANS || expanded >= TOTAL_SEARCH_BUDGET) return Gate.EXHAUSTED;
        return currentTick < nextAttemptTick ? Gate.WAIT : Gate.READY;
    }

    int budget() {
        if (!pending || attempts >= MAX_REPLANS || expanded >= TOTAL_SEARCH_BUDGET)
            throw new IllegalStateException("No replanning budget available");
        return Math.min(PER_REPLAN_BUDGET, TOTAL_SEARCH_BUDGET-expanded);
    }

    /** Called once per B3 planning attempt; never ignore its measured expansion cost. */
    void evaluated(int plannedBudget, int actualExpansions, String status, boolean found, long currentTick) {
        if (gate(currentTick)!=Gate.READY || plannedBudget < 1 || plannedBudget > budget()
                || actualExpansions < 0 || actualExpansions > plannedBudget || status==null)
            throw new IllegalStateException("Invalid or stale replanning result");
        attempts++;
        expanded+=actualExpansions;
        lastStatus=status;
        if (found) {
            generation++;
            pending=false;
            trigger=null;
        } else {
            nextAttemptTick=currentTick+COOLDOWN_TICKS;
        }
    }

    boolean retryable() { return pending && attempts<MAX_REPLANS && expanded<TOTAL_SEARCH_BUDGET; }
    void stop() { pending=false; trigger=null; }
}
