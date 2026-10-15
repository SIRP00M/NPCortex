package io.github.sirp00m.npcortex;

/**
 * Independent authoritative P1-A angles. Side-independent to allow pure Java
 * migration / replay tests; packets and NBT are adapters rather than owners.
 */
record C1RotationState(float yaw, float pitch, float headYaw, float bodyYaw) {
    static C1RotationState legacy(float yaw) {
        float safe = Float.isFinite(yaw) ? yaw : 0.0f;
        return new C1RotationState(safe, 0.0f, safe, safe);
    }

    boolean valid() {
        return Float.isFinite(yaw) && Float.isFinite(pitch)
                && Float.isFinite(headYaw) && Float.isFinite(bodyYaw);
    }

    /** A legacy save has only yaw; missing head/body intentionally follow yaw. */
    static C1RotationState saved(float yaw, boolean hasPitch, float pitch,
                                 boolean hasHead, float head, boolean hasBody, float body) {
        C1RotationState fallback = legacy(yaw);
        float safePitch = hasPitch && Float.isFinite(pitch) ? pitch : 0.0f;
        float safeHead = hasHead && Float.isFinite(head) ? head : fallback.headYaw;
        float safeBody = hasBody && Float.isFinite(body) ? body : fallback.bodyYaw;
        return new C1RotationState(fallback.yaw, safePitch, safeHead, safeBody);
    }
}
