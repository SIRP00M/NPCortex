# NPCortex Terrain-Aware 3D Traversal — Sprint 1 (Architecture C)

## Status and scope

Candidate only: Windows Forge compile, real Minecraft VoxelShape semantic accuracy,
terrain re-scanning, navgraph regression, and server MSPT still need user acceptance.
Only the terrain observation/inspection contract changes. This sprint **does not**
enable STEP_UP, STEP_DOWN, JUMP or DROP edges, or any new movement action.

## Windows setup

Back up the previously accepted P1-D repository and world first. Extract the new full
repository to `F:\AI\NPCortex`, then use PowerShell with Temurin Java 17:

```powershell
cd F:\AI\NPCortex
.\scripts\test-terrain-s1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The terrain inspection command is read-only and **does not require Ollama**.
For previously available C2 navigation execution tests, start Backend separately:
` .\scripts\dev-backend.ps1 `.

## Command / coordinate semantics

```mcfunction
/npcortex c1 info yui
/npcortex c2 navscan yui 4
/npcortex c2 terraininspect yui 80 68 -62
/npcortex c2 navgraph yui 4
```

The `terraininspect` coordinates are the **support block** position, not the feet
coordinate. For example, if a normal full block is at Y=68, expected footY=69.0;
a bottom slab at support Y=68 has footY=68.5. Use F3 to locate Yui and inspect
blocks within **X/Z 6 blocks and support Y +/-2** of her current support block.
Outside this observation window the command must report `OUT_OF_SCAN`.

Output: `footY`, `condition`, `support`, `coverage`, `headroom`, `risk`,
`reason`, `shapeBoxes`, `traversal=NOT_VERIFIED`. These observations are **not**
a promise of inter-cell reachability. `PARTIAL_SUPPORT` is intentionally fail-closed.

## Gameplay acceptance scenarios

| Scenario | Expected observation / safety invariant |
| --- | --- |
| Full grass/stone/dirt block | FootY = supportY+1.0; support FULL, headroom CLEAR when unobstructed |
| Bottom slab | FootY = supportY+0.5 when free; FULL footprint |
| Top/double slab | FootY = supportY+1.0 for stable, unobstructed top |
| Stair different facings, inner/outer corner | Exact shape inspected; unsupported footprint may be PARTIAL_SUPPORT, **never silently promoted to WALKABLE** |
| Composite same-height collision boxes | Only FULL when their union covers the entire player footprint |
| Ceiling low enough to overlap Player AABB | OBSTRUCTED; reason HEADROOM_BLOCKED or COLLISION_BLOCKED |
| Hole one block lower | Correct lower standing surface; no fabricated STEP_UP or STEP_DOWN edge |
| Hazard / water / lava / magma | HAZARDOUS, not treated as safe walking node |
| Place/destroy/replace block | Fresh terraininspect/navscan reflects changed world, not stale cache |
| Target beyond bounded scan | OUT_OF_SCAN, no force loading |
| Unloaded chunk boundary | UNLOADED/UNKNOWN, no force loading; still NEEDS actual Minecraft gameplay evidence |
| Previous flat open navgraph/navplan/navhier | Remain correct (flat WALK regression) |
| Prior C2-P1-D navigate and cancel | No regression in movement, dynamic replan or post-cancel new goal |

Record command, exact block state, full response, and screenshots for at least
full block, bottom/top slabs, multiple stair orientations, hazard, and headroom.
Per-coordinate **semantic** gameplay acceptance remains OPEN until reported.

## Performance and quality gate

- Repeat `/npcortex c2 navscan yui 6` 5 times on comparable terrain; record
  minimum/median/max elapsedMs and compare with P1-D baseline.
- Inspect one composite / stair-rich location and compare navgraph node counts.
- If convenient repeat **sequential** diagnostics for 1/3/5 NPC. Do not confuse
  scan elapsedMs with live concurrent Server MSPT (1/3/5/10 target).
- No claim of full terrain accuracy, unloaded chunk or performance PASS without
  measured Windows results. Keep open items in `PENDING_TESTS.txt`.
- Fail any scenario if a known unsafe or unloaded position becomes WALKABLE.
- Do not begin Sprint 2 unless Sprint 1 core terrain acceptance is confirmed.

## Recovery/limitations

Changing terrain classification may make some previously traversable edges
conservatively unavailable. Do not weaken the guard to make a failing route pass.
Only the loaded, dimension-correct, living NPC is eligible. Diagnostics reject
active C1 movement; snapshots remain bounded and read-only. The next sprint will
prove actual jump/step execution via the existing C1 Vanilla physics.
