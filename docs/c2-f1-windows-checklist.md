# C2-F1 — Windows Forge / Minecraft Acceptance Checklist

Baseline: Sprint 4 Fix #1 Full Repository; Minecraft Forge 1.20.1, JDK 17, Windows.

## Build

```powershell
cd C:\path\to\NPCortex
.\scripts\test-c2-f1-follow.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**Backend/Ollama not necessary for operator-only Follow.** If Yui still has a `CANCEL_PENDING`/`INTERRUPTED` C0 journal, use the existing Backend and ensure terminal reconciliation **before** Follow: `/npcortex c2 status yui`. Do not unsafe-reset to bypass a pending action.

Obtain your *real Minecraft username* using `/list` or the player list; type it literally (without `< >`).

```mcfunction
/npcortex c1 info yui
/npcortex c2 follow yui YourMinecraftName
/npcortex c2 followstatus yui
```

## Suggested tests (report separately)

1. **START/HOLD:** stand within 2.5 blocks of Yui, follow is accepted and does not repeatedly oscillate or force a movement toward the player's exact collision cell.
2. **RESUME/CHASE:** walk 4–6 blocks away on a loaded flat surface; Yui begins following; stop and confirm she also stops at safe distance. Repeat walking without issuing `follow` again; observe generation/cooldown via `followstatus`.
3. **3D TRAVERSAL:** reuse the safe one-block stone platform fixture that already passed S3; move up/down within local six-block range. Watch Vanilla jump/landing and ensure Yui follows safely. If the area is too far, status may pause; do not infer global-range follow.
4. **OBSTACLE:** insert/remove a wall while player moves, with a valid detour; observe bounded replanning, no teleport and no endless retry.
5. **NO PATH:** surround target area, confirm bounded `PAUSED_UNREACHABLE`, never false completion or repeated heavy planning every tick; move target >=1.5 blocks to permit a fresh bounded attempt.
6. **CANCEL / OWNERSHIP:** while following, `/npcortex c2 navigate yui 83 69 -60` (adjust to safe test terrain) must refuse with Follow ownership; `/npcortex c2 cancel yui` or `/npcortex c2 unfollow yui` stops Follow without C0 Goal creation. Wait for C1 motion to settle, then issue normal Navigate. Ensure no manual `/npcortex c2 reset` needed if C0 journal is terminal.
7. **C1 FOREIGN MOVE:** manually stop C1 motion during follow; Follow should not auto-restart the stopped command. Explicit `c2 unfollow` works safely.
8. **PLAYER LOGOUT / REJOIN:** multiplayer two-player test; target leaving or changing dimension pauses without movement; rejoin in same loaded dimension may resume. Verify no cross-dimension teleport. Singleplayer alone cannot prove this case.
9. **SAVE/QUIT:** while following, Save & Quit, reopen same world, and run `/npcortex c2 followstatus yui`. Follow must be inactive, e.g. `STOPPED_AFTER_RESTART`; a new explicit `follow` can be issued after C1 settles and C0 Journal is safe.
10. **MULTI-NPC / PERFORMANCE:** repeat with 1/3/5/10 real NPCs, measure Forge *server* MSPT (not pure Java test time), planning latency, failure count, no shared route/ticket/target mix-up and correct stop per actor. Do not claim PASS without recorded readings.

**Requested final status:** each item PASS / FAIL / NOT TESTED plus commands, failure reason and log/screenshot when available. If the new mod fails to build or crashes, do not force a reset; provide Gradle/Forge log for source audit. No GitHub operations automatically.
