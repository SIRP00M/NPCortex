package io.github.sirp00m.npcortex;

/**
 * Follow-only, dependency-free adapter inspired by the intent of Vanilla
 * MoveControl: bounded yaw rotlerp, a desired forward speed, and gentle speed
 * changes while the verified C1 ServerPlayer remains the sole physics owner.
 *
 * This is intentionally NOT Minecraft's Mob MoveControl; that class requires
 * a Mob and would introduce a second, incompatible controller. It never moves
 * entities, generates paths, cancels tickets or queries the world.
 */
final class C1VanillaMoveControlAdapter {
    record Drive(float yaw, double forward) { }
    private static final float NORMAL_YAW_STEP = 18f;
    private static final float LARGE_YAW_STEP = 24f;
    private static final double MAX_ACCEL_PER_TICK = .24;
    private static final double MAX_DECEL_PER_TICK = .40;
    private double forward;

    Drive tick(float currentYaw, float targetYaw, double distance, boolean lastWaypoint) {
        return tick(currentYaw,targetYaw,distance,lastWaypoint,false);
    }

    Drive tick(float currentYaw, float targetYaw, double distance,
               boolean lastWaypoint, boolean cornerAhead) {
        if (!Float.isFinite(currentYaw) || !Float.isFinite(targetYaw)
                || !Double.isFinite(distance) || distance < 0)
            throw new IllegalArgumentException("Non-finite Vanilla-style follow intent");
        double delta=C2F2Locomotion.angularDelta(currentYaw,targetYaw);
        // C-L3: a bounded ease-out steering curve replaces the former abrupt
        // 32-degree large-turn snap. Physical yaw still belongs to C1, and
        // sharp-turn forward braking/swept collision validation are unchanged.
        float maxStep=Math.abs(delta)>75?LARGE_YAW_STEP:NORMAL_YAW_STEP;
        float step=(float)Math.min(maxStep,Math.max(3.0,Math.abs(delta)*.28));
        float yaw=C2F2Locomotion.wrapYaw(currentYaw+
                (float)Math.max(-step,Math.min(step,delta)));
        double error=Math.abs(C2F2Locomotion.angularDelta(yaw,targetYaw));
        // A sideways/backwards target must not drive into an unverified corner.
        // Only C1's swept-AABB validation authorizes actual player movement.
        double desired=error>=88 ? 0 : Math.max(.20,Math.cos(Math.toRadians(error)));
        if(lastWaypoint && distance<1.0)
            desired*=Math.max(.24,Math.min(1,distance/1.0));
        else if(cornerAhead && distance<1.10)
            desired*=Math.max(.26,Math.min(1,distance/1.10));
        if(desired<forward)
            forward=Math.max(desired,forward-MAX_DECEL_PER_TICK);
        else forward=Math.min(desired,forward+MAX_ACCEL_PER_TICK);
        // Stop input immediately for unsafe sharp turns; never coast under
        // previously computed intent when the heading is behind the actor.
        if(error>=88) forward=0;
        return new Drive(yaw,forward);
    }
}
