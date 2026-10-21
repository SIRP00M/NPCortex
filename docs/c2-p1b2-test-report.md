# C2-P1-B2 Candidate — Test Report (2026-10-16)

## Baseline and scope

Input: `NPCortex_C2-P1-B1_C_TraversabilityGraph_FullRepository_CANDIDATE.zip`.
User-approved scope: P1-B2 Architecture C Reliable General-Purpose A* Engine ONLY. No code changes to C1 movement, HTTP Backend/Ollama, network, inventory, lifecycle, B3 Region Planner, P1-C Waypoint Execution or P1-D Replanning. No GitHub operations.

## Tests ACTUALLY RUN in this environment

| Check | Result | Important caveat |
| --- | --- | --- |
| C2-P1-B2 pure A* contract | **PASS: 71 assertions** | JDK 21 running `javac --release 17`; not a Temurin 17 JVM or Forge |
| P1-B1 TraversabilityGraph | **PASS: 44 assertions** | Pure Java graph, B1 clearance probe real-world claims unproven by this |
| P1-A NavGrid | **PASS: 27 assertions** | Pure Java 200 scan microbenchmark, not Forge |
| C2 Foundation | **PASS: 42 assertions** | Pure Java |
| C1 Motion Plan | **PASS: 33 assertions** | Pure Java |
| C1 Sync State | **PASS: 26 assertions** | Pure Java |
| C1 Inventory Journal | **PASS: 544 assertions** | Pure Java, includes 512 capacity iterations |
| C1 Velocity Ownership | **PASS: 34 assertions** | Pure Java |
| C1 Movement Lifecycle | **PASS: 244 assertions** | Pure Java |
| C1 Combat Tick Policy | **PASS: 135 assertions** | Pure Java |
| C1 Death Presentation | **PASS: 91 assertions** | Pure Java |
| C1 VanillaTickPolicy | **PASS: 32 assertions** | Pure Java |
| R0 Lifecycle Ownership | **PASS: 14 assertions** | Pure Java |
| **Java regression total** | **PASS: 1,337 assertions** | All above, each counted once |
| Backend Python `unittest discover` | **PASS: 193 tests, 33.783s** | Mock/fault-path logs expected; NOT a live Ollama check |
| Command tree compile with minimal Brigadier/Minecraft test stubs | **PASS** | Syntax/generic sanity check only; **NOT real Forge compilation** |
| `./gradlew --offline compileJava` | **BLOCKED BEFORE COMPILE** | Gradle 8.8 wrapper distribution unavailable, tries to download |
| ZIP consistency | Pending packaging review | Verified before delivery |

A* regression cases include flat optimal paths, identity path, closed wall, wall detour, hazard avoidance, unloaded columns, no invented jump/step transitions, collision-veto graph, budget vs NO_PATH, invalid start, unsafe target, out-of-bounds coordinates, determinism, immutable route, inconsistent graph/snapshot rejection and max-radius bounded scan. Microbenchmarks (150 pure Java plans, and 100 graph builds) do not establish Forge tick latency or concurrent MSPT.

## Windows Build and Gameplay: NOT YET CONFIRMED

Required:

```powershell
cd F:\AI\NPCortex
.\scripts\test-c2-p1b2.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Minecraft commands, when Yui is alive and on safe flat ground (replace coordinates with **actual integer foot-block target** within radius 6):

```mcfunction
/npcortex c1 info yui
/npcortex c2 navgraph yui 6
/npcortex c2 navplan yui 80 69 -66
/npcortex c2 navplan yui 80 69 -66 6 1
```

Ask user for Forge Build result plus `navplan` scenarios in `docs/c2-p1b2-astar-path-planning.md`: flat route; obstacle with gap; fully blocked; limited budget; out-of-scan/unsafe target; lifecycle/retracking and C1 motion regression. Confirm NPC never moves from a `navplan` command and never reports A* execution as success.

**Candidate only.** B2 Quality Gate is open until Windows Forge Build and user gameplay validation. P1-A unloaded chunk and per-coordinate partial terrain tests remain `NOT YET TESTED` in `PENDING_TESTS.txt`; B1 per-edge correctness and 1/3/5/10 NPC concurrent performance also remain pending. Do NOT start B3/P1-C implementation without a new approval.
