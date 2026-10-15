# C1-P0 — ServerPlayer Identity / Spawn / Tracking Feasibility Candidate

> **Status: IMPLEMENTED AS AN UNVERIFIED PROTOTYPE. NOT ACCEPTED.** 2026-10-15.
> **Fix #2 candidate:** two-phase client/server viewer synchronization and bounded repair are now included. See [`c1-p0-network-sync-fix2.md`](c1-p0-network-sync-fix2.md) for the updated protocol, diagnostics, Windows commands and acceptance. Earlier test claims below describe the original P0 candidate, not a pass of Fix #2.
> Requires an actual **Windows JDK 17 + Forge 47.4.10** compilation and
> Minecraft 1.20.1 world/session tests before P0 can pass. No backend changes.

## Exact scope and architecture

This is **C**: `C1PlayerAvatar extends ServerPlayer`, not `FakePlayer`,
`PathfinderMob`, or a proxy render entity. `C1P0Runtime` runs on the server
thread via Forge events and operator-only test commands. No player-like
movements, attacks, inventory changes, block mutation, AI decisions, C0 API
calls, autonomous loops, virtual client connection or TaCZ integration.

### Files

- `C1IdentityData.java`: Overworld `SavedData` named `npcortex_c1_p0.dat`;
  durable `world_uuid`, separate `actor_uuid`, `profile_uuid`, stable
  `profile_name`, dimension, static position/yaw, active/deactivated state.
- `C1PlayerAvatar.java`: intentionally stationary `ServerPlayer` subclass.
  Its vanilla `tick`/`doTick` are explicitly suppressed because the passive
  listener is NOT a real client connection. It is invulnerable during P0 only.
  It is not included in the tab list, but retains PlayerInfo for appearance.
- `C1PassivePacketListener.java`: synthetic `ServerGamePacketListenerImpl`
  with an unconnected `Connection(PacketFlow.SERVERBOUND)`. Outbound sends
  are deliberately dropped. The avatar is NOT logged in through PlayerList;
  this is only a safeguard against `connection == null` in tracking code.
- `C1P0Runtime.java`: adds to `ServerLevel` via `addNewPlayer`, sends vanilla
  `ClientboundPlayerInfoUpdatePacket` to real clients before spawn, resends
  profile on login/respawn/dimension changes and StartTracking, sends
  `ClientboundPlayerInfoRemovePacket` on explicit despawn. Keeping PlayerInfo
  across StopTracking is intentional: the next vanilla add-entity packet may
  precede Forge's StartTracking event.
- `NPCortex.java`: registers events and explicit command surface only;
  existing `/npcortex ask` and `/npcortex status` are unchanged.

The NPC is **not** inserted into `PlayerList` as a logged-in person.
This is a high-risk premise of the proof, not a compatibility guarantee.

## Commands (enable cheats; in a test/backup world)

From a real player with permission level 2:

```
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c1 list
/npcortex c1 despawn yui
```

`spawn` creates a static avatar near the operator; using an existing name
reactivates the *same* actor UUID, GameProfile UUID and synthetic profile name.
`despawn` deactivates but **does not delete identity**. Saved ACTIVE avatars
are restored on `ServerStartedEvent` in the recorded dimension/position.
The message `Spawn attempted` is not proof that the client rendered it.

The profile name is a collision-resistant `N<8-character-name>_<6-hex>`
(up to 16 characters). Do not use this synthetic GameProfile as an authenticated
human identity. Actor UUID is currently not registered with the Python C0 API.

## P0 manual acceptance checks (record evidence for EACH)

1. **Compile / launch**: On Windows, use JDK 17. From the repository root
   (`F:\AI\NPCortex`), run `./scripts/dev-forge.ps1 -Task build` and retain
   the complete output. After Build succeeds, launch with
   `./scripts/dev-forge.ps1 -Task runClient` on Minecraft 1.20.1 / Forge 47.4.10.
   These commands invoke the existing `forge-mod/gradlew.bat` wrapper internally.
   If PowerShell is currently in `forge-mod`, run `cd ..` first. A prior
   `SavedData.Factory` compile error was fixed by using the documented
   three-argument `computeIfAbsent(load, create, key)` API; the Windows Forge
   Build still needs to be rerun and verified.
2. **First spawn**: In a *backup test world* with cheats enabled,
   run `... spawn yui`. Capture client screenshot and `latest.log`.
   Confirm server logs `ServerLevel.addNewPlayer returned` and distinguish
   this from *actual rendered body* in the game. The avatar should be still.
