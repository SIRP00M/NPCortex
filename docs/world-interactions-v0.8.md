# NPCortex v0.8.0-alpha — Advanced World Interaction

Experimental, server-validated actions for **Minecraft 1.20.1 / Forge 47.4.10**. This release adds three bounded actions to the existing 8-step queue. It does **not** implement open-ended autonomous building or full vanilla/modded crafting.

## Actions

| Action | Required `target_item` resource ID | Behavior |
|---|---|---|
| `mine_nearby` | Block ID such as `minecraft:oak_log` | Break ONE visible matching allowlisted block within short reach. Drops stay on the ground. A correct tool is required for tool-gated blocks. |
| `pickup_nearby` | Dropped item ID, not necessarily the original block ID | Existing action: stores a nearby ground drop in the NPC bag. |
| `craft_item` | Output item ID such as `minecraft:oak_planks` | Consumes exact ingredients from the nine-slot NPC bag and atomically inserts the crafted output. |
| `place_block` | Exact BlockItem ID such as `minecraft:oak_planks` | Places ONE safe block **directly ahead of the NPC** at feet level, when the location is empty/supported and no entity intersects. |

All actions are checked again on the server, after the LLM makes a proposal. They cannot execute arbitrary Minecraft commands or receive arbitrary coordinates.

## Supported recipes (initial subset)

| Output | Ingredients (NPC **bag**, not equipped hand) | Yield |
|---|---|---:|
| `minecraft:oak_planks` | 1 × `minecraft:oak_log` | 4 |
| `minecraft:stick` | 2 × `minecraft:oak_planks` | 4 |
| `minecraft:crafting_table` | 4 × `minecraft:oak_planks` | 1 |
| `minecraft:torch` | 1 × `minecraft:coal` + 1 × `minecraft:stick` | 4 |

These recipes use a small auditable `WorldInteractionPolicy` registry; other logs, other plank variants, modded recipes, arbitrary crafting tables and furnaces are **not yet implemented**. An unsuccessful craft does not consume anything.

## Safe world editing

**Disabled by default.** Breaking/placing blocks requires all of:

1. `enable_world_edits = true` under `[world_interaction]` in the Minecraft instance's `config/npcortex-common.toml`.
2. A **singleplayer integrated server** with cheats enabled (owner must have permission level 2).
3. A nearby, living, owned NPC and player (distance at most 8 blocks at execution time).
4. Exact target ID in the built-in allowlist; blocks are rechecked at time of execution.
5. No block entities, arbitrary coordinates, dangerous blocks or unsupported actions.

**Back up test worlds first.** Do not enable world editing on servers or worlds you do not control. This experimental implementation is not integrated with land claims/protection mods and is deliberately disabled on dedicated servers. It is not appropriate for public servers without additional permission/protection integration.

Allowed mining: dirt, grass block, sand, gravel, stone, cobblestone, oak log, oak planks.

Allowed placement: dirt, cobblestone, oak planks, crafting table, stone, sand.

## Windows PowerShell development workflow

Back up any work and close Minecraft/Backend before updating. Expand the Update Only archive at the project root. Then:

```powershell
cd F:\AI\NPCortex
.\scripts\world-interaction-check.ps1
.\scripts\dev-forge.ps1 -Task build
```

For trusted local development scripts blocked by PowerShell:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

After the first launch of the new version, close Minecraft, open `forge-mod/run/config/npcortex-common.toml` (or your **actual CurseForge instance** config) and set:

```toml
[world_interaction]
    enable_world_edits = true
```

Keep `false` outside a backed-up local test world. Restart the Minecraft client/server after editing. Crafting works even when world edits are off.

Start Python backend in terminal 1 and Minecraft in terminal 2:

```powershell
# terminal 1
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1

# terminal 2
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

## Testing in Minecraft

Spawn an owned Yui via `/npcortex spawn yui`, stay within 8 blocks, put suitable blocks/resources nearby, and check:

```mcfunction
/npcortex world status
/npcortex observe
/npcortex world mine minecraft:oak_log
/npcortex item pickup minecraft:oak_log
/npcortex world craft minecraft:oak_planks
/npcortex inventory
/npcortex world place minecraft:oak_planks
```

The placement direction depends on the NPC's **facing**, not on an arbitrary requested coordinate. If the spot is blocked or an entity intersects it, placement fails with a status message. The material must be in NPC bag.

Then try **one multi-step natural-language request** in the NPC Dialogue UI:

> Yui, mine one nearby oak log, pick it up, craft oak planks and place one plank block in front of you.

This requests four validated steps: `mine_nearby → pickup_nearby → craft_item → place_block`. Every step must succeed; the queue stops at failure. Depending on geometry, path, orientation, world edits, and item pickup position, the full chain may not complete on the first attempt. Use debug commands to isolate any failure before modifying the prompts.

### Notes

- The current `target_item` wire field is also used for block and recipe resource IDs. A future wire protocol could rename this to `target_id` without changing the action registry semantics.
- Mining targets are small-range, line-of-sight, nearest-matching blocks; no coordinate input or creative replacement. It does not traverse to distant trees or resource veins.
- Drops from stone may be `minecraft:cobblestone`, not `minecraft:stone`.
- World Observation now provides bounded `mineable_blocks`, `allowed_placeable_blocks`, and `supported_recipes` lists. These are **hints only**, not authorization; the server always verifies the final world state.
- `follow`, `stay`, autonomous threat alerts, Skin Manager, Memory and earlier actions remain supported.
- Mining, placing, and crafting are currently **owner-initiated** actions; not part of background autonomous behavior.
- This work must be compiled and tested in a Forge development client before shipping on GitHub Releases.


## Player-like reach hotfix (v0.8.0-alpha)

For the newer player-reach hotfix, use the updated reach and placement behavior documented in [player-reach-hotfix-v0.8.0.md](player-reach-hotfix-v0.8.0.md). This supersedes the older short-range examples above.
