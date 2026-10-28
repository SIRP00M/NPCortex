# C2-P1-B2 — Reliable General-Purpose A* Engine (Architecture C)

**Scope:** P1-B2 ONLY. Builds an immutable, deterministic, bounded *path proposal* over the P1-B1 traversal graph. **No walking, region routing, recovery, building/mining or Qwen change.** C2-P1-B3/P1-C/P1-D are not implemented or approved by this package.

## Components and boundaries

- `C2AStarPlanner` is pure Java 17; takes a B1 `C2TraversabilityGraph` and its P1-A `Snapshot`, exact start/goal support-block coordinates and an expansion budget. Result has typed status, ordered immutable path, cost, expanded and visited counts. The route includes start and goal on success. Same input yields the same path.
- A* uses an admissible conservative horizontal heuristic calculated from the graph's minimum cost per unit Manhattan displacement (zero if no horizontal edges); it does **not** assume future vertical/jump edge costs. Priority queue deterministic tie breaks; finite `g`/cost validation and bounded expansions. No loss of validity or magic traversal through UNKNOWN/HAZARD/UNLOADED regions. An unfinished search yields `BUDGET_EXCEEDED`, **not** `NO_PATH`.
- `C2GraphWorldProbe.captureWithSnapshot` constructs graph/snapshot from **one** fresh server-thread-only observation, with loaded-chunk checks, full-size swept AABB clearance, and no force-load. The planner additionally checks graph vertices match that snapshot.
- `C2PathDiagnostics` validates actor identity/dimension/lifecycle/active motion; computes route once on demand, and prints status + path metrics. It does not persist a path or call C0/Ollama and is **read-only**.
- Proposed future B3 Region Planner can call the same pure planner. Future P1-C **must freshly re-check** collision, chunk status, avatar lifecycle and action ownership before every segment; P1-B2 plans are snapshots, not execution authority.

## Read-only Minecraft command

```mcfunction
/npcortex c2 navplan yui 80 69 -66
/npcortex c2 navplan yui 80 69 -66 4
/npcortex c2 navplan yui 80 69 -66 6 1
```

`x,y,z` are **integer block coordinates of the player's FEET** (F3 Block Y, not the center or the block below). Internally, `supportY=y-1`. Optional `radius` is 1..6, default 6; optional `budget` is 1..845, default 845. Targets are exact; never silently snap to a different safe location. NPC must be alive, registered and motion-idle; target must be inside the NPC-centered scan.

Result statuses:

- `PATH_FOUND`: route of real graph edges (including the case start equals target); **no movement**.
- `NO_PATH`: safe target is on the graph but no path exists after the open set is exhausted.
- `INVALID_START`: current support position is not in the scanned safe graph.
- `OUT_OF_SCAN`: target lies outside the sampled radius/vertical band.
- `UNSAFE_TARGET`: target is in scan but is not a safe graph vertex (including unloaded/unknown/support problems).
- `BUDGET_EXCEEDED`: not enough expansions to establish a route; this is **not** proof that no route exists.

P1-B1 currently has only same-foot-height, four-cardinal WALK edges. Therefore P1-B2 deliberately does **not** find steps/jumps, ladders, water routes, diagonal gaps or multiple-chunk long-distance routes, even though graph nodes preserve 3D standing heights.

## Windows test and gameplay checklist

1. Extract this **full** ZIP into your **backed-up** development repository, keeping paths intact. Do not mix files with older versions.
2. Run in Windows PowerShell:
   ```powershell
   cd F:\AI\NPCortex
   .\scripts\test-c2-p1b2.ps1
   .\scripts\dev-forge.ps1 -Task build
   .\scripts\dev-forge.ps1 -Task runClient
   ```
3. Spawn Yui on flat ground (`/npcortex c1 spawn yui`), verify her current position (`/npcortex c1 info yui`), and set a target 2-4 blocks away, same FEET Y, within radius 6. Expect `PATH_FOUND`, `cost` consistent with cardinal steps, positive waypoints and **no Yui movement**. Use **integer block** positions, not necessarily player F3 decimal coordinates.
4. Put a 2-high full-block wall with an opening/detour that remains INSIDE radius 6; expect `PATH_FOUND` with more waypoints/cost than the direct route. Ensure target itself remains a walkable support position, not inside the wall.
5. Close all possible routes between start and goal **within the observed graph**. Expect `NO_PATH`; note that if the target is obstructed the correct status is `UNSAFE_TARGET` instead.
6. `navplan ... 6 1` on a distant target should produce `BUDGET_EXCEEDED` (unless already at target); repeating with default budget should find the route if a path exists.
7. Target 7+ blocks away from a radius 6 scan → `OUT_OF_SCAN` (subject to NPC/current integer position); aim target at solid obstruction → `UNSAFE_TARGET`. Repeat command; output path/cost/expanded should match (timing can vary).
8. Despawn Yui and test `navplan` failure without crash; respawn and plan again. With an active C1 Movement Session, `navplan` must refuse. Confirm C1 actual Movement/Knockback/Inventory/Networking unchanged.
9. Record F3 MSPT at idle and during repeated `navplan` calls; pure Java timing is not Forge/MSPT. Test multiple NPCs only as resources allow; no unobserved performance claims.
10. Return **both** Forge Build results and Minecraft screenshots/logs. A passing isolated Java test is insufficient for P1-B2 acceptance.

## Evidence and open risks

- See `docs/c2-p1b2-test-report.md` for this candidate's measured test claims and environmental limitations.
- Forge Build/gameplay remain **NOT VERIFIED** until the Windows user reports results.
- Pending P1-A tests remain in root `PENDING_TESTS.txt`: unloaded chunk real gameplay and per-coordinate slab/stair/partial-block semantics are explicitly **NOT YET TESTED**. Do not infer PASS from aggregated counters.
- B1 per-edge correctness, concurrent MSPT, mod compatibility and cross-chunk planning are not proven by this pure-JVM run.
- No GitHub operations performed.
