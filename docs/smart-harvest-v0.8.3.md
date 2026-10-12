# NPCortex v0.8.3-alpha: Smart Tree Harvest and Store-First Gathering

## Behavioral change: gathering never delivers goods without a *separate* instruction

- `Yui, collect 6 sand blocks.` — stores six pieces **in Yui's 9-slot bag** (subject to space, reach, paths and drops). The gather controller does **not** place these items in the player's inventory.
- `/npcortex task status yui` — factual progress (e.g., `4/6`), not model-generated claims.
- `/npcortex inventory` — inspect the NPC bag while nearby.
- `Yui, give me all the sand.` — **separate request** transfers only when explicitly asked.
- `Yui, drop all.` — separate explicit request (also drops held tools; exercise caution).
- `Yui, chop 6 oak logs.` — gather logs and store them; use `give me all the oak logs` later.

Even older saved gather tasks in the v0.8.1 `return` phase finish **without automatic delivery**. A previously supplied phrase such as “collect 6 and bring them to me” is now treated as **collection only**; issue a second give command when ready.

## How intelligent tree gathering works

1. The server scans a **bounded, loaded-only** 12-block area for allowed matching logs.
2. It evaluates the actual line of sight from the NPC, plus possible stand positions on all eight sides of the trunk and small elevation changes.
3. It prefers logs currently reachable, then reachable from an adjacent unobstructed stand, then — **only when necessary** — a directly blocking **natural oak leaf**.
4. It tends to chop **bottom-to-top** on level ground and **top-to-bottom** when standing above part of the trunk, but availability and safe pathing take precedence.
5. Each actual block edit rechecks exact target coordinates, server authority, 4.5/5 block reach and Minecraft raycasts. The generic `mine_nearby` never silently substitutes a different log.
6. After a log breaks, the NPC picks up its verified drops, increments the count and replans.

Only **naturally generated oak leaves** (`persistent=false`) that directly occlude a target log are eligible for limited clearing; no planted/decorative persistent leaves. Clearing at most **8 leaves per task** is optional and uses the same singleplayer/cheats/owner restrictions as mining. This will change the world, so back up a test world.

### Limitations

- Alpha heuristic, **not general-purpose tree felling**, full 3D motion planning, climbing or scaffold building. Tall/high logs that cannot be reached safely still pause the task.
- Oak logs only for smart foliage clearance. No birch/spruce/jungle canopy logic yet.
- NPC may pause if blocked by solids/structures, pathfinding obstacles, unloaded chunks, full bag or owner leaving range; resume after fixing the obstruction.
- Gathering tasks remain bounded to **16 requested items**, 12 block search, 20 block home boundary and 32 block owner proximity.
- Tools, resource allowlists and server authority remain unchanged. `enable_world_edits = true` persists from v0.8.2 new config defaults; pre-existing config files keep their existing value.

## Windows install (overlay onto v0.8.2-alpha)

```powershell
cd F:\AI\NPCortex
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_SmartTreeHarvest_Update_Only_v0.8.3-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\smart-harvest-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

### Suggested in-game test

1. Put a few natural oak trees close to Yui on a **backup world**, alongside some leaves obstructing a trunk. Equip an axe if desired.
2. Via Dialogue: `Yui, chop 6 oak logs.`
3. Run `/npcortex task status yui` several times. Inspect `/npcortex inventory` — Yui retains the logs and **your inventory does not gain them automatically**.
4. Issue `Yui, give me all the oak logs.` after the task is complete.
5. Repeat with `Yui, collect 6 sand blocks.` and ensure sand is stored instead of given.

`./scripts/smart-harvest-check.ps1` runs source/unit tests. A successful **Forge build and in-game integration test on Windows** are still required.
