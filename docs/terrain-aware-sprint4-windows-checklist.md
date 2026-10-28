# NPCortex Sprint 4 — Windows Forge Final Acceptance Checklist

**Environment:** Windows, Eclipse Temurin JDK 17, Minecraft Forge 1.20.1,
local Backend online when using `c2 navigate`. Only an expendable Singleplayer
world should be used for terrain mutation. This Candidate has **not** passed
Forge Build or live Sprint 4 acceptance yet.

## 0. Run regression + start services

```powershell
cd F:\AI\NPCortex
.\scripts\test-terrain-s4.ps1
.\scripts\dev-forge.ps1 -Task build
# In a SECOND PowerShell:
.\scripts\dev-backend.ps1
# In a THIRD PowerShell:
.\scripts\dev-forge.ps1 -Task runClient
```

No GitHub push. Keep logs, screenshots, actual goal/action IDs and /c2 status.

## 1. Existing baseline evidence (NO unnecessary re-testing)

- **USER CONFIRMED PASS:** Sprint 3 automatic JUMP_UP (+1.0) to an elevated
  stone platform, then automatic SAFE_DESCEND (-1.0) to the lower floor.
- **USER CONFIRMED PASS:** Sprint 2 operator-only STEP_UP (+0.5) and JUMP_UP
  (+1.0), SAFE_DESCEND (-1.0) on a corrected 2-wide landing fixture.
- **NOT VERIFIED:** Sprint 2 STEP_DOWN (-0.5); automatic Slab/Stairs; full mixed
  terrain route. Don't promote these to PASS by assumption.

## 2. Sprint 4 new acceptance matrix

Use `/npcortex c1 info yui`, `/npcortex c2 navhier yui <x> <footY> <z>`,
`/npcortex c2 navigate yui <x> <footY> <z>`,
`/npcortex c2 status yui` and `/npcortex c2 cancel yui`.
Use loaded, visible nearby terrain (C2 sensor radius 6), and clear player
bounding-box footprint, including the neighboring columns. All coordinates
below are examples; match your current Yui feet Y and choose a disposable test
fixture. When testing dynamic changes, put an obstacle on an **unvisited** edge
while Yui is actually moving, leaving an alternative route inside scan bounds.

| ID | Scenario | Expected evidence | Status |
| --- | --- | --- | --- |
| S4-01 | 3D route + obstruction ahead, safe detour remains | `MOVING` -> `REPLANNING` -> `MOVING`; generation advances; same C0 goal/action; grounded terminal `COMPLETED` verified | NOT TESTED |
| S4-02 | Destination landing removed/raised **during** jump | Never `COMPLETED` on wrong surface; C1 terminal -> wait for safe ground -> new graph/replan or explicit bounded failure | NOT TESTED |
| S4-03 | Cancel during jump | C1 stops input but gravity settles; `CANCEL_PENDING` until C0 terminal; next navigate without reset | NOT TESTED |
| S4-04 | Combat knockback during active traversal | Vanilla impulse preserved; never steal C1 token; safe replan AFTER terminal/settling or honest failure | NOT TESTED |
| S4-05 | Kill/despawn/chunk unload mid-goal | Fail closed, never teleport/force-load/keep issuing tickets | NOT TESTED |
| S4-06 | Fully sealed route, no detour | Bounded attempts <=3, terminal failure; never false `verified=true` | NOT TESTED |
| S4-07 | Save/Quit during MOVING/REPLANNING, rejoin | No old motion replay; journal `CANCEL_PENDING`; C0 terminal acknowledgement before new navigation | NOT TESTED |
| S4-08 | Turn Backend off after cancel, then back on | No reset allowed during `CANCEL_PENDING`; automatic C0 reconciliation once Backend returns | NOT TESTED |
| S4-09 | STEP_DOWN -0.5 on exact landing fixture | Grounded at target footY; precise terminal receipt; no false path | NOT TESTED |
| S4-10 | Slab/Stairs orientations and mixed 3D route | Correct typed edges, landing collision, final verified | NOT TESTED |
| S4-11 | Flat navigation and prior online cancel recovery | No C1 regression or manual-reset requirement | NOT TESTED |
| S4-12 | Real unloaded-chunk/world-border safety | No forced chunk load; unknown isn't walkable | NOT TESTED |

**Test fixtures:** For an existing level-ground Yui at `footY=69`, a known
Sprint 3 local test area used the following blocks (these commands overwrite
world terrain; only run in a disposable world if Yui is still in that area):

```mcfunction
/fill 75 69 -68 79 73 -62 minecraft:air
/fill 75 68 -68 79 68 -62 minecraft:stone
/fill 75 69 -64 79 69 -62 minecraft:stone
/npcortex c2 navhier yui 76 70 -63
/npcortex c2 navigate yui 76 70 -63
/npcortex c2 status yui
```

For a new obstacle trial, use a longer nearby platform / safe walking lane;
measure its planned path, then `/setblock` a block at a not-yet-crossed edge.
Do not blindly reuse fixed coordinates if Yui's current location has changed.

## 3. Performance — 1, 3, 5, 10 NPCs

| Count | Min/avg/peak server MSPT | Path latency ms | Replans/goal | New/unexpected C1 tickets | Result |
| --- | --- | --- | --- | --- | --- |
| 1 | — | — | — | — | NOT TESTED |
| 3 | — | — | — | — | NOT TESTED |
| 5 | — | — | — | — | NOT TESTED |
| 10 | — | — | — | — | NOT TESTED |

Keep all NPCs within the same known loaded area and launch similarly sized
navigation tasks. Capture real server tick metrics with available Forge profiler
or Spark if installed (do not assume Spark is installed), plus `navhier` elapsed
and `c2 status` generations. Record raw measurements and failure rate. A pure
JVM benchmark or single `navscan` time is **not** server MSPT evidence.

## 4. Quality decision

Mark each row exactly PASS / FAIL / NOT TESTED with command, screenshot/log,
fixture coordinates, Minecraft and Backend versions. Core gameplay may be
accepted independently, but do **not** certify Full Final Quality Gate until
required safety/restart/recovery/performance evidence exists. Retain all
unverified cases in PENDING_TESTS.txt. User handles all GitHub operations.
