# NPCortex v0.5.3-alpha — Bounded Multi-Step Actions & Memory Identity

## Why the emerald stopped after pickup

Prior to v0.5.3, `/v1/agent` returned **one** `action` and `target_item` per message. The Minecraft server executed that single action, so "Pick up that emerald and bring it to me" could perform pickup but never the handoff.

## What's new

The backend returns an additional `steps` array while retaining the legacy `action` and `target_item` fields. Java accepts and revalidates plans of at most **two actions**. The only two-step plans allowed in this version are:

- `pickup_nearby` → `give_item_to_owner` (same nonempty, namespaced `target_item`)
- `pickup_nearby` → `drop_stored_item` (same nonempty, namespaced `target_item`)

All other requests remain one action per turn. This is **not** a general autonomous planning or navigation engine.

Qwen 3 8B is prompted to return the two-step sequence; a narrow English fallback also upgrades a one-step `pickup_nearby` response for an explicit "pick up [named item] and bring/give it to me" request **only if that exact item is present in the current server-grounded `ground_items` snapshot**. If the item was picked up in a prior turn and is now only in `npc.inventory`, the fallback can propose one `give_item_to_owner` step instead. No fallback is attempted for ambiguous/unnamed items, wrong item IDs, or unsafe model output.

The Minecraft server executes steps in order on its server thread. Only an actual `[Action confirmed: ...]` allows the next step to run. Failure or `started` stops the plan. This means item inventory cannot silently be consumed or claimed transferred when a prior step fails.

**Physical handoff**: `give_item_to_owner` transfers one matching item from the NPC bag **directly to the owner's inventory**, without visually throwing it on the ground. Use `drop_stored_item` if a ground drop is desired.

**Range**: an NPC must be within 8 blocks of its owner for any action, and a dropped item must be within 3 blocks of the NPC for pickup. Put both NPC and player close to the emerald when testing.

## Example backend response

```json
{
  "reply": "I'll pick it up and bring it to you!",
  "action": "pickup_nearby",
  "target_item": "minecraft:emerald",
  "steps": [
    {"action": "pickup_nearby", "target_item": "minecraft:emerald"},
    {"action": "give_item_to_owner", "target_item": "minecraft:emerald"}
  ],
  "model": "qwen3:8b",
  "character_id": "yui",
  "character_name": "Yui"
}
```

The in-game dialogue displays **server-confirmed** step results, not a premature model assertion that it succeeded.

## Installation on Windows

1. Exit Minecraft and stop the Python backend with Ctrl+C. Back up any modified source and game worlds.
2. Extract `NPCortex_MultiStep_Update_Only_v0.5.3-alpha.zip` **over** `F:\AI\NPCortex` (the ZIP paths start at repository root).
3. Run diagnostics, then start backend and build Forge.

```powershell
cd F:\AI\NPCortex
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_MultiStep_Update_Only_v0.5.3-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\multistep-check.ps1
.\scripts\dev-forge.ps1 -Task build
```

Start the backend in PowerShell terminal #1:

```powershell
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1
```

In terminal #2, after a successful build:

```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

If PowerShell blocks scripts and your policy permits it, run once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

## Test checklist

- Spawn or select Yui (owner must be near NPC): `/npcortex spawn yui`
- Give the NPC access to a **dropped** emerald within 3 blocks (use Creative inventory to toss one).
- Verify `/npcortex inventory` is empty (so the handoff truly needs pickup).
- Right-click Yui: `Yui, pick up that emerald and bring it to me.`
- Expect `[Step 1/2] [Action confirmed: collected ...]` and `[Step 2/2] [Action confirmed: transferred ...]`.
- Check `/npcortex inventory` and the **player's** inventory. Expect NPC bag empty or one fewer emerald and player inventory +1 emerald.
- Negative test: if the NPC bag is full, or no emerald is close enough, step 1 must fail and step 2 must **NOT** run.

The Python unit/HTTP tests are run with mocked model responses. A **real Forge build and in-game test must still be done on Windows**; none is claimed by this ZIP alone.

## Limitations and next steps

- Commands requiring navigation across multiple blocks do not yet have an async task queue or a follow-up completion signal. This release only chains **immediate** inventory actions.
- No automatic chest use, mining, crafting, combat, or arbitrary action scripts.
- NPC inventory is in Minecraft world save; dialogue/memory is in SQLite.
- Local backend has **no authentication**. Bind it only to loopback (`127.0.0.1`); do not expose it publicly.

## Memory identity bug fix (player vs NPC)

A note like `My name is Kenji` was previously added to the unqualified
`SAVED NOTES` block and could be misinterpreted as the NPC speaking.

This update applies a stronger role/identity boundary in **both** `/v1/chat`
and `/v1/agent`: the NPC identity comes from the character pack (`Yui`),
while saved notes are explicitly marked as **PLAYER-written**. First-person
words (`I`, `me`, `my`) in saved notes refer to the **player**, not to Yui.

In **ESC → NPCortex Manager → Memory**, existing notes are retained. No DB
schema migration is required. You can keep `My name is Kenji`; for even clearer
semantics, you may edit (delete and re-add) it as `The player's name is Kenji.`.
To describe Yui, save a note such as `Yui likes art.`.

**Test:** Restart the backend, ask `Yui, what is your name?` and then
`Yui, what is my name?` via NPC dialogue. Expected Yui / Kenji. A prompt
boundary improves reliability but does **not** guarantee an LLM cannot
misinterpret notes or prior confused chat history.

**Note on old chat history:** The SQLite database also stores previous replies.
If Qwen keeps repeating a mistaken identity, older dialogue may be influencing
it. Use **ESC → NPCortex Manager → Memory → Clear Chat**, click twice to
confirm. It erases only the selected NPC/player transcript; it keeps Memory Notes
and the friendship counter. This is permanent for that chat pairing, so back up
your SQLite database before using it if needed.
