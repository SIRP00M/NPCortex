# C2-P1-B1 Implementation Candidate — Test Report

Date: 2026-10-16 (local development environment, Linux container)
Baseline: NPCortex_C2-P1-A_C_WorldRepresentation_FullRepository_CANDIDATE.zip
Implementation scope: B1 traversability graph + read-only Forge world diagnostic only.

## Tests actually run on this candidate

- **PASS**: `javac --release 17` and standalone Java self-tests, total **1,266 assertions**:
  - C2NavGrid 27; **C2TraversabilityGraph 44**; C2 Foundation 42;
  - C1 MotionPlan 33; P0 Sync 26; Inventory Journal 544;
  - Velocity Ownership 34; Movement Lifecycle 244; CombatTick 135;
  - DeathPresentation 91; VanillaTick 32; Lifecycle Ownership 14.
  - Runtime JDK 21 compiling Java 17 target; NOT Java 17 runtime/Forge compilation.
- **PASS**: Backend focused `test_c2_embodied_contract.py`, **8 tests**, exit code 0.
- **NOT CONFIRMED**: Backend full-suite rerun. Started `python -m unittest discover -s backend/tests -q`; terminated by execution timeout; no full-suite `OK` was obtained. Previously P1-A user confirmed full Backend 193 tests PASS on Windows, but B1's full suite has not been independently reconfirmed here. Backend files were not edited.
- **BLOCKED BEFORE COMPILATION**: `./gradlew --offline compileJava` attempted. Gradle Wrapper could not resolve/download Gradle 8.8: `java.net.UnknownHostException: services.gradle.org`. No Forge Java source compilation completed here. User must run Windows Forge build.
- **NOT RUN**: Minecraft world live `navgraph` edge verification, client/server, actual unloaded chunk, slab/stairs per-coordinate check, concurrent MSPT, multiplayer, recovery and mod compatibility tests.

## Pure Java graph behavior verified

Flat 3x3 graph: 9 nodes, 24 directed edges; central wall splits graph;
unknown/unloaded, hazard, obstruction and partial support excluded; multiple
standing heights remain separate; diagonal shortcuts, guessed steps/drops
and asymmetric/failed edge probes excluded; malformed snapshot rejected;
immutable lists/adjacency; deterministic edges; bounds enforced.

Pure JVM maximal flat-grid test: 169 vertices/624 directed edges at radius 6.
100 graph constructions ~54–73ms TOTAL in this environment (varied runs),
**not** Minecraft Server MSPT and **not** a production performance guarantee.
No background tick or automatic graph refresh is introduced.

## Windows Acceptance (before P1-B2)

1. `cd F:\AI\NPCortex`
2. `.\scripts\test-c2-p1b1.ps1` (requires Eclipse Temurin JDK 17)
3. `.\scripts\dev-forge.ps1 -Task build` (record `BUILD SUCCESSFUL`)
4. Start Client, spawn Yui, compare `/npcortex c2 navscan yui 4`
   with `/npcortex c2 navgraph yui 4` in flat terrain.
5. Build a solid wall, rescan, verify graph reflects changes; remove wall
   and observe edge count recover. Re-test hazard/partial geometry conservatively.
6. Test `navgraph` following despawn (reject), then respawn (works).
7. Check actual FPS/MSPT and on-demand graph elapsed times. Sequential 5-NPC
   NavScan from prior P1-A is not proof of graph concurrency.
8. Confirm that `navgraph` does not cause Yui to move and existing `c2 move`
   remains unchanged.

**B1 Quality Gate remains pending Windows Build + real gameplay checks.**
Do not start B2/B3/P1-C implementation until reviewed.

See `PENDING_TESTS.txt` for explicitly carried-forward P1-A unverified items.
No GitHub operations performed.
