# NPCortex — C2-P1-B3 Test Report (2026-10-16)

## Candidate / source baseline

- Input ZIP: `NPCortex_C2-P1-B2_C_ReliableAStar_FullRepository_CANDIDATE.zip`.
- User approval: **Architecture C — Reliable Hierarchical Region Planner**; P1-B3 only.
- Files changed in existing baseline: `C2Commands.java`, root unified `CHANGELOG.md`, and `PENDING_TESTS.txt`.
- New Java: `C2RegionGraph`, `C2HierarchicalPlanner`, `C2RegionDiagnostics`, `C2HierarchicalPlannerSelfTest`.
- New script/documentation: `scripts/test-c2-p1b3.ps1`, this test report, `docs/c2-p1b3-hierarchical-region-planner.md`.
- **Not changed:** C1 physics/movement, C2-P0 movement, previous B1/B2 graph/A*, Backend/Qwen, Inventory, Lifecycle, networking. No GitHub Operations.

## Tests actually run here

| Test | Outcome | Limitation |
|---|---|---|
| B3 Region / portal / hierarchical planning | **PASS — 677 assertions** | Pure JVM `javac --release 17`, host OpenJDK 21, not Forge |
| Previous B2 A* | **PASS — 71 assertions** | Pure JVM |
| Previous B1 Graph | **PASS — 44 assertions** | Pure JVM |
| P1-A NavGrid | **PASS — 27 assertions** | Pure JVM |
| C2 Foundation | **PASS — 42 assertions** | Pure JVM |
| C1 MotionPlan / Sync / Inventory / Combat / Lifecycle etc | **PASS — 1,153 assertions** | Pure JVM policy tests |
| **Java total (nonduplicated)** | **PASS — 2,014 assertions** | Not a complete Forge build |
| Backend `unittest discover` | **PASS — 193 tests, 33.850 sec, exit 0** | Mock Ollama faults are expected negative tests |
| Command tree Java compile with limited Brigadier/Minecraft stubs | **PASS** | Signature/syntax sanity ONLY, not Forge API fidelity |
| Diagnostics Java source compile against limited Minecraft dependency stubs | **PASS** | Not Forge compilation |
| `./gradlew --offline compileJava` | **BLOCKED BEFORE COMPILE** | Gradle 8.8 wrapper distribution unavailable in this environment; network download failed |
| Windows Forge Build | **PENDING USER VALIDATION** | Must run `dev-forge.ps1 -Task build` |
| Windows Minecraft `navregion` / `navhier` | **PENDING USER VALIDATION** | Real-world Region/Portal semantics not yet checked |
| MSPT/concurrent NPC performance | **NOT VERIFIED** | Pure-Java microbenchmarks and command elapsed time do not prove server MSPT |

The sum 2,014 uses B3 677 + prior B2 report 1,337 assertions. C1 subtotal is 1,153, rather than double-counting prior repeated NavGrid launches.

### B3-specific coverage

- Full flat 13x13 bounded NavGrid: 16 geographic Region components (tile size 4) and 156 directed inter-Region portals; every portal is an existing B1 edge.
- One geographic tile divided by a wall becomes **two** distinct Regions, with zero invented portals.
- Exact B2 reference cost parity; checks all B3 path edges against B1, start/goal positions and repeatability.
- 40 deterministic randomized terrain maps, wall with an open detour, fully closed wall, hazard avoidance, unloaded columns in fake terrain, incompatible elevation planes, wrong graph/snapshot, all-collision-denied graph.
- Tiny budgets -> `BUDGET_EXCEEDED` instead of `NO_PATH`; invalid source, unsafe target, out-of-scan and immutable result data.
- Pure JVM timing in latest B3 test: 10 full-radius plans ~27.58 ms (2.76 ms/plan) with JDK21 host, not a gameplay/MSPT measurement; JIT and probe differences make this unsuitable for speed claims.

## Status / acceptance

**B3 is an Implementation Candidate, not an accepted Windows Forge build or gameplay Quality Gate.** Existing user-accepted B2 remains the operational baseline until the user reports B3 Windows test results. B3 may have MORE overhead than B2 at radius 6 and should not be assumed faster. No P1-C waypoint execution has been enabled. Keep P1-A unloaded-chunk and per-coordinate partial terrain validation explicitly **NOT YET TESTED** in `PENDING_TESTS.txt`.

Windows instructions and expected gameplay results: `docs/c2-p1b3-hierarchical-region-planner.md`.
