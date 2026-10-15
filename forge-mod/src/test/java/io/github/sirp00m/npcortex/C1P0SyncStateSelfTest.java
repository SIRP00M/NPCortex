package io.github.sirp00m.npcortex;

import java.util.UUID;

/** Dependency-free state-machine test. Run with scripts/test-c1-p0-sync.ps1 on Windows JDK 17. */
public final class C1P0SyncStateSelfTest {
    private static int assertions;
    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        UUID npc = UUID.randomUUID(), token = UUID.randomUUID();
        C1P0SyncState state = new C1P0SyncState(npc, 3075, token);
        state.lastActivityTick = 100;
        check(state.phase == C1P0SyncState.Phase.PROFILE_SENT, "starts profile pending");
        check(state.matches(npc,3075,token), "valid viewer session identity");
        check(!state.matches(npc,3075,UUID.randomUUID()), "reject stale/wrong token");
        check(!state.matches(npc,3076,token), "reject old entity ID after restore");
        check(!state.matches(UUID.randomUUID(),3075,token), "reject unrelated NPC");
        check(!state.reportPresent(), "cannot claim client present before ready/verify");
        check(!state.requestRepair(), "cannot repair before verify");
        check(state.profileReady(), "profile ack accepted once");
        check(!state.profileReady(), "duplicate profile ack ignored");
        check(state.beginVerification(), "verification begins");
        check(state.requestRepair(), "first repair allowed");
        check(state.repairs==1, "repair counted");
        check(state.requestRepair(), "second repair allowed");
        check(!state.requestRepair(), "third repair rejected");
        check(state.phase==C1P0SyncState.Phase.FAILED, "hard failure after cap");
        check(!state.beginVerification(), "failed session is terminal");
        check(!state.reportPresent(), "failed cannot be rescued by stale ack");
        C1P0SyncState fresh = new C1P0SyncState(npc,3946,UUID.randomUUID());
        check(!fresh.matches(npc,3075,token), "restart/retacking invalidates old session");
        check(fresh.profileReady() && fresh.beginVerification(), "fresh session usable");
        check(fresh.reportPresent(), "client entity registered");
        check(fresh.phase==C1P0SyncState.Phase.PRESENT, "success terminal");
        check(!fresh.requestRepair(), "do not repair already present NPC");
        check(!fresh.timedOut(1000, 200), "success cannot expire");
        C1P0SyncState hung = new C1P0SyncState(npc,4000,UUID.randomUUID());
        hung.lastActivityTick = 200;
        check(!hung.timedOut(400, 200), "timeout threshold exclusive");
        check(hung.timedOut(401, 200), "orphan handshake times out");
        check(!hung.profileReady(), "timed-out session ignores late ready");
        System.out.println("PASS C1P0SyncStateSelfTest (" + assertions + " assertions)");
    }
}
