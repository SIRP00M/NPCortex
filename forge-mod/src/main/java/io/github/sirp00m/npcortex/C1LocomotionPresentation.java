package io.github.sirp00m.npcortex;

/**
 * C-L1/L2/L3: pose-only, per-avatar presentation for the synthetic ServerPlayer.
 * It never calls Player.travel, sets the movement yaw, creates tickets, or
 * changes authoritative collision/velocity. Angles are bounded shortest-arc
 * steps, not an independent movement controller.
 */
final class C1LocomotionPresentation {
    enum Phase { IDLE, TURNING, STARTING, WALKING, CORNERING, STOPPING, INTERRUPTED }
    record Pose(float headYaw, float bodyYaw, Phase phase) { }
    private static final float BODY_STEP = 11f;
    private static final float HEAD_STEP = 18f;
    private static final float HEAD_LEAD = 30f;
    private static final float HEAD_BODY_LIMIT = 55f;
    private static final double MOVING_EPSILON = .018;
    private float body;
    private float head;
    private Phase phase = Phase.IDLE;
    private int movingTicks;
    private int stopTicks;

    C1LocomotionPresentation(float initialHead, float initialBody) {
        if (!Float.isFinite(initialHead) || !Float.isFinite(initialBody))
            throw new IllegalArgumentException("Non-finite initial pose");
        head = wrap(initialHead);
        body = wrap(initialBody);
    }

    static float wrap(float angle) {
        float v = angle % 360f;
        return v >= 180f ? v - 360f : v < -180f ? v + 360f : v;
    }

    static float difference(float from, float to) { return wrap(to - from); }

    static float approach(float from, float to, float step) {
        return wrap(from + Math.max(-step, Math.min(step, difference(from, to))));
    }

    Pose tick(float movementYaw, float targetYaw, double horizontalSpeed,
              boolean commanded, boolean corner, boolean interrupted) {
        if (!Float.isFinite(movementYaw) || !Float.isFinite(targetYaw)
                || !Double.isFinite(horizontalSpeed) || horizontalSpeed < 0)
            throw new IllegalArgumentException("Invalid locomotion presentation input");
        movementYaw = wrap(movementYaw);
        if (interrupted) {
            phase = Phase.INTERRUPTED;
            movingTicks = 0;
            stopTicks = 0;
            // Combat and knockback own movement; do not attempt to face a stale waypoint.
            return new Pose(head, body, phase);
        }
        boolean moving = commanded && horizontalSpeed > MOVING_EPSILON;
        if (moving) {
            movingTicks = Math.min(20, movingTicks + 1);
            stopTicks = 0;
            phase = corner ? Phase.CORNERING : movingTicks <= 3 ? Phase.STARTING : Phase.WALKING;
        } else {
            if (phase == Phase.WALKING || phase == Phase.STARTING || phase == Phase.CORNERING)
                stopTicks = 3;
            else if (phase == Phase.INTERRUPTED) stopTicks = 3;
            if (stopTicks > 0) {
                phase = Phase.STOPPING;
                stopTicks--;
            } else if (Math.abs(difference(body, movementYaw)) > 2f)
                phase = Phase.TURNING;
            else phase = Phase.IDLE;
            movingTicks = 0;
        }
        // Physical yaw remains independently owned by C1 vanilla movement.
        // Head may look toward the next verified waypoint *ahead of* the body.
        float lookOffset = commanded ? Math.max(-HEAD_LEAD,
                Math.min(HEAD_LEAD, difference(movementYaw, targetYaw))) : 0f;
        float wantedHead = wrap(movementYaw + lookOffset);
        body = approach(body, movementYaw, BODY_STEP);
        head = approach(head, wantedHead, HEAD_STEP);
        // Vanilla player renderer tolerates a modest head/body difference;
        // clamp relative yaw to avoid exaggerated twisting during a 180-degree turn.
        float relative = difference(body, head);
        if (Math.abs(relative) > HEAD_BODY_LIMIT)
            head = wrap(body + Math.copySign(HEAD_BODY_LIMIT, relative));
        if (!moving && stopTicks == 0 && phase != Phase.STOPPING
                && Math.abs(difference(body, movementYaw)) < .75
                && Math.abs(difference(head, movementYaw)) < .75)
            phase = Phase.IDLE;
        return new Pose(head, body, phase);
    }

    Phase phase() { return phase; }
    boolean settled(float movementYaw) {
        return phase == Phase.IDLE && Math.abs(difference(body, movementYaw)) < .75
                && Math.abs(difference(head, movementYaw)) < .75;
    }
}
