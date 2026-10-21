# NPCortex C2-F2 — Adaptive Follow B / Windows Gameplay Acceptance

**Candidate only.** Pure Java tests are not Minecraft Forge acceptance. Use real Windows Minecraft Java 1.20.1 Forge, Eclipse Temurin 17, and a backup copy of your test world. Do not push/merge GitHub until the owner confirms the applicable Quality Gates.

## Preparation and commands

From the extracted Full Repository root, PowerShell:

```powershell
java -version
javac -version
.\scripts\test-c2-f2-adaptive-follow-b.ps1
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

If `dev-forge.ps1` does not recognize `build`, use `cd forge-mod; .\gradlew.bat build` for the Gradle build. Keep the existing backend startup process if needed in your world, but operator Follow does not create a C0 action. Do not interpret warnings from automatic Forge TOML correction as a Follow test failure without other symptoms.

Inside a loaded Single Player world (replace the player name with the exact Minecraft username):

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui <playerName>
/npcortex c2 followstatus yui
/npcortex c1 info yui
/npcortex c2 unfollow yui
```

Observe follow phase, generation, failures, **avoided**, waypoint, motion ticket and log entries such as `WALK_TELEMETRY`. The actual command names are defined in the candidate source; tab-complete the exact player-name argument. Do not change terrain while hovering the NPC above a void without a backup.

## Functional acceptance — record PASS / FAIL / NOT TESTED

| Case | Fixture / actions | Expected observable result | Status |
|---|---|---|---|
| B1 | 20-block straight, level loaded floor; vary walk/run speed | NPC follows without repeated micro-stops or orbiting | NOT TESTED |
| B2 | Make successive 90-degree turns around a wall corner | NPC slows and turns on approved corridor; no corner clipping | NOT TESTED |
| B3 | Make zig-zag, U-turn and full circle motions | No infinite spinning / repeat waypoint loop | NOT TESTED |
| B4 | Split accessible ring from blocked top-ranked positions using a wall | Finds reachable alternate within bounded local graph or reports unreachable (no fake success) | NOT TESTED |
| B5 | Player stands still while NPC encounters changing obstruction; clear it without approaching NPC | Bounded reprobe after ~120 ticks from failure exhaustion; reacquires valid route if currently reachable | NOT TESTED |
| B6 | Keep a genuine closed enclosure while player stands still | Pauses with `PAUSED_UNREACHABLE`, no perpetual replanning or wall clipping | NOT TESTED |
| B7 | Walk in/out of stop/resume radii | Stops near 2.5 blocks; restarts at ~4 blocks (hysteresis) | NOT TESTED |
| B8 | Slab, stairs, 0.5 step, +1 jump, safe -1 descend | Previously working 3D primitives remain safe, transactional and physically verified | NOT TESTED |
| B9 | Introduce wall in front of moving NPC | Motion stops without collision penetration; alternative path or bounded pause | NOT TESTED |
| B10 | `/npcortex c2 unfollow yui` mid-walk | Only Follow-owned motion ends; no auto-resurrection | NOT TESTED |
| B11 | Combat/knockback while following and after combat | C1 owner has priority; no illegal 2nd motion owner | NOT TESTED |
| B12 | Target logout, dimension change, death; avatar despawn | No ghost follows, no force loading; valid fenced terminal/waiting state | NOT TESTED |
| B13 | Save & Quit → rejoin, inspect UUID / pose / follow | Original C1 lifecycle intact; no automatic replay of abandoned Follow | NOT TESTED |
| B14 | `/npcortex c2 navigate` and cancel/recover regression | C2 navigation C0 journal and transactional recovery behavior unaffected | NOT TESTED |
| B15 | 1/3/5/10 NPC follow at same time | Independent token/candidate avoidance memory; collect MSPT, paths/sec, collision-probe rate | NOT TESTED |

## Logs to capture for an orbit / failure

Record one screenshot before and after moving near the stuck NPC; the exact command, `followstatus` before and after, NPC/player XYZ + dimension, whether blocks/chunks changed, and last ~100 relevant log lines (`C2-F1`, `WALK_TELEMETRY`, `BLOCKED`, `PAUSED_UNREACHABLE`, `C1`). Also record whether the player held still long enough for the one bounded recovery attempt. Distinguish repeated C1 active motion from multiple C2 replans. Report actual TPS/MSPT values, not estimates.

## Release gate

`PASS` requires Java17 compile + real Forge build + owner-confirmed Windows gameplay + no regression of `Custom ServerPlayer`, collision, combat, cancellation, saved data, and navigation + acceptable measured 1/3/5/10 NPC performance. This document does **not** certify any of those in-game results.
