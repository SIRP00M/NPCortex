package io.github.sirp00m.npcortex;

import java.util.UUID;

/** Pure Java test; does not claim Forge compatibility or Minecraft rendering. */
public final class C1P1RotationStateSelfTest {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        C1RotationState yaw180 = C1RotationState.legacy(180f);
        check(yaw180.valid(), "valid legacy orientation");
        check(yaw180.yaw() == 180f, "legacy body-facing yaw preserved");
        check(yaw180.headYaw() == 180f, "legacy head defaults to yaw");
        check(yaw180.bodyYaw() == 180f, "legacy body defaults to yaw");
        check(yaw180.pitch() == 0f, "legacy pitch defaults to zero");
        C1RotationState negative = C1RotationState.legacy(-90f);
        check(negative.headYaw() == -90f && negative.bodyYaw() == -90f,
                "negative yaw compatible");
        C1RotationState independent = C1RotationState.saved(90f, true, 12f,
                true, -45f, true, 135f);
        check(independent.valid(), "independent angles valid");
        check(independent.pitch() == 12f, "pitch restored independently");
        check(independent.headYaw() == -45f, "head yaw restored independently");
        check(independent.bodyYaw() == 135f, "body yaw restored independently");
        C1RotationState missingHead = C1RotationState.saved(180f, true, 4f,
                false, 0f, false, 0f);
        check(missingHead.headYaw() == 180f && missingHead.bodyYaw() == 180f,
                "legacy migration does not point head toward zero");
        check(missingHead.pitch() == 4f, "partial migration preserves pitch");
        check(!new C1RotationState(Float.NaN, 0, 0, 0).valid(), "NaN rejected");
        check(!new C1RotationState(0, Float.POSITIVE_INFINITY, 0, 0).valid(),
                "infinite pitch rejected");
        check(C1RotationState.saved(Float.NaN, false, 0, false, 0, false, 0).valid(),
                "corrupt legacy yaw sanitized");
        check(C1RotationState.saved(45f, true, Float.NaN,
                true, Float.POSITIVE_INFINITY, true, Float.NaN).equals(C1RotationState.legacy(45f)),
                "corrupt independent angles fall back safely");

        UUID npc = UUID.randomUUID();
        C1P0SyncState sync = new C1P0SyncState(npc, 101, UUID.randomUUID());
        check(sync.nextRotationRevision() == -1, "no rotation before client entity present");
        check(!sync.rotationApplied(1), "reject premature rotation ack");
        check(sync.profileReady() && sync.beginVerification() && sync.reportPresent(),
                "handshake gates rotation snapshot");
        check(sync.nextRotationRevision() == 1, "first snapshot revision");
        check(!sync.rotationApplied(2), "reject future revision");
        check(sync.rotationApplied(1), "ack matching revision accepted");
        check(!sync.rotationApplied(1), "duplicate ack rejected");
        check(sync.nextRotationRevision() == 2, "turn event increments revision");
        check(!sync.rotationApplied(1), "stale ack after turn rejected");
        check(sync.rotationApplied(2), "new revision accepted");
        check(sync.rotationAckRevision == 2, "latest applied revision retained");
        C1P0SyncState nextSession = new C1P0SyncState(npc, 102, UUID.randomUUID());
        check(nextSession.rotationRevision == 0 && nextSession.rotationAckRevision == 0,
                "restart gets independent rotation revision state");
        check(!nextSession.matches(sync.npcId,sync.entityId,sync.token),
                "stale token and entity ID do not match fresh session");
        System.out.println("PASS C1P1RotationStateSelfTest (" + assertions + " assertions)");
    }
}
