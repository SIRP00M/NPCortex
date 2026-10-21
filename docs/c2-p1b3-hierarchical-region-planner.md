# NPCortex — C2-P1-B3 Reliable Hierarchical Region Planner (Architecture C)

**Scope: P1-B3 only.** This is a **read-only navigation plan** and diagnostic feature. **No P1-C Waypoint Execution, no NPC motion, no B4/P1-D, no chunk loading, no Qwen/Backend or C1 physics changes.** Build and real-world gameplay still require Windows acceptance.

## Design

- `C2RegionGraph` partitions B1's **verified** 3D graph into X/Z tiles (default 4 columns each), then splits each tile into **connected components**. Two positions inside the same geographic tile are *not* one Region if the B1 graph contains no way between them. Elevation nodes remain distinct. B1's current directed walk edges are reciprocal cardinal, same-foot-Y only; no invented step, jump, drop, diagonal or unsafe edge.
- A **directed portal** is an *existing B1 directed edge* whose endpoints belong to different connected Regions. Region adjacency or a block's shape never creates a portal on its own.
- `C2HierarchicalPlanner` creates an exact portal overlay: all portal endpoints are retained, and an intra-Region shortest-path computation supplies real cost and real B1 node sequence between endpoint pairs. A region-level connectivity precheck rejects disconnected regions; the weighted overlay search calculates a full route through Region transitions.
- B2 `C2AStarPlanner` runs as an independent reference in the same planning call, **counted against the same total expansion budget**. If B3's route cost or continuity differs, the result is explicitly `source=B2_REFERENCE_FALLBACK`. If B3 incorrectly invented a reachable route but B2 proves `NO_PATH`, fail closed with `INCONSISTENT_GRAPH`. Reference budget exhaustion is `BUDGET_EXCEEDED`, not `NO_PATH`.
- Both planners are **pure-Java** and have no Minecraft or HTTP dependency. Forge captures a fresh NavGrid + Graph **once per diagnostic**, then builds Regions for that observation. There is **no persistent cache**, so no stale cache contract claims. Path output is immutable and must be revalidated by any future executor; it is not execution permission.
- Cap scan radius at 6 (13x13) and max observed terrain candidates at 845. Region tile size 2..6. **Total** B3 search expansion budget 1..24000 (default 12000), shared across coarse Region check, local paths, weighted overlay, and B2 reference. This is a bounded diagnostic engine, **not** an unlimited-world navigator.

## Minecraft commands (read-only; require an alive, motion-idle registered NPC)

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 navregion yui 6
/npcortex c2 navregion yui 6 4
/npcortex c2 navhier yui 80 69 -62
/npcortex c2 navhier yui 80 69 -62 6
/npcortex c2 navhier yui 80 69 -62 6 1
/npcortex c2 navplan yui 80 69 -62
```

- `navregion <name> [radius] [tileSize]` reports `regions`, `directedPortals`, `graphNodes`, `graphEdges`, `elapsedMs`.
- `navhier <name> <x> <feetY> <z> [radius] [budget]` reports status, source, region/portal counts, expansion/visit/budget, cost/waypoints/firstStep on success and timing. **`feetY` means the integer block coordinate at player feet; support block Y is `feetY-1`.**
- `navplan` remains the unmodified B2 reference command. Compare `status` and `cost`, not just path length (equal-optimal paths can take different routes). Only change `elapsedMs` between repeated runs; route result/source/metrics should be deterministic when the world is unchanged.
- `PATH_FOUND`, `NO_PATH`, `INVALID_START`, `OUT_OF_SCAN`, `UNSAFE_TARGET`, `BUDGET_EXCEEDED`, `INCONSISTENT_GRAPH` are explicit outcomes. Budget exhaustion does **not** prove no path.

## Windows acceptance checklist

1. Back up the last user-accepted B2 Repository; extract this **full repository ZIP** into `F:\AI\NPCortex` (do not overlay old stale source files).
2. Run `cd F:\AI\NPCortex`, then `.\scripts\test-c2-p1b3.ps1` and `.\scripts\dev-forge.ps1 -Task build`. Confirm both exit 0 and `BUILD SUCCESSFUL`.
3. Start `.\scripts\dev-forge.ps1 -Task runClient` (Ollama/Backend not required for read-only commands).
4. Spawn Yui on flat grass and use `/npcortex c1 info yui`. Choose reachable target 2–4 columns away **at current feet Y** within radius 6. Compare `/npcortex c2 navplan yui <x> <y> <z>` and `/npcortex c2 navhier yui <x> <y> <z>`; expect matching `PATH_FOUND` and `cost`, Yui **does not move**.
5. Put a full-block wall two blocks high between Yui and goal, with an opening inside the scan. Expect a detour with B3 cost equal to B2. Close the opening, leaving goal safe; expect `NO_PATH`. Do not confuse `UNSAFE_TARGET` (goal inside wall) with `NO_PATH`.
6. Wall place → `navregion` and `navhier` → wall break → rescan. Regions/portals/paths must update; no stale cache. Repeat an unchanged command to verify determinism.
7. `/npcortex c2 navhier yui <x> <y> <z> 6 1` toward a non-adjacent reachable goal: expect `BUDGET_EXCEEDED`, **not** `NO_PATH`. Target beyond 6: expect `OUT_OF_SCAN`. Unsafe target: `UNSAFE_TARGET`.
8. Despawn Yui then run `navregion`/`navhier`: fail safely without crash; respawn and retry. Active C1 motion should reject diagnostics.
9. Record Forge debug `elapsedMs` and baseline MSPT from F3 before/after repeated commands; compare B2 vs B3 cost/time. A* on this small grid may be faster than B3; **do not assert a performance win without measurements**. Single-command timings are not concurrent 1/3/5/10-NPC performance evidence.
10. Validate C1 movement, combat/knockback, death/inventory persistence, networking and C2-P0 basic move still work; report screenshot and log. **Do not advance to P1-C** unless explicitly approved.

## Limits / safety

- Current B1 `WALK` edges only: no general height transitions, door interactions, stairs/slabs or cross-scan/chunk navigation guarantees. Real Minecraft Per-Edge, partial block and unloaded-chunk safety remain open validation items, carried in root `PENDING_TESTS.txt`.
- Neither a B2 nor B3 path is authoritative after the world changes. Future P1-C must revalidate every segment, chunk and lifecycle state and handle failure/replan.
- Region overhead is not expected to be faster than B2 on 169-node flat terrain. Bound planning work and benchmark on real Forge; performance regressions must be reviewed before acceptance.
