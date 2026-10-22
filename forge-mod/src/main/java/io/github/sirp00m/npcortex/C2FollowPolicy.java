package io.github.sirp00m.npcortex;

/** C2-F1 bounded moving-target follow policy. Pure Java; no Forge or IO.
 * Hysteresis prevents stop/start oscillation; only a server-thread owner may tick it.
 */
final class C2FollowPolicy {
    static final double STOP_DISTANCE = 2.5;
    static final double RESUME_DISTANCE = 4.0;
    static final double TARGET_MOVE_THRESHOLD = 1.5;
    static final int PLAN_COOLDOWN_TICKS = 20;
    static final int MAX_FAILURES = 3;
    static final int RECOVERY_REPROBE_TICKS = 120;
    static final int MAX_ROUTE_TICKS = 800;
    static final int MAX_SEGMENT_TICKS = 380;
    static final int MAX_CANDIDATE_PLANS = 4;
    static final int PER_PLAN_BUDGET = 3000;
    static final int MAX_ACTORS = 10;
    static final int PERSISTENT_PROBE_TICKS = 10; // bounded, no per-tick A*
    static final int SETTLE_TIMEOUT_TICKS = 100;
    static final double ROLLING_DISTANCE = 6.5; // local scan radius; not global path planning
    enum DistanceGate { HOLD, FOLLOW, KEEP }
    private boolean following;
    private long nextPlanTick;
    private int failures;
    private int generation;
    private long recoveryAt;
    private boolean recoveryUsed;
    // Follow-only periodic terrain refresh. Legacy one-shot recoverOnce stays
    // unchanged for old policy clients; terrain-native use is rate-limited.
    private int terrainReprobes;
    private long terrainRetryAt;
    private double lastTargetX, lastTargetZ;
    private boolean hasTarget;
    DistanceGate distance(double horizontalDistance) {
        if (!Double.isFinite(horizontalDistance) || horizontalDistance<0)
            throw new IllegalArgumentException("Invalid target distance");
        if (horizontalDistance<=STOP_DISTANCE) { following=false; return DistanceGate.HOLD; }
        if (horizontalDistance>=RESUME_DISTANCE) { following=true; return DistanceGate.FOLLOW; }
        return following ? DistanceGate.FOLLOW : DistanceGate.KEEP;
    }
    boolean ready(long tick) { return tick>=nextPlanTick; }
    /** A verified physical terminal can request the next local follow plan
     * promptly instead of paying a fresh 20-tick cooldown. The two-tick
     * floor, global one-plan-per-tick limit and failure backoff still apply.
     * Never call on a failed, stale, foreign or unverified motion receipt. */
    void afterVerifiedRoute(long tick) {
        nextPlanTick=tick+2;
    }
    void defer(long tick) { nextPlanTick=tick+PLAN_COOLDOWN_TICKS; }
    void planned(long tick,double targetX,double targetZ) {
        if (!Double.isFinite(targetX)||!Double.isFinite(targetZ)) throw new IllegalArgumentException("target invalid");
        generation++;
        // A successfully found path is NOT evidence of successful C1 movement.
        // Preserve failures so a recurring collision cannot replan indefinitely.
        lastTargetX=targetX;lastTargetZ=targetZ;hasTarget=true;
        defer(tick);
    }
    boolean targetMoved(double x,double z) {
        return !hasTarget || Math.hypot(x-lastTargetX,z-lastTargetZ)>=TARGET_MOVE_THRESHOLD;
    }
    boolean failed(long tick) {
        failures++;
        defer(tick);
        if(failures==MAX_FAILURES) {
            recoveryAt=tick+RECOVERY_REPROBE_TICKS;
            terrainRetryAt=tick+Math.min(RECOVERY_REPROBE_TICKS,40+40*terrainReprobes);
        }
        return failures<MAX_FAILURES;
    }
    /** One delayed fresh-world retry for a stationary target. Bounded: no
     * endless oscillating replan loop on genuinely inaccessible terrain. */
    boolean recoverOnce(long tick) {
        if(failures<MAX_FAILURES || recoveryUsed || tick<recoveryAt) return false;
        recoveryUsed=true;
        failures=0;
        return true;
    }
    /** Terrain-native stationary-target recovery. Retry a NEW world snapshot
     * on an increasing 2/4/6-second delay, never a stale stored path.
     * At most one expensive Follow plan per server tick still applies; this
     * does NOT authorize C1 motion and never clears failure hints by itself.
     * Cannot spin every tick or become permanently dormant after one retry.
     */
    boolean terrainReprobe(long tick) {
        if(failures<MAX_FAILURES || tick<terrainRetryAt) return false;
        terrainReprobes=Math.min(3,terrainReprobes+1);
        failures=0;recoveryUsed=false;
        return true;
    }
    int terrainReprobes() {return terrainReprobes;}
    void targetRelocated() {
        failures=0;recoveryUsed=false;recoveryAt=0;
        terrainReprobes=0;terrainRetryAt=0;
    }
    int generation() { return generation; }
    int failures() { return failures; }
    void markTarget(double x,double z) { lastTargetX=x;lastTargetZ=z;hasTarget=true; }
    /** Freshly proven same-token Follow continuation; never reset failures
     * based on planning alone and never use the full cooldown on live WALK. */
    void rollingExtended(long tick,double x,double z) {
        if(!Double.isFinite(x)||!Double.isFinite(z)) throw new IllegalArgumentException();
        generation++;
        markTarget(x,z);
        nextPlanTick=tick+PERSISTENT_PROBE_TICKS;
    }
    /** Call only after a verified physical route terminal, never on a planned path. */
    void resetFailureWindow() { targetRelocated(); }
    boolean following() { return following; }
    void holdUntilResumeDistance() { following=false; }
    /** Full termination never permits an automatic resurrection of movement. */
    static boolean ownedTicket(java.util.UUID expected,java.util.UUID observed,boolean active,
                               C2S4RecoveryPolicy.MotionOrigin origin) {
        return expected!=null && expected.equals(observed) && active
                && origin==C2S4RecoveryPolicy.MotionOrigin.C2_COMMAND;
    }
    static boolean foreignActive(java.util.UUID expected,java.util.UUID observed,boolean active,
                                 C2S4RecoveryPolicy.MotionOrigin origin) {
        return active && !ownedTicket(expected,observed,true,origin);
    }
}
