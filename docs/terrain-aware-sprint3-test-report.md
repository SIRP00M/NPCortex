# NPCortex — Terrain-Aware 3D Traversal Sprint 3 Test Report

## Scope

Architecture C, Sprint 3 only. Baseline is Sprint 2 Full Repository Candidate.
No Sprint 4 autonomous 3D midair recovery, multi-chunk/world exploration,
non-Vanilla physics, Backend schema or GitHub changes.

## Implementation summary

- `C2TraversabilityGraph`: weighted, cardinal, **directed** WALK/STEP_UP/
  STEP_DOWN/JUMP_UP/SAFE_DESCEND edges; disallowed heights never get an edge.
  Historic two-argument probe retains its **flat** semantics for older tests.
- `C2GraphWorldProbe`: server-only bounded loaded-chunk checks, swept body
  clearance, endpoint standing AABB, conservative jump headroom. No force-load.
- `C2NavigationSensor`, `C2NavGrid`, `C2TerrainObservation`: classify certified
  vanilla stair partial-support as `STAIR_TRANSITION`, **not** `WALKABLE`;
  require >=50% support, nonhazardous surface and full-body landing clearance.
  Generic partial support stays excluded. Half-height supportY is resolved
  consistently for Sensor, B2/B3, Runtime, and C1 S2 landing preflight.
- `C2RegionGraph`: use weak tile connectivity solely for partitioning;
  directed portal/shortest-path costs remain authoritative, compare with B2.
- `C2WaypointExecution`/`C2WaypointBridge`: movement kind and matching
  `TRAVERSAL_COMPLETE_<KIND>` receipts, one active C1 ticket; explicit
  centering ticket for off-center starting positions before a vertical segment.
  Every segment revalidates the fresh graph on the Minecraft server thread.
- `C1P1MotionRuntime.traverseC2`: C2-aware entry into existing C1 Vanilla
  traversal session and S2 collision/landing validation; no parallel physics,
  teleport, dual velocity ownership, combat contract change or Qwen call.
- `/npcortex c2 navigate`, `navplan`, `navhier`: read/request fractional feet Y.
  `navigate` refuses a target height not matching the observed goal surface.

## Local test evidence (candidate)

- Java 17 `--release 17` pure JVM: **3,657 assertions PASS across 21 suites**
  (21 original suites plus 12 added S3 assertions; actual total confirmed below).
- Backend full suite: **201 tests PASS** (`python -m unittest discover -s backend/tests -q`).
- Synthetic 3D test covers weighted B2/B3 parity, one-way edges/portals,
  stale/incorrect receipts, centering tickets, stair candidate contracts,
  missing edges, 1/3/5/10 independent route states.
- Benchmark numbers reported by Java tests are *not* Minecraft Forge MSPT.
- **Forge Gradle Build not verified here:** Gradle 8.8 wrapper distribution
  download fails due blocked external access, BEFORE real Forge compilation.
- **Windows build and live 3D gameplay are pending**. Candidate is NOT the
  completed Sprint 3 Acceptance or Sprint 4 approval.

## Pending

See `PENDING_TESTS.txt` and the Windows Checklist. In particular Sprint 2
STEP_DOWN (-0.5) is not user-confirmed, all stair orientations and Vanilla
route correctness are unverified, and cancel-in-air, unloaded chunks,
combat, crash/rejoin and 1/3/5/10 MSPT remain open.