3. **Client PlayerInfo / model**: Verify human player model, vanilla skin
   fallback and arm/body visibility; record whether slim or classic is used.
   **Custom PNG skins are not implemented in this candidate.** This is a
   known P0 gap, not a passed skin test.
4. **Tracking distance**: Walk far enough that avatar disappears; return.
   Verify it reappears without a relog, missing skin, duplicate entity or
   client error. Record `StartTracking`/client log evidence if available.
5. **Late join**: Open the same world to LAN for a *second client* if possible,
   join with avatar already spawned; verify the second client sees it. If no
   second client is available, mark this test **NOT RUN**, not PASS.
6. **Save/restart**: Record `... info yui` IDs and position; leave and
   reopen the SAME world. Verify identical world/actor/profile IDs and
   visible avatar without running `spawn` again.
7. **Explicit despawn + reactivation**: Run `despawn yui`; confirm entity
   disappears and is NOT restored on restart. `spawn yui` must reactivate
   the SAME UUIDs and must not produce a second entity.
8. **Name collision**: Try `spawn yui` twice without despawn;
   ensure duplicate is rejected and no multiple same-UUID actors appear.
9. **Dimension viewer**: Change the REAL test player's dimension and return;
   ensure the avatar remains in its original dimension and can be rendered
   on return. C1 avatar dimension transfer is **P1, not P0**.
10. **Server safety**: Close the world/reopen several times; inspect crash
    reports, Forge Event errors, console, server chunk loads, player list/tab
    entries, and verify `/npcortex ask`/`status` still register.

**Accept only if**: Java/Forge build succeeds, an actual NPC appears and is
tracked correctly by the client, UUIDs survive restart, repeated spawn and
explicit despawn are correct, no unexpected crashes and observed limits are
reported. Default skin alone does not qualify as *custom skin support*.

## Important technical risks / limitations

- `ServerLevel.addNewPlayer` and player-tracker pathways may expect a
  **fully working** player connection. The passive listener avoids a null
  reference but does not implement the login handshake. A hard crash or
  absent appearance is a **failed feasibility result**; do NOT patch blindly
  by inserting a fake human into `PlayerList`.
- Default Vanilla skin fallback depends on GameProfile UUID/client data.
  No `skins/yui.png` loading or slim/classic selector is wired up yet.
- Single-player integrated server is the ONLY scoped gameplay environment.
  Dedicated server, LAN peer tracking and third-party mod loading can
  inform the audit but are not a release-grade compatibility guarantee.
- A successful `ServerLevel.addNewPlayer` call does not prove packets reached
  a client or that a `RemotePlayer` was instantiated. Client screenshot/log
  required.
- A static no-tick ServerPlayer is **not** functionally equivalent to a
  real, connection-backed player. Movement, death, inventory, modded events
  and dimension *actor* travel are intentionally excluded.
- Operator may spawn into another dimension later; SavedData keeps the
  original dimension and position until an explicit `spawn` command updates
  them. Do not use this to claim C0 actor migration capability.
- If the game crashes between new identity persistence and entity insertion,
  SavedData's ACTIVE desired state can be retried on next server start. This
  makes no world mutation and is only an *idempotent spawn-intent test*.
  C0 Action Execution Journal and exactly-once recovery are NOT part of P0.
- This is intentionally a **feasibility candidate**, not a verified runtime.
  If any fundamental G1 tracking gate fails, stop and return to C1 audit.

## Verification in delivery environment (2026-10-15)

- Forge `bash ./gradlew --offline --no-daemon compileJava` was **attempted,
  but BLOCKED** before compilation: wrapper Gradle 8.8 is not cached;
  `services.gradle.org` DNS lookup failed. Java toolchain available here is
  OpenJDK 21, not required user JDK 17.
- Python backend 185 tests and 175 subtests reported PASS, but the full
  `pytest` process stayed alive after the summary and was terminated by the
  outer timeout (exit not clean). Record this as **test assertions pass,
  process shutdown not clean**; no Python source modifications were made.
- No Minecraft gameplay, client rendering, LAN or restart tests have been
  executed here. All G0/G1/G2 P0 gameplay gates remain **PENDING**.

## Ownership

All GitHub operations, Windows builds and Minecraft gameplay tests remain
with the user. No backend schema/API edits, no new release designation,
the historical changelog entries were consolidated into the root `CHANGELOG.md` only; no other code or backend contracts were changed by the changelog cleanup.
