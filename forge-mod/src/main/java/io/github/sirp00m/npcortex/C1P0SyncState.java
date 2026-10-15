package io.github.sirp00m.npcortex;

import java.util.UUID;

/** Side-independent P0 viewer handshake state; deliberately no world mutations. */
final class C1P0SyncState {
    static final int MAX_REPAIRS = 2;
    enum Phase { PROFILE_SENT, PROFILE_READY, VERIFYING, PRESENT, FAILED }

    final UUID token;
    final UUID npcId;
    final int entityId;
    Phase phase = Phase.PROFILE_SENT;
    int repairs;
    long lastActivityTick;
    // Rotation snapshots are emitted on spawn verification and on explicit turns.
    // Revisions disallow stale ACKs; no idle/per-tick packets are necessary.
    int rotationRevision;
    int rotationAckRevision;

    C1P0SyncState(UUID npcId, int entityId, UUID token) {
        this.npcId = npcId;
        this.entityId = entityId;
        this.token = token;
    }

    boolean matches(UUID npcId, int entityId, UUID token) {
        return this.npcId.equals(npcId) && this.entityId == entityId && this.token.equals(token);
    }

    boolean profileReady() {
        if (phase != Phase.PROFILE_SENT) return false;
        phase = Phase.PROFILE_READY;
        return true;
    }

    boolean beginVerification() {
        if (phase != Phase.PROFILE_READY && phase != Phase.VERIFYING) return false;
        phase = Phase.VERIFYING;
        return true;
    }

    boolean reportPresent() {
        if (phase != Phase.VERIFYING) return false;
        phase = Phase.PRESENT;
        return true;
    }

    int nextRotationRevision() {
        if (phase != Phase.PRESENT) return -1;
        return ++rotationRevision;
    }

    boolean rotationApplied(int revision) {
        if (phase != Phase.PRESENT || revision <= 0 || revision != rotationRevision
                || revision <= rotationAckRevision) return false;
        rotationAckRevision = revision;
        return true;
    }

    boolean timedOut(long currentTick, long limitTicks) {
        if (phase == Phase.PRESENT || phase == Phase.FAILED || currentTick - lastActivityTick <= limitTicks) return false;
        phase = Phase.FAILED;
        return true;
    }

    boolean requestRepair() {
        if (phase != Phase.VERIFYING) return false;
        if (repairs >= MAX_REPAIRS) {
            phase = Phase.FAILED;
            return false;
        }
        repairs++;
        return true;
    }
}
