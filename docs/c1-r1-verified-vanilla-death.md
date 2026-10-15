# NPCortex C1-R1 — Verified Vanilla Death Presentation (Candidate)

Date: 2026-10-15. Minecraft 1.20.1 / Forge 47.4.10 / Java 17.

## Scope

Implements approved **R1, approach B** only. The project still uses a real `Custom ServerPlayer` with a passive non-logged-in listener, guarded Vanilla world tick, B+ motion, P1-B transactional inventory, and P0 two-phase tracking. **No knockback, portal detection, navigation, new AI behavior, new renderer or full network login lifecycle**.

## Evidence from real source audit

Previously `C1P1CLifecycleRuntime.onTick` persisted inventory and `DEAD` and immediately called `C1P0Runtime.detachForLifecycle`. This discarded the server avatar in the SAME server tick, denying the client time to play a Vanilla death presentation. This was not a problem with health calculation, GameRule handling or the appearance skin. Vanilla `LivingEntityRenderer` uses death-time fallover interpolation, while a player is not a mob and the status byte 3 alone must not be relied upon to render a death.

## Changes

- Death decisions and `keepInventory` drops still execute **once** on the END tick; `DEAD` is persisted then. The world avatar remains as a non-interactive 0-HP `ServerPlayer` for **26 server ticks** before detaching; it is never an eligible ALIVE actor, and motion stays stopped.
- Packet `DeathPresentation` is sent **only to verified P0 tracker sessions** with matching UUID, entity id and session token. Vanilla health metadata is supplied, with a client-side `RemotePlayer.setHealth(0)` fallback so Vanilla PlayerRenderer has its natural dead-state input. This is **not a new death animation renderer**.
- The client reports `DeathReady` only after a valid `RemotePlayer` has been in a dead health state for at least two client ticks AND `RemotePlayer.deathTime > 0`. Server validates the P0 token/tracking session again. **This ACK proves Vanilla client death-time advanced, NOT that the animation was visibly observed on screen**. A real visual test remains mandatory.
- New/late verified viewer during the bounded death window gets the same state via the normal `CLIENT_ENTITY_PRESENT` path. Leaving tracking and rejoining gets a fresh token; stale ACKs are discarded.
- Respawn during the presentation returns `WAIT death animation` rather than accidentally treating the dying avatar as a duplicate. After teardown, existing P1-C Respawn/Identity/Inventory behavior is used unchanged.
- A world closed during presentation restores as `DEAD`, with no living avatar. No re-animation is persisted or replayed after reopening the world. An earlier interruption during drop/commit stays `TRANSITION` and fails closed.
- The presentation window is bounded and requires no AI call, new chunk load, per-tick world scan or persistent schema migration.

## Windows verification (test-world copy recommended)

From repository root, with JDK 17:

```powershell
.\scripts\test-c1-r1-death.ps1
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

### Minecraft Acceptance: check with your eyes, not just ACK

Start in a copied single-player test world with one or more NPCs in a safe location, then:

```mcfunction
/gamerule keepInventory true
/npcortex c1 spawn yui
/npcortex c1 lifecycle yui
/npcortex c1 kill yui
/npcortex c1 lifecycle yui
```

Observe **Yui falling sideways with the Vanilla player death pose for about a second before disappearing**. The chat command initially may report `phase=DEAD`, `live=false`, and `deathPresentationTicks>0`. The dead avatar is intentionally still visible briefly, but is not actionable. Look for `DEATH_PRESENTATION_BEGIN`, `CLIENT_DEATH_STATE_ENTERED`, `CLIENT_DEATH_ANIMATION_TICK`, and `DEATH_PRESENTATION_END` in `latest.log`. These log events alone DO NOT prove animation visibility.

Wait ~2 seconds before:

```mcfunction
/npcortex c1 respawn yui
/npcortex c1 inventory yui
/npcortex c1 sync yui
```

Check old UUIDs, one visible living Yui only, and preserved gear. Repeat with `/gamerule keepInventory false` in a clean fixture, one stack of 3 bread, and verify **one** drop and empty inventory after respawn. **Do not crash-inject into a world you care about**: drops and SavedData still cannot be atomically committed with chunk entities.

The Forge network channel version changes to `c1-r1-death-1`; Client and Server must use the same R1 mod build.

For restart mid-presentation, save-and-quit immediately after `kill`. Upon rejoining, check `/npcortex c1 lifecycle yui` reports DEAD and Yui is NOT reanimated/recreated. Then respawn and verify UUID/inventory. Repeat with Hina/Ai alive to ensure their network, inventory, and movement are unaffected. Test a Viewer who leaves/returns to tracking while another NPC is dying, and no stale death-state packet affects a respawned entity.

**Important:** In current C1-P0 command interface, the `info` field `live=true` during the brief presentation may mean a dead Avatar still exists on Server; `lifecycle` is authoritative for ALIVE/DEAD, and `activeAvatar()` rejects DEAD.

### Gate status / limits

- Isolated `javac --release 17` policy test: **43 assertions PASS**; combined R0/P0/P1 isolated regression: **721 assertions PASS**, total **764 assertions**. This does not compile Minecraft/Forge classes.
- Unchanged backend focused C0 tests: **56 passed + 8 subtests passed, Exit Code 0**.
- Forge `compileJava`, Vanilla death pose visually displayed, network event handling, RenderPlayer behavior, timing under lag, dropped items, multiplayer, and 1/3/5/10 NPC performance need Windows/Minecraft verification.
- Real logged-in death screen, player list registration, bed/respawn anchors, player stats, full Forge Player Death/Clone parity, and automatic respawn remain out of scope. No new capability is claimed without user tests.
- `CHANGELOG.md` is the sole project changelog. No GitHub commits or pushes.
