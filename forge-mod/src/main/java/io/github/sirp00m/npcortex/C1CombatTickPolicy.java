package io.github.sirp00m.npcortex;

/**
 * C1-R2: Connection-independent Vanilla combat tick contract.
 * DamageSource, hurt frames and knockback magnitude are owned by Minecraft,
 * not an NPCortex reimplementation. Only the travel scheduling is ours.
 * Pure Java by design so we can regression-test it without Forge downloads.
 */
final class C1CombatTickPolicy {
    static final int MAX_COMBAT_TICKS = 100;
    static final int MIN_COMBAT_TICKS = 6;
    static final double MIN_IMPULSE_DELTA_SQR = 1.0e-10;
    static final double REST_HORIZONTAL_SPEED_SQR = 0.000625; // (0.025 blocks/tick)^2
    static final double REST_VERTICAL_SPEED = 0.06;
    // Values below these limits are numerical jitter rather than a meaningful
    // displacement/impulse. The fallback does not change the impulse strength.
    static final double MIN_HORIZONTAL_IMPULSE_SQR = 0.0001; // 0.01 blocks/tick
    static final double MIN_VERTICAL_IMPULSE = 0.05;
    static final double POSITION_EPSILON_SQR = 1.0e-8;
    static final double POSITION_EPSILON = 1.0e-4;

    private C1CombatTickPolicy() {}

    static boolean mayIntegrate(boolean registered, boolean alive, boolean removed, boolean chunkLoaded) {
        return registered && alive && !removed && chunkLoaded;
    }

    static boolean actualImpulse(double beforeX, double beforeY, double beforeZ,
                                 double afterX, double afterY, double afterZ) {
        double dx = afterX - beforeX;
        double dy = afterY - beforeY;
        double dz = afterZ - beforeZ;
        return Double.isFinite(dx) && Double.isFinite(dy) && Double.isFinite(dz)
                && dx * dx + dy * dy + dz * dz > MIN_IMPULSE_DELTA_SQR;
    }

    static boolean flaggedExternalImpulse(boolean vanillaImpulseFlag, double vx, double vy, double vz) {
        return vanillaImpulseFlag && Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz)
                && vx * vx + vy * vy + vz * vz > MIN_IMPULSE_DELTA_SQR;
    }

    static boolean shouldEndImpulse(int age, boolean grounded, double velocityX,
                                    double velocityY, double velocityZ) {
        if (age < MIN_COMBAT_TICKS || !grounded) return false;
        // A synthetic ServerPlayer can retain the ordinary grounded gravity
        // velocity (-0.0784) even with no physical displacement. That must
        // not extend a completed combat session for the full timeout.
        return velocityX * velocityX + velocityZ * velocityZ <= REST_HORIZONTAL_SPEED_SQR
                && (velocityY <= 0.0 || Math.abs(velocityY) <= REST_VERTICAL_SPEED);
    }

    /** Call Vanilla Entity.move ONLY if Player.travel failed to consume the
     * incoming horizontal impulse. Respect a reported horizontal collision:
     * it may be a real wall, not a broken movement path.
     */
    static boolean needsHorizontalCollisionFallback(double vx, double vz,
                                                     double movedX, double movedZ,
                                                     boolean horizontalCollision) {
        return !horizontalCollision && Double.isFinite(vx) && Double.isFinite(vz)
                && Double.isFinite(movedX) && Double.isFinite(movedZ)
                && vx * vx + vz * vz >= MIN_HORIZONTAL_IMPULSE_SQR
                && movedX * movedX + movedZ * movedZ <= POSITION_EPSILON_SQR;
    }

    /** Resolve unconsumed vertical knockback, but never create a downward
     * impulse into solid ground. Vanilla Entity.move remains the collision
     * authority for any requested fallback, including ceilings.
     */
    static boolean needsVerticalCollisionFallback(double vy, double movedY,
                                                   boolean grounded, boolean verticalCollision) {
        return !verticalCollision && Double.isFinite(vy) && Double.isFinite(movedY)
                && Math.abs(vy) >= MIN_VERTICAL_IMPULSE
                && Math.abs(movedY) <= POSITION_EPSILON
                && (vy > 0.0 || !grounded);
    }

    static boolean ownsTravelThisTick(int combatTicksRemaining) {
        return combatTicksRemaining > 0;
    }

    static boolean canClearHorizontalVelocity(int combatTicksRemaining) {
        return combatTicksRemaining <= 0;
    }
}
