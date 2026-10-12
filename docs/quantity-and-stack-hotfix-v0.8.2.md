# NPCortex v0.8.2-alpha — Quantity & Full-Stack Hotfix

This is an incremental fix based on v0.8.1-alpha. **It fixes the mismatch between player-requested quantities and one-step model actions.** No new memory format, character-pack schema, or network packet types are introduced.

## What changed

- Natural-language counted gathering now routes to the Minecraft-server Gather Task Controller; the ordinary `mine_nearby` action still mines **one block**. Supports `collect 6 sand blocks`, `mine 6 blocks of sand`, `gather 10 oak logs`, and polite forms addressed to any NPC name with a comma. **The NPC does not claim success until the server reports actual item collection/delivery.**
- `drop all` / `give me all` moves **all items across NPC held main hand and nine-slot bag**. Also supports `drop all sand`, `give me all your diamonds`, and exact numbers such as `give me 6 sand blocks`. This is server-validated in dialogue, not an instruction for Qwen to repeat a one-item action.
- Bulk transfer/drop never silently discards items: only successfully spawned drops are removed from the NPC. Delivery counts actual accepted items, retaining any not accepted by the player's inventory. Failed or partial outcomes are labeled explicitly. A bulk command is blocked while the NPC's gather task or step plan is active.
- `[world_interaction] enable_world_edits` now defaults to **true** when the game generates a new NPCortex config. This is for development convenience; Minecraft still checks **singleplayer + cheats + NPC owner** on each edit. **Your existing TOML is not reset**; run `scripts/enable-world-edits.ps1` once with Minecraft closed to change it. Back up any world you don't want an AI to edit.

## In-game dialogue examples

```text
Yui, collect 6 sand blocks and bring them back to me.
Yui, mine 6 blocks of sand.
Yui, give me all the sand.
Yui, give me 6 sand blocks.
Yui, drop all.
Yui, drop everything.
```

`/npcortex task status yui` shows real gathering progress. A mining request may pause if resources or a path cannot be found; do not treat a response like "I'll collect them" as completion. `drop all` intentionally drops **equipment in NPC's hand too**—use in a test world.

### Scope

- Default config `true` does **not** disable singleplayer/cheats checks and does **not** auto-edit old configs.
- One-shot `mine_nearby` and `give_item_to_owner` / `drop_stored_item` actions remain one-unit operations for existing integrations. Counted and all-quantity dialogue uses the new deterministic server paths.
- Mining is bounded by original gather-task search radius 12 blocks, home radius 20, total quantity <=16, and loaded chunks. No arbitrary block digging beyond the allowlist.
- "Give all" is bounded to a maximum of 640 items per request and may partially transfer if the player's inventory has no space. This does not transfer armor/off-hand; it transfers NPC **main hand + nine-slot bag**.
- Only **English quantity commands** are recognized with the strict parser. Complex multi-part commands still need individual task steps and may not be recognized; a malformed or unsupported command is not treated as a successful gather.

## Validation

```powershell
cd F:\AI\NPCortex
.\scripts\quantity-hotfix-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**Forge compilation and in-game behavior must be verified on your Windows computer.** Pure-Java tests cannot guarantee correct Minecraft inventory and entity semantics.
