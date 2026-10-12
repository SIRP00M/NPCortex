# NPCortex v0.8.0-alpha — Player-like Interaction Reach Hotfix

This patch applies on top of **v0.8.0-alpha Compile Fix**. It changes Minecraft-side reach only; the Ollama backend, character packs, save data, and action names remain unchanged.

| World action / observation | Before | After |
| --- | --- | --- |
| `mine_nearby` | About 3.16 blocks, nearby 5×3×5 scan | **4.5 Survival / 5.0 Creative** from NPC eyes to target block center; bounded wider scan with ray visibility |
| `pickup_nearby` | 3 blocks from NPC | **4.5 Survival / 5.0 Creative** from NPC to drop; must have a clear line of sight |
| AI `ground_items` and `mineable_blocks` observation | 3-block pickup and short-range blocks | Mirrors the new reach (mineable block list remains a non-authoritative hint) |
| `place_block` | One block directly ahead | Picks the **farthest safely supported, unobstructed empty space straight ahead within reach**. Never accepts arbitrary positions. May end up nearer than the maximum. |

The owner player's current **Survival/Creative game mode** selects the reach; NPCs have no personal game mode. This is a fixed reach mimicking *vanilla block interaction range*, **not** a request to Forge's potentially modded/custom reach attribute. Item pickup at this distance is an NPC convenience action; players normally need to physically approach dropped items. Crafting is inventory-only, without world reach.

**Important:** `WorldActions`' owner-to-NPC control limit is still **8 blocks**. World editing still requires an opted-in singleplayer world with cheats enabled; all block allowlists, inventory checks, visibility and server-side validation remain enabled. This patch does not add pathfinding toward distant blocks, arbitrary coordinates, or interaction through walls. The wider scan is capped at a five-block horizontal offset.

## Install (PowerShell)

Stop Minecraft and back up the test world. From your existing repo root `F:\AI\NPCortex`:

```powershell
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_PlayerReach_Update_Only_v0.8.0-alpha.zip" -DestinationPath 'F:\AI\NPCortex' -Force
.\scripts\world-interaction-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

`[world_interaction] enable_world_edits = true` remains required for mining/placing. Test with a block or drop at approximately 4 blocks and at more than 5 blocks; the latter must fail. Keep owner near NPC. Use `/npcortex world mine minecraft:oak_log` and `/npcortex item pickup minecraft:oak_log`, or `/npcortex world place minecraft:oak_planks` (provided the NPC bag contains planks). Confirm the placement position is clear, supported, and in the NPC's facing direction.

## Known limitations

- Reaches are based on the NPC eyes/entity position; exact target geometry can make a target at an approximate four/five-block distance unreachable, especially when the **block center** is beyond the limit.
- The placer uses the farthest safe candidate along a **straight, horizontal direction** because the current command protocol has no position or aimed-face parameter. This differs from precise vanilla crosshair-based placement.
- World edits remain disabled by default for new worlds, and only happen as owner-requested actions.
- Compile and runtime tests should be completed in the user's Forge 1.20.1 development instance before tagging any new release.
