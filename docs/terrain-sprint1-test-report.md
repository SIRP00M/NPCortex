# NPCortex Terrain-Aware 3D Traversal — Sprint 1 (Architecture C) Test Report

## Base and changes

Base: `NPCortex_C2-P1-D_C_ReliableTransactionalDynamicReplanning_FullRepository_CANDIDATE.zip`.

- New `C2TerrainGeometry`: deterministic exact rectangular-union support check
  across all same-height VoxelShape collision boxes, using the player footprint.
- New immutable `C2TerrainObservation`: support, clearance, hazard/unknown,
  reason and fraction. Unsafe/unknown evidence cannot be promoted to WALKABLE.
- Updated `C2NavigationSensor`: uses the same geometry evaluation for NavGrid
  as the per-coordinate `terraininspect` read-only command. Preserves existing
  loaded-chunk, dimension, world-border and lifecycle guards.
- Updated `C2Commands`: `/npcortex c2 terraininspect <name> <x> <supportY> <z>`.
- Retains **flat WALK only** in TraversabilityGraph, B2/B3 A*, P1-C waypoint
  controller, P1-D replan, C1 Vanilla Physics and C0 Backend.

## Tests executed in this environment

| Test | Evidence | Result |
| --- | --- | --- |
| Sprint 1 pure Java geometry + immutable evidence | 1,073 assertions including slabs, composite rectangles, stair-like partial heights, narrow support, crack, invalid data, performance microcheck | PASS |
| C2 pure Java graph/planners/waypoint/replan | 8 selftest programs including Terrain, NavGrid, B1, B2, B3, P1-C and P1-D = 2,296 assertions by reported counts | PASS |
| Backend full Python unittest suite | 201 tests in 34.062 s, final `OK`; expected negative-test error traces occurred | PASS |
| Terrain Sensor compiled against simulated Minecraft API | Java `--release 17` mock compile + 10 simulated shape/collision tests | PASS (MOCK ONLY) |
| C2 command tree compile against simulated Brigadier API | Java `--release 17` compile | PASS (MOCK ONLY) |
| Real Forge 1.20.1 Gradle compile | Wrapper requires Gradle 8.8 download not available; blocked BEFORE Forge compile | NOT VERIFIED |
| Windows Forge gameplay collision/terrain | Must be run by user | NOT YET TESTED |
| Live concurrent server MSPT 1/3/5/10 NPC | Not measured | NOT VERIFIED |

**The mock tests and isolated Java tests are not proof of real Forge compilation or
Minecraft VoxelShape correctness.** No actual Windows Gameplay PASS is claimed.

## Known limitations and follow-up

- Bounding-box check validates a fixed centered standing position, not traversal
  across a changing elevation or offset standing position within stair geometry.
- Unsupported/partial stair treads are fail-closed; Sprint 2/3 will establish
  verified step/jump transitions and update Graph execution only after tests.
- No persistent terrain cache, force-load, multi-chunk planning or additional
  action skills were introduced.
- In-world real unloaded chunk safety, partial-block accuracy, B1 swept-edge
  verification, P1-D cancellation/crash recovery and MSPT remain in PENDING_TESTS.
- One unified `CHANGELOG.md`; no GitHub operations.
