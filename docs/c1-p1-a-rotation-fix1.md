# NPCortex C1-P1-A Rotation Fix #1 — Full Rotation State Sync (Option C)

**Status: Implementation Candidate — requires Windows Forge Build + Minecraft verification.**

## Scope

- Preserve four independent server-authoritative angles (`yaw`, `pitch`, `headYaw`, `bodyYaw`) in overworld SavedData; no UUID or world key changes.
- **Legacy migration:** existing actor records only contain `yaw`, so head/body default to that saved yaw, and pitch to zero. This prevents the head from returning to world angle zero on restore. Missing/corrupt angle values are sanitized, never used as packets.
- Restore all angles to the server-side Custom `ServerPlayer` *before* adding to ServerLevel.
- Extend the existing two-phase Forge networking handshake: **after** a client confirms `RemotePlayer` presence, send a session-scoped `RotationSnapshot`. Client validates UUID, live Entity ID, handshake token and revision, applies all angles and returns `RotationApplied` ACK. Explicit operator turns send another rotation snapshot only to PRESENT viewers.
- No periodic rotation polling, no per-tick extra packets; no changes to Backend, Qwen, motion physics, navigation, mining, inventory, skin or combat.
- Rotation snapshots are extra synchronization atop Vanilla ChunkMap; they do not replace player spawn/tracking packets.

## Windows tests

From repository root, with JDK 17:

```powershell
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

In a backed-up **Single Player** test World (cheats enabled):

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 turn yui 90
/npcortex c1 rotation yui
/npcortex c1 sync yui
```

1. Verify on-screen head AND body facing 90 degrees. Exit to menu **without despawn**, enter world again. Both head and body must still face the same saved angle. Note `ROTATION_SYNC` and `CLIENT_ROTATION_APPLIED` / `ROTATION_APPLIED` logs after `CLIENT_ENTITY_PRESENT`.
2. Repeat at `180`, `-90`, and `0`. For each, compare `savedRotation` and `liveRotation` via `/npcortex c1 rotation yui`, as well as what the Client renders. If `repairs=1`, verify head/body still match after repair.
3. Walk beyond tracking range and return; check the avatar appears once, faces correctly and `/npcortex c1 sync yui` shows PRESENT.
4. Recheck P1 movement: `settle`, `move ... forward 3`, `jump`, `stop`, and solid-block collision. Exit/rejoin and verify position and unchanged World/Actor/Profile UUIDs.
5. Run `/npcortex c1 despawn yui` and rejoin: no auto-respawn. A subsequent `spawn` may intentionally reset orientation to the commander's facing (this is pre-existing spawn command semantics).
6. Check no constant ROTATION_SYNC spam when avatar stands idle. Every explicit turn or verified track creates at most one RotationSnapshot per viewer/valid session (duplicate HANDSHAKE_BEGIN can create separate sessions; older token is rejected).

## Acceptance evidence

- Windows Forge Build must exit 0 and `runClient` must complete without crashes.
- Capture `latest.log` from both initial spawn and restart/re-track, including `CLIENT_ENTITY_PRESENT`, `ROTATION_SYNC` / `CLIENT_ROTATION_APPLIED` and `ROTATION_APPLIED`.
- Screen observation verifies rendering (ACK reports application, **not** screenshot proof).
- Save/Load has been tested only on actual Minecraft after the user runs this checklist. Pure Java self-tests (26 P0 + 29 Motion + 29 Rotation assertions) and the isolated SavedData/NBT test-double harness (12 assertions) are not Forge API tests. Backend pytest showed 185 tests and 175 subtests passed but no confirmed normal process exit.

## Risk notes

- Vanilla entity tracking may also interpolate yaw/head orientation. P1-A snapshots are applied once per verified spawn and explicit turn, not continuously. Re-evaluate only if visual rotation gets overridden after another server tracking update.
- The synthetic ServerPlayer deliberately does not tick through Vanilla PlayerList lifecycle, which remains out of scope.
- Multiplayer LAN, third-party skins and mod interactions still require their own Acceptance.
