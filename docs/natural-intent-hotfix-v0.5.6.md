# NPCortex v0.5.6-alpha — Natural-language action hotfix

## Why it failed

The old DirectIntent helper used a strict string switch: `wait here` was recognized,
but `wait for me here` was not. The sentence fell back to the model, which could
incorrectly select `follow` despite the player's opposite intent.

## Behavior

* Clear, standalone English owner instructions like `Yui, wait for me here`,
  `Could you please wait right here?`, `hold your position`, `don't follow me`,
  `stay where you are`, and `follow me` are recognized before the LLM request.
* The direct recognizer also handles simple requests to `move_to_player`
  (come here), `look_at_player` (face me), `jump` (hop once), and `wander`
  (take a short walk). These are bounded English commands, not arbitrary actions.
* Unambiguous single-item requests for `pickup_nearby`, `take_offered_item`,
  `equip_item`, `give_item_to_owner`, `drop_held_item`, and `drop_stored_item`
  receive grounded item matching against server observations *after* Qwen's
  reply. Ambiguous item classes (two different swords) become a no-op asking
  for clarification; no free-form Minecraft commands are executed.
* Direct commands invalidate any pending LLM reply and cancel an active
  queued plan. Immediate actions call `WorldActions`; asynchronous movement
  and jump actions use `ActionPlanRunner` for completion/timeout tracking.
* Acknowledgment is based on server execution, not imagined AI output.
* Multi-step commands (`wait here and drop the sword`) and indirect questions
  (`what does wait here mean?`) deliberately skip direct matching and remain
  under the existing 8-step AI planner. The model prompt includes additional
  examples for these requests, but LLM decisions remain probabilistic.
* This is a bounded **English** grammar, not perfect semantic understanding.
  Phrases outside the grammar can still be misinterpreted by the LLM;
  `/npcortex stay` remains the deterministic debug fallback.

## Windows install / test

Close Minecraft and stop the backend first. Backup or commit any local changes.

```powershell
cd F:\AI\NPCortex
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_NaturalIntent_Update_Only_v0.5.6-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\natural-intent-check.ps1
.\scripts\dev-forge.ps1 -Task build
```

Restart the Python backend (for the updated AI prompt) and the Minecraft client.

```powershell
.\scripts\dev-backend.ps1
```

Separate PowerShell window:

```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

### In-game regression

1. `Yui, follow me!` — walk to verify NPC follows.
2. `Yui, wait for me here.` — NPC should **stop** and stay.
3. `Yui, could you please wait right here?` — NPC should still stay.
4. `/npcortex observe` — `following:false` expected.
5. `Yui, come here` — short route to player without permanent follow mode.
6. `Yui, face me`, `Yui, hop once`, `Yui, take a short walk` — corresponding single actions.
7. Put an emerald within 3 blocks and say `Yui, grab that emerald` — pickup should target emerald.
8. Say `Yui, give me one emerald` — transfer only the grounded item.
9. `Yui, follow me!` — resume following.
10. `Yui, wait here and drop the sword` — routed to AI planner as multiple actions;
    not intercepted as only `stay`.
11. `Don't drop the emerald` and `What does wait here mean?` — must not trigger the action.

**Verification scope:** standalone Java 17 intent tests + 78 Python tests locally.
ForgeGradle compilation and in-game behavior still require testing on Windows.
