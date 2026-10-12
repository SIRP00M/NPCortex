# NPCortex v0.8.1-alpha — Intelligent Task Execution (Experimental)

This is an incremental release on the **v0.8.x** branch. It extends **v0.8.0-alpha + Player Reach Hotfix**, rather than beginning a v0.9 release.

## What is included

- **Long-running bounded gather tasks:** Minecraft's server handles the task independently of the 8-step Qwen dialogue planner, including moving to resource blocks, mining, collecting drops, walking back to the owner and delivering items.
- **Per-NPC persisted progress:** task ID, item, quota, collected count, search origin and stage live in the NPC's Forge persistent entity data in the world save. Loaded tasks continue when the owner is back in the same dimension and near enough; paused tasks require explicit `resume`.
- **Stop, status and resume:** cancellation halts pathfinding and leaves already acquired items in the NPC inventory. `follow` and `stay` override a gather task.
- **Simple English dialogue shortcut:** try `Yui, collect 10 oak logs and bring them back to me.` in the dialogue UI. The task is recognized and validated by Java server code, not executed as arbitrary model output. The rest of the dialogue system remains unchanged.
- **Per-NPC selection in commands:** optional character ID or UUID, using the loaded-owned-NPC selector from the Autonomy Manager. Ambiguous duplicate character IDs require a full UUID.

## Commands (Singleplayer, cheats enabled)

```mcfunction
/npcortex task gather minecraft:oak_log 10
/npcortex task gather minecraft:oak_log 10 yui
/npcortex task status
/npcortex task status yui
/npcortex task cancel yui
/npcortex task resume yui
```

**Important:** default `gather` targets the nearest owned loaded NPC; the optional suffix targets the loaded NPC by Character ID or full UUID. The NPC must be **within 8 blocks** of you to **start** a gather job, even if you selected it by name. Use `/npcortex auto list` to find UUIDs.

### Suggested test sequence

1. **Back up a test world.** Close the client, open `forge-mod/run/config/npcortex-common.toml`, set `[world_interaction] enable_world_edits = true`, then restart Minecraft. The server still requires Singleplayer + Cheats.
2. Spawn Yui, place several **separate oak logs or oak-log blocks** about 5–10 blocks away on walkable terrain. Leave an open route around the logs and space in the NPC's nine-slot bag and your own inventory.
3. Stand within 8 blocks of Yui, run `/npcortex task gather minecraft:oak_log 3`.
4. Observe navigation, block breaking, ground-drop pickup and `/npcortex task status`. Wait for her to return and transfer **3 logs** to your inventory.
5. Try cancelling a longer task: `/npcortex task gather minecraft:oak_log 10` → `/npcortex task cancel`. It must stop and keep already collected logs.
6. If it pauses because no reachable logs are loaded, add more resources near its original search area, then run `/npcortex task resume`.
7. Optionally restart the test world mid-task and check `/npcortex task status` for restored progress.
8. Test the simple dialogue instruction: `Yui, collect 10 oak logs and bring them back to me.` (needs a nearby owned NPC and an enabled world-edit configuration).

## Safety and realistic limitations

- **This is a prototype gatherer, not a general autonomous builder or Minecraft bot.** Supported self-drop resources for v0.8.1 are `minecraft:oak_log`, `minecraft:dirt`, `minecraft:sand`, `minecraft:cobblestone`, and `minecraft:oak_planks`. Nonmatching drops (e.g., stone → cobblestone) are deliberately excluded; arbitrary modded resources/ores are not supported yet.
- Task count is **1–16 items**, not an unlimited mining loop; one task per NPC. Per-NPC task execution checks ownership and game mode, and **never teleports or force-loads chunks**.
- Searches **loaded blocks up to 12 blocks around the NPC**, within **20 blocks of the task's starting location**. Requires plausible adjacent walkable positions. The NPC uses normal pathfinding and can fail on complex terrain, caves, lava, foliage, steep terrain or blocked entrances.
- While a gather task is running, keep the owner **in the same dimension and within 32 blocks** of the NPC. Moving away or logging off suspends execution without discarding saved progress. The NPC requires a loaded chunk to tick.
- Mining still enforces the same vanilla-like reach and raycast checks as v0.8.0. It only edits blocks on the allowlist, while Singleplayer + Cheats + opt-in remain mandatory.
- An NPC that cannot reach resources, has a full bag, or cannot deliver the exact quota to your inventory **pauses rather than claiming completion**. Clear the obstacle and use `resume`; `cancel` keeps items acquired so far.
- NPC will attempt to return to the **owner's current position**; return is not guaranteed if the owner stands on unreachable terrain. No land claims, storage chests, farms, forests beyond loaded radius, persistent cross-dimension movement, hunger, equipment repair, or house construction are implemented.
- Direct dialogue shortcuts are currently **English-only and narrow**; e.g., `collect 10 oak logs` works, `gather some wood` is intentionally ambiguous and handled by the existing AI pipeline, which does not yet have a native gather-job action.
- **Persistence and navigation require in-game confirmation.** The bundled source-level/policy tests cannot prove Forge build compatibility, real-world pathfinding or chunk-unload behavior. Run `./scripts/dev-forge.ps1 -Task build` and the tests above on your Windows installation before tagging a release.

## Developer tests

```powershell
cd F:\AI\NPCortex
.\scripts\intelligent-task-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-backend.ps1   # separate window if using AI conversation
.\scripts\dev-forge.ps1 -Task runClient  # another window
```
