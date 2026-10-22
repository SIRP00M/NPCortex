# NPCortex — Airborne Follow Target Projection / Flight-Y Oscillation Fix

## Baseline and approved intent

- Baseline: `NPCortex_StableTrajectoryFollow_B_FullRepository_CANDIDATE.zip` (the latest Stable Trajectory B Candidate shown before the user confirmed the Creative-flight trigger).
- Observed symptom: while a player flies in Creative Mode, the ground-bound NPC may wobble or alternate left/right even on straight ground; the user reported the airborne comparison made the trigger clear.
- Scope: Follow-only target interpretation and planning. Do not alter C1 physics, Motion Ownership, Transactional Cancellation, Combat, 3D landing receipts, hazard policy or generic C2 Navigate.

## Audit: code conditions before patch

`C2FollowRuntime.tick()` computed Follow distance with all X/Y/Z components, including the vertical gap to a flying player. It also paused when the target's height differed from the NPC by more than the bounded local vertical reach. Terrain-native planning in `plan`, `extendLiveFollow` and `prepareContinuation` passed `player.getY()` as the destination height even for airborne players. `C2TerrainNativeFollowPlanner` uses height terms in goal selection and ring eligibility; there was no airborne target projection. These inconsistencies can cause inappropriate candidate selection/pauses while a player flies over safe ground. The exact fractional contribution to the in-game zigzag remains unmeasured.

## Implementation

- New pure-Java `C2FollowTargetProjection`: airborne mode requires confirmed flight flag (Creative flying or elytra), not ordinary jumps. Transition debouncing: enter after 2 server ticks, exit after 4. Grounded players retain actual 3D Y.
- Airborne Follow stop/resume gating uses horizontal XZ distance, eliminating false “still far” just because target Y is above the NPC.
- The vertical-gap pause is not applied while flight mode is active. Local graph bounds and loaded-world requirements remain mandatory.
- All three terrain-native route-selection paths (normal planning, live persistent extension, speculative continuation) receive a **projected support height** rather than mid-air player Y when flying.
- The projected height is selected **only** from nodes reachable through approved directed edges in the already captured bounded, world-checked 3D graph and no higher than the target's feet (with a 0.5-block tolerance). For far-away airborne XZ or when no reachable surface exists near the target, use the verified starting-node footY as a neutral scoring/reference height. This **does not** synthesize an edge, standing surface or executable path.
- When the target flight/ground mode changes, drop speculative paths and unstarted routes; never interrupt an active owned C1 ticket solely because the mode changed. Existing stop-distance, cancel, combat and lifecycle rules still apply.
- `c2 followstatus` includes `targetMode`, `targetProjection`, `projectedY` diagnostics for flight-vs-ground comparison.

## Validation performed in this environment

| Check | Result | Interpretation |
|---|---|---|
| Pure Java compilation (`javac --release 17` on local JDK21) | PASS | Compiles shared pure Java contracts; **not Forge mod compilation** |
| Pure Java regression | **22/22 suites, 2,658 assertions PASS** | 21 original Stable Trajectory B suites plus 44 new airborne projection assertions |
| Backend C2-focused Python tests | **16/16 PASS** | No contract break detected in focused backend tests |
| ZIP integrity and source-diff scope | To verify at final packaging | Full repo must be preserved |
| Forge Gradle `compileJava` | **BLOCKED** | Wrapper requires unavailable Gradle distribution/download; not claimed to compile with Forge API |
| Actual Windows Minecraft Creative-flight behavior | **PENDING** | Must verify on user machine |
| 1 / 3 / 5 / 10 NPC Forge MSPT + multiplayer | **PENDING** | No performance acceptance inferred from JVM tests |

## Not changed / known limitations

- No new 3D flying NPC capability. The NPC remains an ordinary ground-bound `Custom ServerPlayer` using existing movement and terrain validation.
- Projection is bounded to the existing local graph (radius 6, vertical scan relative to the NPC). Distant targets use a neutral provisional Y; no far-world ground-level estimation, chunk generation, or teleporting.
- An airborne player over a ravine may have **no reachable ground underneath**; the system must still route around using approved edges or pause safely.
- Ordinary falling/jumping without flight state retains the legacy grounded 3D target logic. This change is focused on actual Creative/elytra flight.
- The root cause is supported by user-observed flying-vs-ground comparison and static source inconsistencies, but full in-game elimination of zigzag is **not yet verified**.

## Windows build + acceptance

Run from the repository root with Temurin 17:

```powershell
.\scripts\test-c2-airborne-target-projection.ps1
.\scripts\test-c2-stable-trajectory-follow-b.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Then perform the cases in `docs/c2-airborne-target-projection-windows-checklist.md`. Record any Forge `compileJava` errors and capture `c2 followstatus yui`, `c1 motion yui` at the moment of visible left/right oscillation.

**GitHub:** no commit, push, branch or repository action performed.
