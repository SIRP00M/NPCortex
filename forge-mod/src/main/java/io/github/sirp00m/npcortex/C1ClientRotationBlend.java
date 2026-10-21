package io.github.sirp00m.npcortex;

/** C-L4: viewer-local presentation easing for revisioned C1 snapshots.
 * Does not own movement yaw, world state, tickets, or physics. */
final class C1ClientRotationBlend {
    record Angles(float head, float body) { }
    private float head, body, targetHead, targetBody;
    private boolean initialized;

    void accept(float headTarget, float bodyTarget) {
        if (!Float.isFinite(headTarget) || !Float.isFinite(bodyTarget))
            throw new IllegalArgumentException("Invalid snapshot pose");
        targetHead = C1LocomotionPresentation.wrap(headTarget);
        targetBody = C1LocomotionPresentation.wrap(bodyTarget);
        if (!initialized) {
            head = targetHead;
            body = targetBody;
            initialized = true;
        }
    }

    Angles advance() {
        if (!initialized) throw new IllegalStateException("No valid snapshot");
        // Two server ticks between active follow snapshots. Ease each render
        // tick rather than abruptly forcing head/body on packet receipt.
        head = C1LocomotionPresentation.approach(head, targetHead, 16f);
        body = C1LocomotionPresentation.approach(body, targetBody, 13f);
        return new Angles(head, body);
    }

    boolean ready() { return initialized; }
}
