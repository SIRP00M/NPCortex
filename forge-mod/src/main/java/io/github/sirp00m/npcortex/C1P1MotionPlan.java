package io.github.sirp00m.npcortex;

/**
 * Dependency-free bounded movement intent. Qwen, pathfinding, chunk loading and
 * Minecraft's tick loop are deliberately outside this state machine.
 */
final class C1P1MotionPlan {
    enum Direction {
        FORWARD(0.0, 1.0), BACK(0.0, -1.0), LEFT(1.0, 0.0), RIGHT(-1.0, 0.0);
        final double strafe;
        final double forward;
        Direction(double strafe, double forward) {
            this.strafe = strafe;
            this.forward = forward;
        }
    }

    enum Phase { MOVING, SETTLING, COMPLETE, BLOCKED, TIMED_OUT, CANCELLED, CHUNK_UNLOADED }

    static final double MIN_DISTANCE = 0.5;
    static final double C2_PRECISION_MIN_DISTANCE = 0.10;
    static final double MAX_DISTANCE = 8.0;
    static final int MAX_TICKS = 240;
    static final int STALL_LIMIT = 16;
    static final int MAX_SETTLE_TICKS = 100;

    final Direction direction;
    final double requested;
    final double axisX;
    final double axisZ;
    final double originX;
    final double originZ;
    double lastX;
    double lastZ;
    double progress;
    int elapsed;
    int stalled;
    int settlingTicks;
    Phase phase;

    C1P1MotionPlan(Direction direction, double distance, float yaw, double x, double z) {
        this(direction,distance,yaw,x,z,false);
    }

    /** C2-only short movement; ordinary C1 movement keeps its 0.5-block minimum. */
    C1P1MotionPlan(Direction direction, double distance, float yaw, double x, double z, boolean c2Precision) {
        double minimum = c2Precision ? C2_PRECISION_MIN_DISTANCE : MIN_DISTANCE;
        if (direction == null || !Double.isFinite(distance)
                || distance < minimum || distance > MAX_DISTANCE) {
            throw new IllegalArgumentException("Distance must be finite and within movement bounds");
        }
        this.direction = direction;
        this.requested = distance;
        this.originX = x;
        this.originZ = z;
        this.lastX = x;
        this.lastZ = z;
        double radians = Math.toRadians(yaw);
        this.axisX = direction.strafe * Math.cos(radians) - direction.forward * Math.sin(radians);
        this.axisZ = direction.forward * Math.cos(radians) + direction.strafe * Math.sin(radians);
        this.phase = Phase.MOVING;
    }

    void observe(double x, double z) {
        if (phase != Phase.MOVING) return;
        double delta = (x - lastX) * axisX + (z - lastZ) * axisZ;
        lastX = x;
        lastZ = z;
        // Count net progress from the starting point, not accumulated steps:
        // walking backwards/oscillating cannot falsely complete the intent.
        progress = Math.max(0.0, (x - originX) * axisX + (z - originZ) * axisZ);
        stalled = delta < 0.004 ? stalled + 1 : 0;
        elapsed++;
        if (progress >= requested - 0.12) phase = Phase.COMPLETE;
        else if (stalled >= STALL_LIMIT) phase = Phase.BLOCKED;
        else if (elapsed >= MAX_TICKS) phase = Phase.TIMED_OUT;
    }

    void cancel() {
        if (phase == Phase.MOVING) phase = Phase.CANCELLED;
    }

    boolean moving() { return phase == Phase.MOVING; }
    boolean terminal() { return phase != Phase.MOVING && phase != Phase.SETTLING; }
    double remaining() { return Math.max(0.0, requested - progress); }
}
