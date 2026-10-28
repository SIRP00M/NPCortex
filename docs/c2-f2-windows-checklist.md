# C2-F2 — Windows / Minecraft 1.20.1 Acceptance Checklist

**Use F2 Candidate ZIP as full repository**, do not mix individual classes. Windows Java 17 (Temurin). Operator-only Follow requires no Ollama/Backend unless there is an unresolved prior C0 action. Save a backup world.

```powershell
cd F:\AI\NPCortex
.\scripts\test-c2-f2-natural.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

## A — Natural 8-way Follow

On flat safe loaded ground, with Yui near you, run `/npcortex c2 follow yui <your_player_name>` then walk diagonally (X+Z) 6–8 blocks. Use `/npcortex c2 followstatus yui` and `/npcortex c1 motion yui`. **PASS:** Yui follows along diagonal without an axis-only staircase, turns with visible interpolation before motion, stops near 2.5 blocks and resumes around 4 blocks.

## B — Rolling Local Follow (>8 blocks)

While following, walk 10–20 blocks away on **continuous loaded flat terrain** (do not teleport away / change dimension for this test). **PASS:** Yui advances using repeated safe local routes, reduces separation, and stops at follow distance without teleport or chunk loading. **FAIL:** old pause reason `Target outside local graph reach` or endless plan loops at safe loaded terrain. Inspect `/npcortex c2 followstatus yui`.

## C — Diagonal Collision / Corner Cutting

Make an L-shaped two-block-high solid wall with a diagonal gap in a disposable Creative test area. Walk diagonally behind it. Yui must **not** clip a wall corner or report success while blocked. It may take a longer route or correctly pause unreachable. Verify actual physical path and `c1 motion` receipts, not only `navhier PATH_FOUND`. Beware other open shortcuts.

## D — Waypoint Coalescing and Terrain Regression

Walk 4–8 blocks straight with Yui following; verify fewer visual start/stop beats, no block skipping or clipping. Repeat through a one-block ledge (JUMP_UP) and down (SAFE_DESCEND) on a safe arena: original typed C1 vanilla physics and landing verification must remain. Slabs/Stairs, STEP_DOWN and mixed terrain remain **PENDING** until separately verified.

## E — Ownership / Lifecycle

Run `/npcortex c2 unfollow yui` mid-route, verify stop, then start a new `/npcortex c2 navigate yui <integer_x> <footY> <integer_z>` only when old action journal is resolved (backend required). Cancel or stop while following, combat knockback, save/rejoin, player logout, different dimension, death/despawn; no F2 auto-replay, foreign C1 owner stealing, persistent old tickets or forced chunks.

## F — Performance

With 1/3/5/10 NPC simultaneously following distinct local players/positions, collect **actual Forge server MSPT**, planning latencies, ticket count, replan count and failed-path frequency. Compare with F1 4-way baseline if available. A Java-only microbenchmark is NOT sufficient to claim Forge MSPT pass. Note negative scenarios and unknown performance rather than assuming a PASS.

**PASS criteria:** A–E have reproducible evidence, F measured, no critical ownership/physics regressions; otherwise keep F2 as CANDIDATE and record exact failing commands/logs.
