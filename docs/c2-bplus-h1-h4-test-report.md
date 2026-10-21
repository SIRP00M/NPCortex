# NPCortex B+ H1–H4 — Implementation Test Report

Date: 2026-10-21 | Status: IMPLEMENTATION CANDIDATE — **NOT a Forge Gameplay PASS**

## Baseline & scope

Original repository: C-L1..L4 Full Locomotion Animation Controller C Full Repository Candidate.
Approved: H1 Continuous Follow Target, H2 Safe Corridor, H3 Adaptive Steering, H4 Stable Replanning & Recovery tests. Kept fixed C1 Player-based physics and exclusive Motion Owner; not converted to `PathfinderMob` or a new physics controller. All changes are local source modifications; no branch/commit/push performed.

## Implementation evidence

1. `C2FollowPlanner.java`, `C2FollowRuntime.java`: centerline-aware target scoring, previous-target preference, max 4 candidate path attempts per batch; the existing follow-ring and local radius-6 graph retained. Goal stays a verified standing node. Short lookahead targets within approved segments are sub-block coordinates.
2. `C2BPlusCorridor.java` and `C2F2SafeShortcut.java`: bound flat direct shortcuts to fully observed equal-height low-risk player-width lanes rather than their entire enclosing rectangle. Directed graph-edge safety is mandatory; any missing support/hazard/unloaded graph cell returns false.
3. `C2WaypointBridge.java`: new Follow-only live world sampled AABB corridor check for starting and extending WALK chains; retains original generic `sweptWalkClear` unchanged for static `c2 navigate` and C1 per-tick braking/hazard safety.
4. `C1F2AdaptiveSteering.java` + `C1F2ContinuousWalk.java`: look ahead .85 blocks projected on the existing approved flat leg; off-lane fallback above .42; never skips a waypoint or changes physical completion receipt; keeps Vanilla-player `travel()`.
5. Windows PowerShell isolated Java17 regression scripts updated for new dependencies. New script `scripts/test-c2-bplus-adaptive-steering.ps1` and pure Java test `C2BPlusAdaptiveSteeringSelfTest.java`.

## Executed tests

| Test | Evidence | Status |
|---|---|---|
| Pure Java `javac --release 17` | Installed OpenJDK 21, targeted Java 17 bytecode; all 16 self-test suites ran | **PASS: 2,422 assertions** |
| New B+ direct cases | Sub-block 360° lookahead, fail-closed risk/gap/corner, bounded target candidates, cancel terminal | **PASS: 18 assertions** |
| C2 Backend focused | `python -m unittest discover -s backend/tests -p 'test_c2*.py'` | **PASS: 16 tests** |
| Full Backend Suite | Log shows `Ran 201 tests in 38.814s` and `OK`; tool orchestration timed out afterward | **Log OK; clean runner exit not verified** |
| Gradle Wrapper | `./gradlew --offline classes` | **BLOCKED: DNS / Gradle 8.8 distribution unavailable** |
| Actual Forge Java compilation | Forge/Minecraft dependencies not fetched here | **NOT VERIFIED** |
| Windows 1.20.1 gameplay / multiplayer | Requires user's Forge world | **NOT VERIFIED** |
| 1 / 3 / 5 / 10-NPC MSPT | No real server tick benchmark run | **NOT VERIFIED** |

## Known limitations & risk

- The 3D graph and route endpoint receipt remain block-based intentionally: Steering is continuous **inside validated flat segments**, not a new 3D continuous trajectory optimizer. Some sharp corners and motion-boundary stops remain unavoidable without another audited scope.
- Every independent route terminal still goes through existing C1 stop/settling behavior; no cross-token velocity handoff was added.
- A live hazard that changes during motion may trigger safe stopping/replan. The new swept Follow corridor proof is not a substitute for C1 tick collision checks.
- Centerline target ranking uses the existing four candidate A* probes at most; worst-case CPU overhead versus early-success original rolling selection needs real 10-NPC MSPT benchmarking.
- `sweptFollowCorridorClear` consumes up to 48 world samples per inspected Follow segment, subject to exact chunk, collision and border results; no automatic chunk loading.
- Static-navigation collision verification deliberately stays unchanged.

## Windows commands

```powershell
.\scripts\test-c2-bplus-adaptive-steering.ps1
.\scripts\test-c1-full-locomotion-c.ps1
.\scripts\test-c2-turn-aware-b.ps1
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Do not mark build/gameplay/multiplayer/performance as PASS unless explicitly tested and reported. Refer to `docs/c2-bplus-h1-h4-windows-checklist.md`.
