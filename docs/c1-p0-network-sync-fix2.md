# C1-P0 Fix #2 — Two-Phase Viewer Network Synchronization (Candidate)

**Status (2026-10-15): CODE CANDIDATE, NOT FORGE-ACCEPTED.** No NPCortex backend/API/SQLite changes. This replaces neither Minecraft's native ChunkMap tracking nor a real player connection. It is limited to the stationary C1-P0 ServerPlayer avatar and viewer-side visibility checks.

## Why this change is needed

Windows gameplay evidence: first `/npcortex c1 spawn yui` produces a visible vanilla player model. After leaving and reopening the world, server logs show `ServerLevel.addNewPlayer`, `TRACK_START`, `PROFILE_SYNC`, and `/npcortex c1 info yui` says `savedActive=true live=true` with unchanged World/Actor/Profile UUIDs, but the user cannot see the character. In the observed restart log, `TRACK_START` preceded `PROFILE_SYNC`. This suggests a PlayerInfo/ADD_PLAYER ordering bug; the real client packet ordering was not directly captured, so it remains a *hypothesis* until retesting.

## New handshake — per viewer × NPC × live entity ID

1. Native Forge `StartTracking` is still authoritative for deciding whether the viewer tracks the server NPC. It sends Vanilla PlayerInfo (no tab listing) and a `Prepare` Forge play packet with profile UUID, entity ID, and a fresh session UUID token. `onLogin` reissues handshakes for sessions registered before login.
2. Client waits for a non-null world, local player, and `ClientPacketListener.getPlayerInfo(npcUUID)`; only then emits `ProfileReady` ACK.
3. Server verifies the sender is the current real player, is in the same `ServerLevel`, is registered as a viewer, and the UUID/entityId/token match the current session. It then emits `Verify` and client checks its actual `ClientLevel` for a `RemotePlayer` whose **entity ID and UUID** match.
4. If present, client reports `Observation(present=true)` and server logs `CLIENT_ENTITY_PRESENT`. This is a report from the client instance, **not proof of a visible on-screen model or correct custom skin**.
5. If missing after 15 client ticks of verification, the client reports `present=false`; server sends a vanilla PlayerInfo update and, **only to that viewer**, one `ClientboundAddPlayerPacket` (plus available non-default EntityData) and repeats verification. At most two repairs per current session; then mark `FAILED`, never loop indefinitely.
6. Session invalidates on logout, StopTracking, server stopping, viewer dimension change, viewer respawn, despawn, or re-tracking with a fresh token. Server marks pending sessions `FAILED` after 200 server ticks without activity. Invalid/stale client replies are ignored.

**Safety:** Repair packets do NOT call `ServerLevel.addNewPlayer`, change SavedData/UUIDs, spawn an additional server Entity, move players, execute AI/actions, or mutate Minecraft blocks. They are diagnostics/recovery for client visibility *only*. The net channel is versioned `c1p0-2`; both sides must have the updated mod.

**Known risks:** Late vanilla tracking packets may race with custom repair. A client reporting presence can be untruthful or the entity can remain outside the camera frustum. No screen-level proof is possible with these packets. `CLIENT_ENTITY_FAILED`, rejected session, or Forge compilation error means **FAIL**, not automatic P0 success. More extensive vanilla pairing and multiple-client testing would be a later follow-up with fresh approval if this design fails.

## Build / test on Windows

Start from the **repository root**, NOT `forge-mod`:

```powershell
cd F:\AI\NPCortex
.\scripts\test-c1-p0-sync.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

`test-c1-p0-sync.ps1` compiles the dependency-free state machine with JDK 17 and checks handshake transitions, stale tokens/IDs, repair cap, timeout, success terminal state. It does **not** validate Forge APIs or actual packet ordering. `dev-forge.ps1 -Task build` MUST succeed before treating the deliverable as Forge-compiled.

**Use a backup test world**. No backend or Ollama is required for this scope.

### Minecraft tests in this order

1. `/npcortex c1 spawn yui` and `/npcortex c1 sync yui` — record a screenshot that Yui appears and `sync` eventually reads `PRESENT/repairs=0` (if the client had already received a valid spawn).
2. `/npcortex c1 info yui` — copy World/Actor/Profile UUIDs. Quit to Minecraft Main Menu (not `despawn`), reopen the SAME world; check a visible Yui with unchanged UUIDs. Run `/npcortex c1 sync yui`. An automatic repair may show `PRESENT/repairs=1` or `2`.
3. Exit normal tracking distance and return. Check the same Yui appears once, no duplicate name tags; `TRACK_STOP`, then `HANDSHAKE_BEGIN` should appear. `/npcortex c1 sync yui` should reach `PRESENT` again.
4. `/npcortex c1 despawn yui` => `savedActive=false`, `live=false` and visibly disappears. Quit/re-enter: Yui must NOT auto-restore. `/npcortex c1 spawn yui` should reuse saved UUIDs and show one body.
5. Repeated `/npcortex c1 spawn yui` when active must not create duplicates. If possible, use LAN second viewer and confirm viewer-specific status; if unavailable mark NOT RUN.
6. For a diagnostic failure, the server should show `CLIENT_HANDSHAKE_TIMEOUT` or `CLIENT_ENTITY_FAILED`, not endlessly send repair packets. No crash or protocol mismatches.

Logs in `forge-mod/run/logs/latest.log` (filter):

```powershell
Get-Content .\forge-mod\run\logs\latest.log -Wait -Tail 50 | Select-String 'NPCortex C1-P0|\[CHAT\]|Exception|ERROR'
```

Expected significant log entries: `HANDSHAKE_BEGIN`, `CLIENT_PREPARE`, `CLIENT_PROFILE_READY`, `PROFILE_READY`, `CLIENT_VERIFY`, `CLIENT_ENTITY_PRESENT` or `CLIENT_ENTITY_MISSING`, `CLIENT_ENTITY_REPAIR`, `CLIENT_ENTITY_FAILED`.

**Acceptance is NOT just `live=true` or `CLIENT_ENTITY_PRESENT`.** Require Forge build, visual screenshot after restart, UUID equality, re-tracking, despawn and duplicate prevention. Custom Yui PNG skin remains missing/out of Fix #2 scope.

## Test evidence for this delivery

- Isolated Java state machine: `javac --release 17` + `C1P0SyncStateSelfTest` **PASS (26 assertions)** in Linux JDK 21 compiling to Java 17 target.
- Java syntax parser: see packaging verification report (source parse only).
- Full Forge build / `runClient`: **NOT RUN / BLOCKED** here because Gradle 8.8 distribution cannot be downloaded (DNS resolution failure). Test on user's Windows PC; do not claim Forge compilation success.
- Backend regression: **PASS** — `PYTHONPATH=backend/src python -m pytest -q backend/tests` returned **185 passed, 175 subtests passed in 34.02s, exit code 0**. Source remains unchanged.
- GitHub: no operations performed.
