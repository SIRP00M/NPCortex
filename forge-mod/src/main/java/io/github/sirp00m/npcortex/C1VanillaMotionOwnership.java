package io.github.sirp00m.npcortex;

/**
 * C1-R2 Fix #2: the synthetic ServerPlayer has no client movement packets.
 * Vanilla hurt/knockback may run in a different phase than NPCortex's END-tick
 * authoritative Player.travel. Hold the LAST Vanilla-produced velocity across
 * that boundary, not a made-up force or an accumulated second knockback.
 *
 * Only one movement owner may consume the snapshot. Physics still uses the
 * actual Player.travel / Entity.move collision implementation. This small
 * pure-Java state machine deliberately knows nothing about Forge or Qwen.
 */
final class C1VanillaMotionOwnership {
    record Velocity(double x, double y, double z) {
        boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
        double horizontalSqr() { return x * x + z * z; }
    }
    record Selection(Velocity velocity, String source) {}

    private static final double HORIZONTAL_EPSILON_SQR = 1.0e-8;
    private static final double VERTICAL_EPSILON = 0.05;
    private Velocity pending;
    private Velocity owned;

    /** Called AFTER Vanilla knockback computed resistance and enchantments.
     * Repeated Vanilla callbacks before physics are replacements, not additive.
     */
    void captureVanilla(Velocity latest) {
        if (latest == null || !latest.finite()) return;
        pending = latest;
        owned = latest;
    }

    /** Choose a single input velocity for an authoritative physics step.
     * The real engine wins whenever it still supplies meaningful motion.
     * Otherwise rejoin the last observed Vanilla velocity (which synthetic
     * ServerPlayer.tick() can lose before END-tick). Gravity on a grounded NPC
     * is not evidence that the horizontal knockback has already been consumed.
     */
    Selection begin(Velocity live, boolean grounded) {
        if (live == null || !live.finite()) return new Selection(new Velocity(0, 0, 0), "INVALID_LIVE");
        if (pending != null) {
            Velocity snapshot = pending;
            pending = null;
            if (liveWins(live, snapshot, grounded)) return new Selection(live, "VANILLA_LIVE");
            return new Selection(snapshot, "VANILLA_HANDOFF");
        }
        if (owned != null && !liveWins(live, owned, grounded)
                && meaningful(owned, grounded)) {
            return new Selection(owned, "OWNED_CONTINUATION");
        }
        return new Selection(live, "VANILLA_LIVE");
    }

    /** Store Vanilla's damped result after travel. Never retain momentum into
     * a wall or a floor: the collision engine wins over the owned snapshot.
     */
    void completed(Velocity vanillaAfter, boolean grounded, boolean horizontalCollision,
                   boolean verticalCollision) {
        if (vanillaAfter == null || !vanillaAfter.finite()) { clear(); return; }
        double x = horizontalCollision ? 0.0 : vanillaAfter.x;
        double z = horizontalCollision ? 0.0 : vanillaAfter.z;
        double y = (verticalCollision || (grounded && vanillaAfter.y < 0))
                ? 0.0 : vanillaAfter.y;
        owned = new Velocity(x, y, z);
    }

    void clear() { pending = null; owned = null; }

    private static boolean liveWins(Velocity live, Velocity stored, boolean grounded) {
        // A synthetic ServerPlayer's world phase can replace a horizontal
        // combat vector with ONLY ordinary downward gravity (~ -0.0784).
        // That is not evidence the horizontal knockback was consumed.
        if (live.horizontalSqr() > HORIZONTAL_EPSILON_SQR) return true;
        if (live.y > VERTICAL_EPSILON) return true;
        if (stored.horizontalSqr() > HORIZONTAL_EPSILON_SQR) return false;
        return !grounded && live.y < -VERTICAL_EPSILON && stored.y <= 0.0;
    }

    private static boolean meaningful(Velocity v, boolean grounded) {
        return v.horizontalSqr() > HORIZONTAL_EPSILON_SQR
                || (v.y > VERTICAL_EPSILON)
                || (!grounded && v.y < -VERTICAL_EPSILON);
    }
}
