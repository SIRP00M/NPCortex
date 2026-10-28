# NPCortex C2-P1-B1 — Traversability Graph (Architecture C)

## Scope and dependencies

This is **P1-B1 only**, based on P1-A World Representation and the C2-P0
Precision Movement baseline. It introduces a bounded, immutable, deterministic
4-connected **traversability graph** with a strict live Forge-server collision
adapter. It does NOT implement A* search (B2), region-level routing (B3),
`navplan` (B4), waypoint movement (P1-C), Qwen changes or world mutation.

### Components

- `C2TraversabilityGraph`: pure Java graph whose nodes preserve `(x,supportY,z,footY)`;
  only `WALKABLE` cells can become nodes. Duplicate, out-of-area and invalid
  snapshots are rejected. Entire `UNLOADED` columns are excluded even if input
  is inconsistent. Immutable `nodes()`, `edges()` and `neighbors(node)`.
- `C2GraphWorldProbe`: server-thread-only adapter that captures a **new** P1-A
  snapshot, confirms both chunks are loaded and validates a continuous
  player-width/player-height swept AABB through `ServerLevel.noCollision`.
  Unknown/uncertain clearance returns false. Does not force-load chunks.
- `/npcortex c2 navgraph <npc> [radius]`: operator-only *read-only* B1 gameplay
  diagnostic. Does not perform route search or instruct NPC to move.

### Edge rules (intentionally conservative)

- Four cardinal directions; only immediate adjacent columns.
- Identical footY (epsilon 0.0001); 1-block cost, directed pairs included only
  after both directions pass the clearance probe.
- No diagonal corner-cutting, jumps, steps, drops, ladders, water or scaffolds.
  These require independently verified movement semantics and a future approved
  scope. Multiple heights can coexist above one column without a made-up
  vertical edge.
- Physical clearance at scan time does **not** guarantee the future world
  stays unchanged. P1-C will require fresh pre-execution checks per waypoint.
- There is no background scan, per-NPC tick scan or Ollama call.

## Windows commands and acceptance

Run in PowerShell after extracting the FULL repository to `C:\path\to\NPCortex`:

```powershell
cd C:\path\to\NPCortex
.\scripts\test-c2-p1b1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-backend.ps1
# In a separate PowerShell:
.\scripts\dev-forge.ps1 -Task runClient
```

Minecraft tests (when Yui has spawned and is alive):

```mcfunction
/npcortex c2 navscan yui 4
/npcortex c2 navgraph yui 4
/npcortex c2 navgraph yui 6
```

1. On safe flat terrain, `nodes > 0` and `directedWalkEdges > 0`.
2. Build a full-height solid wall dividing Yui's scanned area. `navgraph`
   should remove unsafe crossing edges; totals alone **cannot prove** exact
   edge correctness. Capture screenshots and optionally build a 1-block-wide
   corridor to compare.
3. Near partial blocks, hazardous blocks and different-height platforms,
   the output must remain conservative; planner must never assume a jump or
   diagonal edge is executable.
4. Despawn Yui, retry `navgraph`, verify rejection and no server crash. Respawn
   and scan again. Repeat separately for 3–5 NPCs if practical.
5. Capture actual MSPT in F3 or profiler and repeated `elapsedMs`; an isolated
   microbenchmark is **not** an acceptable Forge/10-NPC performance claim.
6. No `navplan`, path, action or C1 gameplay behavior change is expected.

### Quality Gate boundaries

**Local pure JVM graph contract** and C0/C1 regression results are tracked in
`docs/c2-p1b1-test-report.md`. Forge compile, live collision correctness,
per-coordinate P1-A partial support tests and actual unloaded-chunk gameplay
MUST be represented separately. See `PENDING_TESTS.txt`.

Stop at this B1 candidate until the user has checked the Windows build and
Minecraft read-only graph behavior. Do not advance to B2, B3 or P1-C without
reviewing/closing the B1 Quality Gate and agreed scope. No GitHub operations.
