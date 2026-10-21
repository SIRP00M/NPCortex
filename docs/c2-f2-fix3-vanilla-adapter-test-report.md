# NPCortex C2-F2 Fix #3 — Vanilla-inspired MoveControl Adapter A — Test Report

**Candidate only.** Baseline: `NPCortex_C2-F2_Fix2_C_PlayerLikeMotionSmoothing_FullRepository_CANDIDATE.zip`.

## Source audit (2026-10-21)

User observed Follow motion visually jerky despite verified multi-waypoint walks. Reported Fix #2 `WALK_TELEMETRY` frequently had `extensions=0`, multiple short `COMPLETE` tickets and two cancellations. Code audit confirmed the Follow WALK controller used direct cosine input, a sharp stop on large yaw, and C2 Follow deferred new plans even after an already verified route terminal. C1 zeroes horizontal velocity **at the end of each independent movement ticket**. Do not confuse this with the entire problem: client rendering/position packets still need real-game evidence.

## Implemented, limited to approved Option A

- New pure Java `C1VanillaMoveControlAdapter`: per-NPC, bounded yaw rotlerp (18° normal, 32° for large >75° heading changes), desired forward speed from heading error, bounded acceleration/deceleration per tick, terminal-approach braking, immediate zero forward for unsafe >88° orientation. No Mob class or `MoveControl` instance is created. No additional entity, world request, path planner, network integration, physics owner or background controller.
- `C1F2ContinuousWalk` delegates **input shaping only** to this adapter. Existing authoritative C1 Vanilla `Player.travel`, fresh per-tick ground/headroom/collision sweep, terrain verification and owned-ticket lifecycle remain untouched.
- `C2FollowPolicy.afterVerifiedRoute(tick)` permits a new Follow-only local plan after 2 server ticks **only after an already verified C1 route or segment terminal**. Failed/missing/foreign tokens and collision retry remain on the existing 20-tick backoff. Maximum 1 plan batch per global server tick remains.
- All C0 static `navigate`, vertical traversal, combat force handling, cancel/stop action ownership, journal recovery, backend and database contracts unchanged.

## Local test evidence

| Test | Result |
|---|---|
| Pure-Java 17-target compile on JDK 21 + test mains | **PASS: 30/30 self-test suites, 5,096 output-reported assertions** |
| New Adapter A test (rotlerp, acceleration, stop, 10 NPC independent state, safe replan scheduling) | **PASS: 28 assertions**, included in total |
| Previous F2 Fix #1 and Fix #2 dedicated contract tests | **PASS: 41 + 42 assertions**, included in total |
| Backend C2-focused unit tests (`test_c2*.py`) | **PASS: 16 tests / 0.883 sec** |
| Backend full suite | **NOT VERIFIED this run**: full-suite run stalled and was terminated after 120s; this does not imply a regression, Python/backend code unchanged |
| Real Gradle Forge compile | **BLOCKED**: wrapper attempted download with no network access; Forge sources not compiled locally |
| Windows Minecraft gameplay, client smoothness, real performance | **PENDING** |

## Known limitations

- This is a small **Vanilla-inspired adapter**, not Vanilla Mob `MoveControl`, `GroundPathNavigation`, or PathfinderMob inheritance. There is no guarantee of visual parity with Minecraft follower mobs.
- Independent Follow local routes still terminate C1 motion tickets, clearing velocity; this adapter and verified-terminal replan timing can reduce but **cannot entirely remove cross-route stop/start**. A persistent cross-route ticket would require separate approved architecture work.
- `WALK_TELEMETRY` is server-side only, not a measure of remote-client render interpolation; `nearStationary=1` may include the first uninitialized delta sample.
- Existing 3D Navigation Full Quality Gate items remain pending. Do not claim Forge Build / Windows Gameplay / 1-3-5-10 NPC MSPT has passed.

## Scope / Git

No Backend/schema changes, no PathfinderMob/Helper Mob, no teleport, no force-loaded/global pathfinding, no Qwen/interaction skills, no GitHub operations. One `CHANGELOG.md` and existing `PENDING_TESTS.txt` retained.
