# NPCortex C1-P1-C Fix #1 — Selective Vanilla World Tick

**Status: IMPLEMENTATION CANDIDATE. Windows Forge compile and Minecraft gameplay gates are OPEN.**

## Why this fix exists

- In the C1-P1-C baseline, a user verified that `/npcortex c1 damage yui 3` reports `damageAccepted=false`, with `health=20` unchanged. There are 20 health points in 10 standard player hearts.
- The user also verified that an idle NPC sometimes floats, but `/npcortex c1 settle yui` activates gravity and lands them.
- Vanilla Minecraft 1.20.1 `ServerPlayer.tick()` decreases private `spawnInvulnerableTime`; C1-P0 had overridden `tick()` to a no-op to avoid synthetic-connection risks. This is strong source evidence of the damage rejection but other causes (GameMode, invulnerable abilities, Forge events) must still be checked in-world.
- `ServerPlayer.doTick()` is the separate connection-driven phase, with `Player.tick` and player status/inventory networking. It must NOT be enabled blindly for an unlogged custom ServerPlayer. This fix restores only world-phase `super.tick()`.

## Modified modules

1. `C1PlayerAvatar.tick()` now executes Vanilla world-phase `ServerPlayer.tick()` and asks P1-A to check idle floor support. `doTick()` remains a deliberate no-op. The `die()` override, C1-P0 passive connection, C1-P1-A commands and C1-P1-B transactional inventory are retained.
2. `C1P1MotionRuntime.onVanillaWorldTick`: one support probe every **4 server ticks** for idle live NPCs in loaded chunks; if unsupported, it resumes the *same* P1-A settle mechanism. No new chunk loading or AI calls. Existing active movement sessions are not interrupted or duplicated.
3. Motion's existing travel loop now forwards authoritative movement deltas to Vanilla `ServerPlayer.doCheckFallDamage` after each tick's movement iteration. Running the callback after the iterator is essential because death removes sessions.
4. `/npcortex c1 lifecycle yui` adds GameMode, flying/invulnerable abilities, and noGravity diagnostics. No record schema changes or additional user commands required.

### Test procedures (Windows / Minecraft Forge 1.20.1 / Java 17)

**Before testing:** Use a COPY of a test world, ideally a flat safe area. Back up the world before keepInventory=false, falling, death, or dimension-change tests. Disable unrelated gameplay mods during initial smoke testing. Do **not** push to GitHub until all relevant quality gates pass.

```powershell
cd C:\path\to\NPCortex
.\scripts\test-c1-p1c-vanilla-tick.ps1
.\scripts\test-c1-p1-lifecycle.ps1
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

#### Gate A — Damage (must pass first)

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 lifecycle yui
```

Wait at least **4 seconds** after spawning: Vanilla players are briefly invulnerable immediately after spawn. Then:

```mcfunction
/npcortex c1 damage yui 3
/npcortex c1 lifecycle yui
```

Expected without armor/effects: `damageAccepted=true`; health near `17/20`. Vanilla hurt cooldown may reject an immediate second hit; wait 1 second before retrying. `lifecycle` should show `gameMode=SURVIVAL`, `abilityInvulnerable=false`, `flying=false`, `noGravity=false` (format of GameMode may vary). If `damageAccepted=false` persists, capture `lifecycle` diagnostics and `latest.log`; stop before testing death and return to Audit. Do not force damage by setting health directly as a substitute for fixing `hurt()`.

#### Gate B — Gravity / floor removal

```mcfunction
/npcortex c1 motion yui
/npcortex c1 jump yui
/npcortex c1 motion yui
```

Allow the jump to finish and the player to settle. From a **low, safe test platform**, remove the supporting block(s) under the NPC in Creative while Yui is otherwise idle, and watch that Yui falls and lands without `/npcortex c1 settle`. Confirm `/npcortex c1 motion yui` shows grounded and a changed Y coordinate. No chunk may be force-loaded to finish a fall.

#### Gate C — Fall Damage (separate high-risk test)

Use a safely prepared *5-block platform*, an empty Inventory, and backups. Make Yui fall while the chunk is loaded, and verify landing deducts HP using the Vanilla fall-damage pathway. Observe `lifecycle`, any Forge Damage Event and `latest.log`. Do not claim this feature passed based on a 1–2 block drop; insufficient fall height may legitimately cause no damage. If the NPC dies, stop to verify the current P1-C death/respawn pipeline before continuing.

#### Gate D — No regression to previously accepted systems

- Check `/npcortex c1 turn yui 180`, `/npcortex c1 move yui forward 3`, `/npcortex c1 jump yui`, and `/npcortex c1 rotation yui`.
- Check `give/equip`, Inventory, Transaction Replay (including after Save and Quit), and equipment rendering.
- Save and Quit; rejoin; check Position, Head/Body Rotation, World/Actor/Profile UUID, Two-Phase Network Sync and Automatic Repair.
- Repeat with Yui/Hina/Ai spawned together; independence of Movement, Rotation, Inventory and Lifecycle. Optionally profile 1/3/5/10 NPC with built-in TPS/MSPT information, but this ZIP includes no measured benchmarks.
- Only after damage/physics passes, test both `keepInventory=true` and `keepInventory=false` through P1-C kill/respawn in the **backup** test world, and controlled dimension transitions.

## Hard limitations and rollback rules

- `super.tick()` invokes Vanilla world-phase game mode, container and advancement logic. Interactions with mods, synthetic listener and unregistered PlayerList sessions **have not been proven** and could still fail. `doTick()` remains disabled: complete Hunger/Potion/Swimming/PlayerList/Client-owned simulation is not claimed.
- This is a candidate; nothing in an isolated JVM test proves Forge API compatibility or live Minecraft Physics. All user-run Windows results must be documented separately.
- World/Inventory/Lifecycle SavedData and spawned ItemEntities are not one atomic transaction. Interrupted death drops remain an open durability problem; do not test destructive crash recovery on the only copy of a world.
- Existing 512 transaction receipt cap per NPC is unchanged. No modded-item/TaCZ compatibility claim, background Qwen actions, autonomous navigation, or new GitHub operations.
- On Build/Gameplay failure: stop and return to **Audit → A/B/C options → explicit approval → implementation → retest**; avoid opportunistic out-of-scope fixes.
