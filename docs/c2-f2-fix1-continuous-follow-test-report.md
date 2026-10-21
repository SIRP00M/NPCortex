# C2-F2 Fix #1 — Test report (2026-10-20, Implementation Candidate)

## Proven in this environment

- **PASS**: `javac --release 17` isolated pure Java **28 self-test suites / 5,026 assertions** (executed on JDK 21, bytecode targets Java 17). New 41-assertion continuous follow self-test covers bounded walking, same owner across waypoints, sharp-turn safety, wrapped yaw, no progress timeout, stop/cancel, off-height refusal, all-edge graph verification, stale/foreign token refusal, chain terminal proof, exclusion of typed traversal and 10 independently initialized walking controllers.
- **PASS**: Follow/Waypoint Bridge recompiled against mock Forge-facing API (only a contract smoke compilation; real Forge classes and runtime were not present).
- **PASS**: Unmodified backend full `python -m unittest discover -s backend/tests -v`: **201 tests OK**; no C0/Backend source changes.
- **PASS**: Verified source tree comparison against C2-F2 baseline; changes concentrated in F2 Follow, C1 movement/rotation, C2 waypoint bridge/receipt and new tests/docs.
- **PENDING**: actual Forge compile. `forge-mod/gradlew --offline compileJava` failed *before compilation* because Gradle wrapper attempted to download `gradle-8.8-bin.zip`, and `services.gradle.org` DNS resolution failed (`UnknownHostException`). This is not a source compilation PASS or FAIL.
- **PENDING**: real Minecraft local/world and client animation/smoothness acceptance; cancellation/combat/Save&Quit/despawn/rejoin regression; Server MSPT and packet rates with concurrent 1/3/5/10 NPC.

## Mandatory Windows criteria before acceptance

1. Actual Forge Java17 build and `runClient` compile, no compile errors; compare visuals with C2-F2 baseline.
2. Basic Follow: Yui follows Dev while walking flat diagonal, can turn along a curve without `motion_done` after every 1–3 blocks. Some terminal motion per bounded chain is expected.
3. Radius: >8-block distance follow continues through successive loaded local frontiers; never forces chunks or teleports.
4. Wall/corner: no clip, no jump across blocked corner and no forward motion into unloaded/unsafe floor. An unsafe route must stop/replan under bounded budget.
5. Stop: followstatus / unfollow / c2 cancel during WALK, verify no old motion restarts, then fresh follow/new navigate is allowed.
6. Combat: knockback overrides continuous WALK, no stale reactivation, C1 combat and death/regeneration behavior intact.
7. Vertical: STEP_UP/JUMP_UP/SAFE_DESCEND existing typed C1 movement and landing receipt still work. Pending STEP_DOWN and mixed terrain tests remain explicitly open.
8. Save&Quit/rejoin + offline and target dimension/death: no obsolete Follow replay, no C0 journal reset bypass.
9. Performance: 1,3,5,10 simultaneous NPC follow: measure actual Forge MSPT, tickets/sec, rotation packet revisions per second, route planning cost and CPU, compare old vs fix. Mark as PENDING without real measurements.

## No blanket quality gate declaration

This file records automated code evidence, not confirmed gameplay performance. The user controls Windows Minecraft acceptance and GitHub operations; this ZIP is a **candidate**, not a release.
