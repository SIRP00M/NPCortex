package io.github.sirp00m.npcortex;

/** Independent, testable coordinates -> single Vanilla C1 motion segment. */
final class C2MoveGeometry {
    // Movement stays a single C1 Vanilla travel owner; no teleport or alternate physics.
    static final double MIN_MOVE = 0.10;
    static final double MAX_MOVE = 7.5;
    static final double DEFAULT_TOLERANCE = 0.35;
    static final double MIN_TOLERANCE = 0.20;
    static final double MAX_TOLERANCE = 0.75;
    record Plan(float yaw, double distance) {}

    static double validatedTolerance(double tolerance) {
        if (!Double.isFinite(tolerance) || tolerance < MIN_TOLERANCE || tolerance > MAX_TOLERANCE)
            throw new IllegalArgumentException("C2 goal tolerance must be 0.20..0.75 blocks");
        return tolerance;
    }

    static boolean reached(double x,double y,double z,double tx,double ty,double tz,double tolerance) {
        validatedTolerance(tolerance);
        return near(x,y,z,tx,ty,tz,tolerance);
    }

    static Plan plan(double x, double y, double z, double tx, double ty, double tz) {
        if (!(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Double.isFinite(tx) && Double.isFinite(ty) && Double.isFinite(tz))) {
            throw new IllegalArgumentException("Non-finite movement target");
        }
        if (Math.abs(ty - y) > 0.65) throw new IllegalArgumentException("C2-P0 requires same-height terrain");
        double dx = tx - x, dz = tz - z;
        double d = Math.hypot(dx, dz);
        if (d < MIN_MOVE || d > MAX_MOVE) throw new IllegalArgumentException("C2-P0 movement must be 0.10..7.5 blocks");
        // Vanilla forward world axis: x=-sin(yaw), z=cos(yaw).
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new Plan(yaw, d);
    }

    static boolean near(double x, double y, double z, double tx, double ty, double tz, double radius) {
        return Double.isFinite(radius) && radius >= 0
                && (x - tx) * (x - tx) + (y - ty) * (y - ty) + (z - tz) * (z - tz) <= radius * radius;
    }
}
