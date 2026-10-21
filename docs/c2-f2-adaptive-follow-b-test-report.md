# NPCortex C2-F2 Adaptive Follow B — Candidate Test Report

**Date:** 2026-10-21 (local). **Baseline:** `NPCortex_C2-F2_Fix3_A_VanillaInspiredMoveControl_FullRepository_CANDIDATE(2).zip`. **Change gate:** user-approved Approach B (Adaptive Follow, Corner-Aware Locomotion, bounded stuck/oscillation recovery). No GitHub commands performed.

## Audit / implemented behavior

- `C2FollowPlanner`: bounded directed reachability flood on an *already validated* local graph, before spending the existing 4 hierarchical candidate search slots. Preserve same-source single-node ring stop, sliding forward frontier, per-plan search budgets, and strict no chunk force-loading. Read-only blacklisted next/terminal waypoint hints, previous goal preference with a small bounded hysteresis bias, and a zero-step HOLD if already standing on a verified follow-ring node.
- `C2FollowAlternatives`: new session-private bounded avoidance of recently failed first waypoints and route goals, clear on verified physical progress or significant player target movement; never serialized as durable action history or used as proof to traverse an edge.
- `C1F2ContinuousWalk` and `C1VanillaMoveControlAdapter`: no unchecked interpolation toward the *next* edge while still en route to the current corner. Steer toward the validated current waypoint, reduce forward speed before a sharp turn and before the final endpoint, retain bounded rotation/acceleration and same C1 owned ticket; recover intermediate waypoint samples crossed between physics ticks without relaxing final C2 terminal receipt.
- `C2FollowPolicy` and `C2FollowRuntime`: retain three-failure limit. After exhaustion with a stationary target, permit one delayed fresh-world retry at 120 server ticks after terminal exhaustion, then remain paused if still unreachable; no endless replan loop. Follow status exposes `avoided` count; maintain combat/foreign ticket fences and stopOwned ownership gate.
- All changes scoped to Follow. No player entity redesign, C0 protocol alteration, Mob/PathfinderMob MoveControl, backend, new tasks, global navigation or GitHub changes.

## Execution and exact scope

- **PASS, isolated Pure-Java compile:** `javac --release 17` under OpenJDK **21.0.12.1** on Linux; Java 17 bytecode target, NOT a real Temurin 17 run.
- **PASS, isolated 7 regression suites / 416 assertions:** C2F1FollowPlanner (29), C2F1FollowPolicy (188), C2F2NaturalLocomotion (62), C2F2ContinuousFollowFix1 (41), C2F2VanillaInspiredFollowA (28), C2F2MotionSmoothingFix2 (42), C2F2AdaptiveFollowB (26). Rerun completed after final planner correction.
- The prior Fix #2 test's **one intentionally incompatible assertion** (targetZ > 0.5 from unverified corner look-ahead) was replaced with `targetZ == 0.5` asserting the new safety contract. Test still checks waypoint chaining and token fencing. This is a conscious behavior replacement, not silent omission.
- **BLOCKED (environment), actual Forge compile:** `forge-mod/./gradlew --offline compileJava --no-daemon` could not start because Gradle wrapper distribution 8.8 was not cached and `services.gradle.org` DNS failed. This does not establish Forge code compilation.
- **INCOMPLETE, unchanged Python backend regression:** attempted full `python -m unittest discover -s tests -v`, but process timed out before suite finished; no overall PASS is claimed. Backend code unchanged.
- **NOT RUN** real Minecraft ServerPlayer, client, collision, motion tickets, save/rejoin or 1/3/5/10-NPC MSPT. User Windows acceptance required.

## Remaining known risks

- Reachability is topological, not a guarantee that actual C1 Player.travel can execute a dynamic edge. Fresh graph and swept collision checks remain authoritative; remaining physical failures are retried within bounded policy.
- After two windows of three failed attempts at the same stationary target pose (including one delayed probe), Follow deliberately remains `PAUSED_UNREACHABLE` until the target moves. It never reports success for a truly inaccessible position.
- Stopping a C1 token between finished independent Follow routes may still produce a short hesitation. Persistent live-ticket rerouting is NOT part of Approach B and requires separate architecture approval.
- Full Forge API compatibility, diagonal/mixed 3D gameplay, multiplayer contention, performance, and natural motion/packet observations must be tested on Windows.
