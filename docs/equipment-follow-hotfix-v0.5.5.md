# NPCortex v0.5.5-alpha — Equipped Item and Follow/Stay Hotfix

Baseline: `v0.5.4-alpha`. Forge 1.20.1 / Java 17.

## Bugs fixed

1. `equip_item` takes a stack out of the nine-slot bag and places it in the NPC's **main hand**, but the `give_item_to_owner` and `drop_stored_item` executors previously only scanned bag slots. A held iron sword therefore appeared to be missing.
2. A model-proposed `give_item_to_owner` could fail or even contradict a player's explicit request to **drop** an equipped item.
3. A clearly worded **Stay here** request could be classified incorrectly by the model; an older in-flight plan could also switch Follow back on.
4. The model sometimes replied *Here you go!* even when the one-step action had failed. All action attempts now display server-generated execution status instead.

## Changed behavior

- `give_item_to_owner` transfers a matching item from main hand OR bag to the player's inventory (prefers matching held item when an exact ID was requested). The item is not dropped.
- `drop_stored_item` drops a matching item from main hand OR bag (prefers matching held item when an exact ID was requested).
- `drop_held_item` still drops the equipped main-hand stack.
- `equip_item` confirms when the exact requested item is already equipped rather than incorrectly failing.
- `WorldObservation.describe()` shows `NPC hand` and `following` as separate diagnostic fields.
- Simple, explicit `Stay here`, `Stop following me`, and `Follow me` commands are handled server-side without waiting for Qwen; they cancel an earlier queued plan. Compound requests still use the multi-step AI planner.
- A newer direct order invalidates old in-flight AI replies so they cannot reactivate Follow later.
- The Python backend narrowly reconciles *single-step* `Drop the sword` (and matching named equipped items) to `drop_held_item` when the server observation confirms the equipped type. It never interprets negated or multi-step commands this way.

## Install (close Minecraft and Backend first)

```powershell
cd F:\AI\NPCortex
# Commit or back up any uncommitted changes before extracting.
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_Equipment_Follow_Hotfix_Update_Only_v0.5.5-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\equipment-follow-check.ps1
.\scripts\dev-forge.ps1 -Task build
```

Start the backend in one terminal and the Forge development client in another:

```powershell
.\scripts\dev-backend.ps1
```

```powershell
.\scripts\dev-forge.ps1 -Task runClient
```

If scripts are blocked, the recommended policy for scripts you trust is `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`; don't permanently enable `Bypass`.

## Test in a **test world**

- Spawn or select your existing Yui and equip an iron sword, then run `/npcortex inventory`. The sword should be listed under `hand`, not necessarily bag.
- Try `Drop the sword.` through Yui's dialogue UI. It should physically drop the equipped sword into the world and show a server confirmation.
- Equip another sword; run `/npcortex item drop minecraft:iron_sword` as a deterministic debug test if model behavior is uncertain.
- Equip again and say `Yui, give me the iron sword.` It should go into the player's inventory; Yui's main hand becomes empty.
- Say `Yui, follow me!` and walk away, then `Yui, stay here.` Walk away again. Also test `/npcortex stay` and `/npcortex observe` to validate the follow state independently of the model.
- Try `Yui, stay here and look at me.` This compound command is intentionally still handled by the LLM/planner.

**Notes:** This patch changes both the Forge sources and Python backend; restart both and rebuild Forge. Does not change or migrate SQLite. Standalone tests cannot prove Forge compilation or the final in-game behavior.
