# NPCortex C1-R0 — Vanilla Lifecycle Foundation (2026-10-15)

**Scope:** Approved R0 foundation only. This is a refactor candidate for Minecraft Forge 1.20.1 / Java 17. It **does not** add death animations, automatic portal travel, vanilla knockback, autonomous tasks, player-list login, or real player connections.

## Audit baseline

Source reviewed from `NPCortex_C1-P1-C_Fix1_C_SelectiveVanillaTick_FullRepository.zip`:

- `C1PlayerAvatar` extends `ServerPlayer`, has a `C1PassivePacketListener`, calls `super.tick()` for the server world phase, and deliberately disables `doTick()` to avoid assuming a real player connection.
- `C1P1MotionRuntime` owns all `Player.travel` / jumping / idle support checks; server-end movement happens ahead of pending-death cleanup.
- `C1P1CLifecycleRuntime` manages fail-closed persisted ALIVE/DEAD/TRANSITION, respawn and controlled dimension proof. Vanilla death `ServerPlayer.die` is not invoked on a passive connection.
- `C1P0Runtime` owns identity, entity registration, per-viewer two-phase network tracking/repair, and deactivation.
- `C1P1InventoryRuntime` restores Vanilla ItemStacks **before** ServerLevel registers the player; Client equipment sync follows Entity verification.

The baseline runs `C1P0Runtime.onServerTick` then `C1P1MotionRuntime.onTick` then `C1P1CLifecycleRuntime.onTick`. `C1P0Runtime.onServerStopping` ran ahead of pending-death shutdown. These orders are preserved.

## R0 changes and boundaries

- Added `C1VanillaLifecyclePolicy`: an explicit ownership table with Vanilla WORLD tick, disabled CONNECTION tick, and NPCortex-controlled MOTION, DEATH and TRACKING. Only a registered, active, nonremoved avatar receives the Vanilla WORLD phase. DEATH/TRANSITION or detached actors fail closed.
- Added `C1VanillaLifecycleCoordinator`: one coordination point for spawn preparation, world-tick handoff, damage/death callbacks, removal and ordered server END tick / shutdown callbacks.
- `C1PlayerAvatar` now delegates lifecycle/motion coordination, keeping `ServerPlayer.tick()` and the P1-C fatal-damage handler unchanged where eligible. `ServerPlayer.doTick()` is still deliberately **not called**.
- `C1P0Runtime` delegates only before-spawn inventory/lifecycle restoration, after-spawn motion init and before-despawn cancellation. No change to handshake messages, two-phase protocol, packets or player identity format.
- `NPCortex.java` registers one server-tick and one shutdown coordinator callback instead of independent listeners for the same three runtimes, preserving their execution order.
- No SavedData/Inventory/HTTP/SQLite schema changes. No Backend changes, GitHub actions, new entity type, new network polling or Qwen calls.

**Current ownership (explicitly not all Vanilla):**

| Phase | Owner in R0 | Why |
|---|---|---|
| ServerPlayer WORLD `tick()` | Vanilla, guarded | Retains built-in spawn-invulnerability countdown |
| ServerPlayer CONNECTION `doTick()` | Disabled | Passive listener cannot supply real movement packets |
| Active/idle motion | NPCortex P1-A | One travel owner; no double physics loop |
| Fatal damage / death / respawn | NPCortex P1-C | Fail closed across saved inventory/death state |
| Viewer tracking / repair | NPCortex P0 | Preserve confirmed two-phase network synchronization |

## Local verification in this package

- R0 pure owner policy: **13 assertions PASS** (`javac --release 17`).
- R0 coordinator with isolated test doubles: **12 assertions PASS** (tests ordered callbacks and guards; NOT Forge runtime).
- Existing isolated Java checks re-run with exit 0: P0 Network **26**, P1 Movement **29**, Rotation **29**, Inventory Journal **544**, Inventory NBT Save/Load doubles **14**, Lifecycle SavedData doubles **22**, Idle Physics Policy **32** = **696 assertions PASS**.
- Total isolated checks: **721 assertions PASS**. The runner machine has Java 21 using `--release 17`, not a JDK 17 runtime.
- Backend bytes remain identical to supplied baseline. A focused unchanged C0-agent smoke regression was run: `python -m pytest -q backend/tests/test_c0_agent.py` → **56 passed, 8 subtests passed, exit 0**. The full Backend suite was not rerun for R0.
- **Forge `compileJava` BLOCKED:** Gradle Wrapper cannot resolve `services.gradle.org` / download Gradle 8.8 here. Thus no verified Forge compilation, mod loading, dedicated-server or gameplay results for R0.

## Windows acceptance steps (test world backup required)

Open PowerShell at the repository root. Use installed JDK 17 (Temurin supported).

```powershell
cd F:\AI\NPCortex
.\scripts\test-c1-r0-foundation.ps1
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
.\scripts\test-c1-p1-lifecycle.ps1
.\scripts\test-c1-p1c-vanilla-tick.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**Minecraft tests (perform in an isolated copy):**

1. Spawn Yui/Hina/Ai; verify player models/UUID isolation and no duplicate live avatars. `/npcortex c1 list`, `/npcortex c1 info yui`, `/npcortex c1 sync yui`.
2. For Yui, run `/npcortex c1 turn yui 180`, `/npcortex c1 move yui forward 3`, `/npcortex c1 jump yui`, `/npcortex c1 motion yui`. Verify gravity, stopping, collision, independent movement.
3. Inspect `/npcortex c1 lifecycle yui`, then (after spawn protection has elapsed) `/npcortex c1 damage yui 3`; verify accepted damage and actual health change. Do not infer success from a command being accepted alone.
4. With `/gamerule keepInventory true` give Yui an item; use `/npcortex c1 kill yui`, wait until `DEAD`, then `/npcortex c1 respawn yui`. Verify health, inventory, one model and `CLIENT_ENTITY_PRESENT`.
5. On a separate backed-up disposable world, test `keepInventory false`: verify exactly one set of drops, no restoration of dropped items. Crash mid-drop **not certified**.
6. Save and Quit to Title; rejoin and verify UUID, equipment, position and all four rotation angles; test client repair and re-tracking after leaving the render distance.
7. Optional guarded manual dimension regression: operator stands in **loaded safe** destination dimension and uses `/npcortex c1 dimension yui minecraft:the_nether`; check identity and inventory, then return via controlled command. Automatic portal is **not** part of R0.
8. Multi-NPC smoke: repeat at **1/3/5/10** active NPC counts if practical; observe server tick time, client/server disconnect, player tick exceptions, packet loops or duplicate avatars. No measured performance result exists yet.

**Stop/FAIL signs:** Forge compile errors; crashes; entity duplication; PLAYER/CONNECTION exceptions; NPC inventory loss/duplication; PHASE `TRANSITION` after an unexpected error; absent/reversed tracking; motion/death simultaneous state corruption. Do not force respawn on TRANSITION; capture `latest.log` and return for audit.

## Deferred work and explicit risks

R1: vanilla-like death presentation/animation; R2: damage-related knockback; R3: automatic portal/dimension change; R4: mod event and load compatibility. No phase is advertised as implemented in R0.

No compatibility guarantee for Forge mods relying on player login or network session; passive synthetic NPCs are **not** real logged-in clients. Disk atomicity across world drops, lifecycle and inventory is still unproven, as are real multiplayer and 10-NPC profiling.
