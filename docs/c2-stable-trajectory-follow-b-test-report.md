# NPCortex — Stable Trajectory Follow B — Test Report (2026-10-21)

**Base:** `NPCortex_C-TN_SmoothFollowHardening_C_S1-S4_FullRepository_CANDIDATE.zip`. This is an **Implementation Candidate**, not Minecraft acceptance.

## Approved scope and source changes

- `C1F2AdaptiveSteering`: deadband small (<0.12 block) cross-track errors on an already certified flat segment; damp larger corrections while keeping bounded preview and direct certified endpoint aim near the end. Out-of-lane still falls back to committed waypoint, no path rewrite.
- `C1F2TrajectoryArrival` (new): reduce forward input as NPC approaches an observed waypoint, without moving any avatar or modifying C1/C2 receipts. Intermediate swept-point helper only accepts an **observed** displacement <= 0.7 block; final completion still depends on C1 <= 0.28 physical proximity and C2 <= 0.35 receipt verification.
- `C1F2ContinuousWalk`: use the controlled arrival limiter and factored observed crossing helper. No change to ticket caps, ownership, vertical physics, or terminal return semantics.
- `C2FollowAlternatives` + `C2FollowRuntime`: retain *only a preference hint* for an ahead, local, verified prior goal across small player relocations; never reuse stale route or failed edge, and clear the preference when behind/passed/outside local range. Fresh graph planning remains mandatory.
- `C2StableTrajectoryFollowBSelfTest` (new): endpoint overshoot, steering symmetry, lateral deadband, bounded correction, forward braking, cancel, physical receipt, continuity.

## Executed tests

- `javac --release 17` of pure Java subset: **PASS** on OpenJDK 21 with target release 17.
- `C2StableTrajectoryFollowBSelfTest`: **PASS**, 23 assertions.
- All 20 baseline pure-Java suites: **PASS**.
- **Total 21/21 suites, 2,614 assertions PASS** (includes baseline assertions; not Forge build).
- `PYTHONPATH=src python -m unittest discover -s tests -p 'test_c2_*.py' -v`: **16/16 PASS**.
- `./gradlew compileJava --offline`: **BLOCKED** because Gradle wrapper tries to fetch 8.8 and raises `UnknownHostException: services.gradle.org`. No Forge API compilation was obtained.
- No Forge gameplay, Client visual smoothness, dedicated server or 1/3/5/10 NPC MSPT measured.

## Safety and compatibility boundaries

- No `Player.travel()` changes; no Mob pathfinder; no additional physics owner.
- No changes to `/npcortex c2 navigate`, A*, nav collision probing, 3D landing, combat or cancellation code.
- Steering only advises within certified segment and must still pass the C1 swept collision/hazard/support/loaded-chunk checks every physical step.
- Final waypoint completion **never** inferred from passed-along-track state alone: actual physical distance is required. This may still require a small corrective turn if overshot outside 0.28 blocks, which is safer than falsely completing a route.

## Windows Quality Gate (PENDING)

1. `scripts/test-c2-stable-trajectory-follow-b.ps1`, JDK 17, then `scripts/dev-forge.ps1 -Task build`.
2. 50-block cardinal and diagonal straight following: count visible zigzag oscillations and look for reduced corrections against S1–S4 baseline.
3. Approaching player stop threshold: no repeated left/right orbiting; report any overshoot or waypoint BLOCKED.
4. Wall/corner, grass, slab/stair, +1 jump, -1 descend, partial support, player reposition during follow, cancel mid-turn/jump, combat and save/rejoin.
5. Multi-NPC 1/3/5/10 MSPT, token isolation and ownership checks. Record actual tests instead of inferring PASS from the pure Java suite.

See `docs/c2-stable-trajectory-follow-b-windows-checklist.md` and `PENDING_TESTS.txt`.
