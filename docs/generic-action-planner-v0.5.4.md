# NPCortex v0.5.4-alpha — Generic Multi-Step Planner

## Summary

The previous v0.5.3 rule only allowed `pickup_nearby → give_item_to_owner` or `pickup_nearby → drop_stored_item`. This version accepts an **ordered bounded plan of 0–8 validated steps**, using any currently registered combination. New future actions do not require adding every possible combination to a whitelist.

Example player request:

> Yui, pick up that iron sword, equip it, and follow me.

Example AI proposal (never a claim of successful execution):

```json
{
  "reply": "I'll pick up the sword, equip it, and follow you!",
  "action": "pickup_nearby",
  "target_item": "minecraft:iron_sword",
  "steps": [
    {"action":"pickup_nearby","target_item":"minecraft:iron_sword"},
    {"action":"equip_item","target_item":"minecraft:iron_sword"},
    {"action":"follow","target_item":""}
  ]
}
```

Only the Minecraft server can confirm steps. Actual server responses appear as `[Step 1/3] [Action confirmed: ...]` etc. A blocked, failed, or timed-out step halts the remaining plan. A new plan cannot replace the active plan for the same NPC. Only the nearby owner can execute actions, with the distance/ownership check repeated on every step.

## Noninstant steps

`move_to_player`, `wander`, and `jump` are **deferred**. They must complete before later steps run. A per-step 120-game-tick timeout prevents waiting forever. A navigation failure or disconnected/out-of-range owner cancels the remainder. Navigation continues with vanilla Minecraft pathfinding, which is limited and can fail. Pending plans are in RAM on the game server and are not persisted through a restart.

## Current action set

- `follow`, `stay`, `look_at_player`
- `move_to_player`, `wander`, `jump`
- `pickup_nearby`, `equip_item`, `drop_held_item`, `drop_stored_item`
- `take_offered_item`, `give_item_to_owner`
- `none` is a no-action response, never a plan step.

**Important:** `equip_item` equips into the NPC's main hand but **does not** grant sword attacks or any combat AI. `follow` enables the existing follow behavior. The `take_offered_item` action now optionally checks the requested ID against the player's actual held item to prevent swapping items between observation and execution.

## Extending with new actions

1. Describe the new action and its allowed argument shape in `backend/src/npcortex_backend/agent.py` (`ALLOWED_ACTIONS`, `TARGETED_ITEM_ACTIONS`, `AGENT_POLICY` and `STEP_SCHEMA`). For non-item arguments, design a new validated schema instead of treating free-form model text as commands.
2. Declare the action and its target policy in `forge-mod/src/main/java/io/github/sirp00m/npcortex/ActionCatalog.java`.
3. Implement it in `WorldActions.execute(...)` (Minecraft server thread). Return `[Action confirmed: ...]` only **after** completing an immediate action; return `[Action failed: ...]` for failure. The generic planner automatically sequences immediate actions without further special cases.
4. If the action is **asynchronous** (navigation, mining with time, crafting animation), add a completion handler to `ActionPlanRunner`, with explicit success/failure predicates and a timeout. Merely starting an action must never count as completion.
5. Add Python tests and in-game tests for item/state changes, inventory limits, ownership, early failure, cancellation, and save/reload.

### Boundaries

- A plan is **at most 8 steps**, not unlimited or a general autonomous agent loop.
- Only registered, pre-implemented actions can run. New item types can use existing inventory actions without new Java code, but entirely new gameplay abilities require new executors.
- Neither the LLM nor Character Packs can inject arbitrary Java/commands, write files, bypass server checks, mine, craft, build or attack in this version.
- World observations are captured **at dialogue time**; the executor re-checks changed live conditions before each step.
- Python model/schema tests have passed; the Forge build and game behavior need verification on your Windows development machine.

## In-game acceptance tests

With the Backend running and a test world loaded:

1. Spawn your NPC and drop a vanilla iron sword within 3 blocks of it, ensuring its NPC bag has space.
2. Say `Yui, pick up that iron sword, equip it, and follow me.`
3. Verify `[Plan complete: 3/3 steps confirmed.]`, NPC holds an iron sword, and follows you. Check `/npcortex inventory` for bag vs held item.
4. Hold a second sword in *your* hand and say `Take this sword, equip it, and follow me.`. Expect the exact requested sword to move from player hand to the NPC, then equip it.
5. Say `Come to me, look at me, then follow me.` and watch any movement step finish before later steps execute. Stay within 8 blocks of the NPC.
6. For failure, request an item not on the ground / in the bag and verify **later steps do not execute**.

When Qwen returns a malformed or unsafe sequence, NPCortex returns a no-op message. Try specifying exact steps and one visible item type, and check backend logs if malformed output persists.

## Windows update

1. Stop Minecraft and the Python Backend, back up the world and SQLite DB, commit your working tree.
2. Extract Update Only into the repo root, **overwriting old files**.
3. `./scripts/generic-plan-check.ps1` then `./scripts/dev-forge.ps1 -Task build`.
4. Restart `./scripts/dev-backend.ps1` and `./scripts/dev-forge.ps1 -Task runClient` in separate PowerShell windows.
5. Keep scripts under `RemoteSigned` for your current user, or use a scoped session exception for trusted scripts only.
