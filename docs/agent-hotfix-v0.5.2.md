# NPCortex Agent Action Validation Hotfix (v0.5.2-alpha)

## Cause
`/v1/agent` previously raised `ModelError("Item target is unsupported for this action")`
when Qwen generated a valid `target_item` (for example `minecraft:emerald`)
combined with an action that does not support an item target (for example
`take_offered_item`, `drop_held_item`, or `none`). The server returned HTTP 502,
misleadingly suggesting Ollama was down.

## Fix
- Preserve **allowlist** of actions and strict namespace validation for item IDs.
- If an otherwise valid model response gives an item target to a non-targeted
  action, change to a safe **none** action with a message asking for one step
  at a time. Do **not** execute the mismatched action or touch inventory.
- Improve the system prompt for requests combining pickup and give.
- Improve error text for any remaining model validation failures.

## Limits
NPCortex has only **one action per conversation turn**, and it does not queue
multi-action plans. `pickup_nearby` moves an item from the ground into the NPC
inventory; `give_item_to_owner` moves it from NPC inventory to player inventory;
`drop_stored_item` drops from NPC inventory onto the ground.

If `/npcortex inventory` already displays `minecraft:emerald`, say:

> Yui, give me one emerald from your inventory.

Otherwise throw an emerald near Yui, and say:

> Yui, pick up the emerald on the ground.

After it succeeds:

> Yui, give me the emerald from your inventory.

Check for `[Action confirmed: ...]` in-game; a chat response alone does not
prove an item action was performed.

## Install (PowerShell)

Stop backend via `Ctrl+C` and close Minecraft if desired. Apply the Update Only
ZIP in repository root; Forge rebuild is **not** required for this backend-only
hotfix.

```powershell
cd F:\AI\NPCortex
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_AgentHotfix_Update_Only_v0.5.2-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\agent-hotfix-check.ps1
.\scripts\dev-backend.ps1
```

If `.ps1` scripts are blocked, use (once for your account):

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

Do not expose local unauthenticated NPCortex backend to the internet.
