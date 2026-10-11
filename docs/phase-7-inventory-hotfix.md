# NPCortex v0.5.1-alpha — Inventory Awareness Hotfix

This patch fixes the Phase 7 discrepancy where `/npcortex inventory` could list an emerald but the model falsely said the NPC had none, and where `drop_held_item` could not drop from the nine-slot bag.

## Root cause

- Phase 7 sent only `npc.stored_items` (occupied slot count) to the model. The item identity was missing.
- Minecraft has separate **ground item entities**, **nine bag slots**, and **equipped hand items**. Tossing an emerald on the ground did not automatically insert it into the NPC bag.
- The original `drop_held_item` affects the equipped hand only. The old `give_item_to_owner` transfers one item to the **player's inventory**, not the ground.

## Fix

- The server includes `npc.inventory` with `slot`, registry `id`, and `count`, plus `npc.holding_count` and `ground_items` within 3 blocks. The backend strictly sanitizes these before supplying them to the LLM. This is an observation **at chat time**.
- The AI action JSON accepts `target_item`, e.g. `minecraft:emerald` for targeted pickup, equipment, giving, or dropping.
- New `drop_stored_item` drops **one** item from the NPC bag to the ground; it does not require equipping it first. `give_item_to_owner` gives one to the player's inventory.
- `/npcortex inventory` now prints the name and shortened UUID of the selected (nearest owned) NPC **plus its hand item**. This helps distinguish two nearby Yui instances.
- `/npcortex observe` now shows bag contents and number of ground items.
- Developer-only commands (cheats on, permission level 2) allow testing actions independently of the LLM:

```text
/npcortex item pickup minecraft:emerald
/npcortex item drop minecraft:emerald
/npcortex item give minecraft:emerald
```

The commands above target the **nearest owned** NPC; to use a specific one, stand near it and move the others away. These commands still run the same server-side owner/distance validation as AI actions (within 8 blocks). They are diagnostics, not a substitute for the AI agent.

## In-game regression sequence

1. Stop Minecraft and the Python backend. Back up the world save. Install the `Update Only` ZIP over the existing repo.
2. Restart Python backend (old processes retain old code). Build and launch Forge.
3. Drop an emerald within **3 blocks of the NPC**. Run `/npcortex inventory`. If the bag is empty, this is correct: dropping to the ground is not automatic pickup.
4. Tell the NPC: `Yui, pick up the emerald on the ground.` Wait for `[Action confirmed: collected ... minecraft:emerald ...]`. If necessary run the debug `item pickup` command to isolate LLM selection problems.
5. Run `/npcortex inventory` and `/npcortex observe`; both should list the stored emerald.
6. Ask: `Yui, do you have an emerald?` Its world snapshot now includes `minecraft:emerald` and its count.
7. Tell it: `Yui, drop one emerald from your inventory onto the ground.` Expect `[Action confirmed: dropped 1 minecraft:emerald ...]` and a visible drop. Or use `/npcortex item drop minecraft:emerald` to test without Qwen.
8. Separately try `Yui, give me one emerald.` This transfers **one emerald to your inventory**, not to the ground.

**Important:** The model selects at most one action per turn. If you say “pick it up and give it back,” you must give two separate requests. The NPC cannot pick up items further than 3 blocks, take from chests, or perform unrestricted player actions. Actions can fail if item locations change while Qwen responds. The game server is authoritative and always prints an action confirmation/failure.

## Install / build (PowerShell)

```powershell
cd F:\AI\NPCortex
# Only if Windows still blocks local PowerShell scripts:
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

# Close the previous backend and game first, and unzip the patch.
.\scripts\inventory-hotfix-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Open the backend in a different terminal using `.\scripts\dev-backend.ps1`.

## Validation and limitations

Python unit tests cover item identity in LLM snapshots, new targeted action response, malformed item IDs, and compatibility with older two-field model replies. The Forge source must be compiled and tested on a local Forge 1.20.1 / JDK 17 setup: it has **not** been compiled here due to missing ForgeGradle dependencies. Do not replace a working save before backing it up.
