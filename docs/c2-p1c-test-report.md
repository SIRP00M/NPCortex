# C2-P1-C — Reliable Transactional Waypoint Engine Test Report

Date: 2026-10-16. Status: **IMPLEMENTATION CANDIDATE, NOT WINDOWS ACCEPTED**.

## Scope & baseline

- Source baseline: `NPCortex_C2-P1-B3_C_ReliableHierarchicalRegions_FullRepository_CANDIDATE.zip` (user confirmed B3 Core PASS on Windows).
- Implementation changes: C1P1MotionRuntime.java (transient motion-session UUID ticket + last-ticket read-only receipt; Vanilla `travel`/knockback math unchanged); C2AgentRuntime.java (navigation goal, route tick state and stricter local+C0 success requirement); C2MoveExecutor.java, C2Commands.java; new C2WaypointExecution.java and C2WaypointBridge.java; new C2WaypointExecutionSelfTest.java; documents and PowerShell script.
- NOT changed: Backend, Qwen provider, B1/B2/B3 planner logic, C1 inventory, lifecycle policy, death, combat formulas, networking.

## Tests run on this candidate (local environment)

| Test | Result | Limitations |
|---|---|---|
| NEW C2-P1-C Waypoint Protocol | **PASS — 261 assertions** | Pure JVM, ticket mismatch/duplicate, segment ordering, failed receipt, fake wall/lost edge, immutable/bounded path. Does not exercise real Forge movement receipts. |
| B3 Hierarchical Planner | **PASS — 677 assertions** | Pure JVM cost/reference comparisons and randomized maps. |
| B2 A* | **PASS — 71 assertions** | Pure JVM. |
| B1 Traversability Graph | **PASS — 44 assertions** | Pure JVM. |
| P1-A NavGrid | **PASS — 27 assertions** | Pure JVM. |
| Existing C2 Foundation and C1 Java regression | **PASS — 1,195 assertions** | Pure JVM including inventory/physics policy self-tests. |
| **Total non-duplicated Java** | **PASS — 2,275 assertions** | OpenJDK 21 tool, `javac --release 17`; not actual Temurin 17/Forge. |
| Backend `unittest discover` | **PASS — 193 tests; 33.828 sec** | Uses mocked failures; error prints from negative tests are expected. |
| Brigadier command tree syntax against limited stubs | **PASS** | Only syntax/shape; no Forge API guarantee. |
| Gradle 8.8 `compileJava` | **BLOCKED BEFORE COMPILE** | Wrapper tries `services.gradle.org` and raises `UnknownHostException`. |
| Windows Forge Build / Yui multi-segment gameplay | **NOT VERIFIED** | User must run on Windows and supply evidence. |
| Real cancellation, knockback, lifecycle, post-restart action isolation | **NOT VERIFIED** | Pure contract checks alone are not integration proof. |
| Concurrent 1/3/5/10 NPC / MSPT | **NOT VERIFIED** | No actual server tick benchmark; B3 local 10 pure plans were 27.89 ms total (not MSPT). |

## Local Java and backend output

```
PASS C2 P1-A NavGrid: 27 assertions
PASS C2 Foundation: 42 assertions
PASS C1-P1 MotionPlan: 33 assertions
PASS C1P0SyncStateSelfTest (26 assertions)
C1 Inventory Journal PASS: 544 assertions
PASS C1-R2 Fix #2 Velocity Ownership: 34 assertions
PASS C1-R2 Fix #2 Movement Lifecycle: 244 assertions
PASS C1-R2 Combat Tick Policy: 135 assertions
PASS R1/R1-A Death Presentation Policy: 91 assertions
PASS C1-P1-C VanillaTickPolicy: 32 assertions
PASS R0 Vanilla Lifecycle Ownership Policy: 14 assertions
PASS C2 P1-B1 TraversabilityGraph: 44 assertions
PASS C2 P1-B2 AStarPlanner: 71 assertions
PASS C2 P1-B3 Hierarchical Region Planner: 677 assertions
PASS C2 P1-C Waypoint Protocol: 261 assertions

Ran 193 tests in 33.828s
OK
```

Full Windows acceptance checklist: `docs/c2-p1c-windows-checklist.md`.
Open carried-forward validation: `PENDING_TESTS.txt`.

**No GitHub Operations. No P1-D Dynamic Replanning.**
