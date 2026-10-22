# C-TN — Persistent Terrain-Native Follow Controller (Implementation Candidate)

Date: 2026-10-21. Base: user-supplied C-3D Persistent Mixed 3D Locomotion Full Repository Candidate. The user selected Option C to address `PAUSED_UNREACHABLE` by a ravine and renewed zigzag after C-3D. No GitHub operations.

## Source/root-cause audit

Gameplay evidence (screenshots) shows `F1=PAUSED_UNREACHABLE`, `failures=3`, `avoided=3`, `rollingMisses=4`, `mixed3DStarts=4`, `motion=BLOCKED`, `horizontalCollision=false` near steep uneven ravine terrain. `horizontalCollision=false` does **not** prove all support/landing/edge checks passed. The screenshots do not identify which C1 stage refusal occurred; no complete stage-specific server trace was provided. Treat root-cause identification as informed by code paths, not an independently verified playback.

Previous Follow target selection first ranked ring/frontier cells geometrically, then spent at most four expensive target searches; it could reject all selected candidates even if a longer **reachable** 3D route existed. Failed nodes could exclude the only corridor; stationary-player recovery permitted one delayed re-probe and then potentially paused indefinitely. Mixed 3D start/fallback can reset the steering corridor and create visible oscillation. The local radius-6 scan cannot see arbitrary far terrain.

## Implementation: terrain-native Follow only

- Added `C2TerrainNativeFollowPlanner`: a **single bounded** directed-graph Dijkstra search with incoming heading state and turn penalty, using real edge risk costs from the fresh 3D graph. Ranking happens *after* reachability and actual path costs are known; includes safe follow ring, forward rolling and stable preferred endpoint. Search never invents links to unloaded/hazardous/unsupported cells. Upper bound 3000 state expansions per selection, 64 path nodes.
- Route cost discourages dangerous and previously failing corridors, but previous failed-node memory is a finite **penalty** rather than an absolute claim that a newly rescanned edge is untraversable. Only the live graph and C1/receipt checks grant movement permission.
- When NO reachable advancing candidate exists on the radius-6 graph, optional **bounded lateral detour** selects an actual safe side corridor, retaining the side across planning decisions. Maximum **6 lateral corridor selections** before failing closed; not an infinite local-wall-follower or a guarantee of escaping a ravine. Source can re-open after target motion; still requires per-stage physical verification.
- `C2FollowRuntime` uses the new selector for initial Follow route and same-ticket live continuation from the **committed** endpoint. Does not change static `/npcortex c2 navigate` logic or the C1 player physics owner.
- Added `C2FollowPolicy.terrainReprobe()` with increasing cooldowns **40/80/120 ticks**, maximum 120 ticks for later retries, while retaining legacy `recoverOnce()` API for existing behavior/tests. Every probe uses a new graph and per-server one Follow planning batch/tick; no automatic motion if no verified route exists. Successful physical progress resets this recovery backoff. `Unfollow` and `CANCELLED` remain fenced and must not resume.
- Added `terrainSelections`, `terrainReprobes`, `detourHops`, `terrainDecision`, `lastFailure` diagnostics to `/npcortex c2 followstatus <name>`.
- Kept C1 `Custom ServerPlayer`, `Player.travel()`, mixed WALK/STEP_UP/STEP_DOWN/JUMP_UP/SAFE_DESCEND staged safety/landing receipts, combat priority, cancellation ownership, chunk handling and no restart replay. Normal C2 operator Navigate is unaffected.

## Actual tests in this environment

| Test | Result | Evidence / limitation |
|---|---|---|
| Pure Java existing + new contract suites | **PASS 19/19, 2,560 assertions** | JDK 21 `javac --release 17`; includes 36 new assertions across ravine detour, elevation, risk, no paths, bounded sideways, backoff. Does not compile Forge-bound classes. |
| Python backend C2 focused tests | **PASS 16/16** | No backend modifications. |
| Java 17 syntax parse for changed Forge classes | **PASS** | javac parser; NOT full Forge compile. |
| Pure JVM local-graph selection microbenchmark | **~1.8–3.8 ms/selection average observed across runs** | 169-node/1200-edge synthetic graph, warm-up + 200 runs, NOT actual Forge MSPT; not a throughput certification. |
| Forge Gradle compileJava/build | **PENDING — verify Windows JDK17** | This environment's Gradle 8.8 wrapper distribution is unavailable. |
| Real Minecraft 3D gameplay + bug fix | **PENDING** | Requires player evidence and logs from the ravine fixture. |
| Modded blocks, dynamic terrain, rejoin, multiplayer and 1/3/5/10 NPC MSPT | **PENDING** | May reveal Forge/runtime issues not represented by pure Java tests. |

## Safety review and limitations

- No new physics writer: C1 remains the exclusive movement owner, with its token and per-stage collision / support / headroom / hazard / ground landing verification; planning success never means physical completion.
- A route that starts with a lateral detour can temporarily increase player distance; strict six-selection cap and preference for eventual forward progress prevent unlimited sideways search with a stationary target, but cannot guarantee a route beyond the local scan horizon. Do **not** lower hazardous/fall safeguards to force a success.
- Repeated fresh graph probes are rate-limited, not continuously polling the world each tick. Need measure real MSPT under 1/3/5/10 NPC; if high, revert or decrease planning frequency with approval.
- Existing C-3D and navigation quality-gate gaps remain; do not declare the whole project or this Candidate Gameplay PASS based on these local tests.
- No GitHub branch/commit/push. Owner validates quality gate on Windows.
