package io.github.sirp00m.npcortex;

import java.util.Locale;

/**
 * Sprint 2: dependency-free contract for a single operator-authorized 3D
 * movement primitive. This does not declare a graph edge traversable: only
 * a real C1 Vanilla movement session can produce a verified receipt.
 */
final class C1S2TraversalPlan {
    enum Kind {
        STEP_UP(0.5, false), STEP_DOWN(-0.5, false),
        JUMP_UP(1.0, true), SAFE_DESCEND(-1.0, false);
        final double height;
        final boolean jump;
        Kind(double height, boolean jump) { this.height = height; this.jump = jump; }
    }
    static final double MIN_DISTANCE = 0.65;
    static final double MAX_DISTANCE = 1.45;
    static final double HORIZONTAL_TOLERANCE = 0.27;
    static final double HEIGHT_TOLERANCE = 0.16;
    static final int MAX_SESSION_TICKS = 120;
    static final int SETTLE_TICKS = 60;

    final Kind kind;
    final double startY;
    final double targetY;
    final double originX, originZ, targetX, targetZ;
    boolean leftGround;
    boolean combatInterrupted;

    C1S2TraversalPlan(Kind kind, double startX, double startY, double startZ,
                      double targetX, double targetZ, double distance) {
        if (kind == null || !finite(startX, startY, startZ, targetX, targetZ, distance)
                || distance < MIN_DISTANCE || distance > MAX_DISTANCE)
            throw new IllegalArgumentException("Invalid bounded 3D traversal request");
        if (Math.abs(Math.hypot(targetX - startX, targetZ - startZ) - distance) > 0.001)
            throw new IllegalArgumentException("Movement distance/target mismatch");
        this.kind = kind;
        this.startY = startY;
        this.targetY = startY + kind.height;
        this.originX = startX;
        this.originZ = startZ;
        this.targetX = targetX;
        this.targetZ = targetZ;
    }

    boolean onTick(double x, double y, double z, boolean grounded, boolean combat) {
        if (!grounded) leftGround = true;
        if (combat) combatInterrupted = true;
        return grounded;
    }

    /** A successful C1 horizontal plan alone is never a successful 3D traversal. */
    String verify(double x, double y, double z, boolean grounded, String horizontalResult) {
        if (combatInterrupted) return "TRAVERSAL_INTERRUPTED_COMBAT";
        if (!"COMPLETE".equals(horizontalResult)) return "TRAVERSAL_" + horizontalResult;
        if (!grounded) return "TRAVERSAL_NOT_GROUNDED";
        if (kind.jump && !leftGround) return "TRAVERSAL_JUMP_NOT_OBSERVED";
        if (kind == Kind.SAFE_DESCEND && !leftGround) return "TRAVERSAL_DESCENT_NOT_OBSERVED";
        if (!finite(x, y, z)) return "TRAVERSAL_INVALID_POSITION";
        if (Math.hypot(x - targetX, z - targetZ) > HORIZONTAL_TOLERANCE)
            return "TRAVERSAL_HORIZONTAL_MISMATCH";
        if (Math.abs(y - targetY) > HEIGHT_TOLERANCE)
            return "TRAVERSAL_LANDING_MISMATCH";
        return "TRAVERSAL_COMPLETE_" + kind.name();
    }

    static Kind parse(String type) {
        try { return Kind.valueOf(type.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Type must be step_up, step_down, jump_up or safe_descend");
        }
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
