# C1-P1-C — Selective Vanilla Player Lifecycle (C-Optimized) — Candidate

**Status:** Implementation candidate only. Neither the Windows Forge Build nor in-game acceptance has been performed for this change. Use a COPY of the test world, not your original save. **No GitHub operations.**

## Scope and architectural contract

This implementation extends the existing custom `ServerPlayer` avatar and its passive synthetic listener; it does **not** register a fake logged-in human, use `PlayerList.respawn`, create physical sockets, or invoke vanilla `ServerPlayer.die` and `changeDimension` blindly. That would violate C1-P0's verified network assumptions. It selectively uses Vanilla damage/health, the real `keepInventory` GameRule, `Inventory.dropAll`, and Forge's cancellable `LivingDeathEvent` via `ForgeHooks.onLivingDeath`. It uses a controlled server-side teardown/rebuild and the existing two-phase tracking/rotation/equipment sync. **It is not yet full vanilla/third-party player lifecycle compatibility.**

`C1LifecycleData` (overworld SavedData `npcortex_c1_p1c_lifecycle`) stores a separate UUID-keyed phase (`ALIVE`, `DEAD`, or fail-closed `TRANSITION`), health and revision. Old P1-B records default to ALIVE and 20 HP. Corrupt/unknown phases are retained but blocked, not rewritten or auto-restored. New mutations are event driven; there is no new per-idle-NPC scan and no Qwen calls. This is designed for a typical target of 1/3/5/10 NPCs, but there are **no benchmark results** in this candidate.

On a lethal hit the controlled handler consults the *current* world's `keepInventory` rule and records `TRANSITION`. On the next server-tick END, once the damage call has unwound, it either keeps the Vanilla Inventory or calls `Inventory.dropAll()`. It then stores the P1-B Inventory Snapshot and marks DEAD. On failure it stays TRANSITION and cannot be automatically respawned. Manual test respawn reconstructs the ServerPlayer from its stable UUID at the **last saved position** (not an unverified bed anchor), restores Inventory and restarts viewer sync. A manual controlled cross-dimension transfer is allowed only if the operator is present in the destination dimension/chunk and the target position is clear.

### Operator test commands

The usual repository root PowerShell commands apply:

```powershell
cd C:\path\to\NPCortex
.\scripts\test-c1-p1-lifecycle.ps1
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

On a **backup** test world with cheats enabled:

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 give yui minecraft:diamond_sword 1
/npcortex c1 lifecycle yui
/npcortex c1 damage yui 3
/npcortex c1 lifecycle yui
/gamerule keepInventory true
/npcortex c1 kill yui
/npcortex c1 lifecycle yui
/npcortex c1 respawn yui
/npcortex c1 inventory yui
/npcortex c1 sync yui
```

**Important:** `kill` completes on the next server-tick END. Wait until `/npcortex c1 lifecycle yui` reports `DEAD`; if it reports `TRANSITION` beyond the next tick, stop and inspect latest.log; do not force new spawns.

Now test `keepInventory=false` with a distinguishable test inventory:

```mcfunction
/gamerule keepInventory false
/npcortex c1 give yui minecraft:bread 3
/npcortex c1 kill yui
/npcortex c1 lifecycle yui
/npcortex c1 respawn yui
/npcortex c1 inventory yui
```

Expected: the bread/sword drop once as Item Entities at the death location when the GameRule is false, and do not reappear in Yui's respawn Inventory. With `true`, previously held items stay in Yui's Inventory and no death drops should occur. Turn the GameRule back on/off between tests; Qwen is not consulted to evaluate the rule.

**Dimension fixture (no automatic portal travel):** Have the operator enter Nether/End using ordinary gameplay, make sure a safe clear position exists, and run:

```mcfunction
/npcortex c1 dimension yui minecraft:the_nether
/npcortex c1 info yui
/npcortex c1 lifecycle yui
/npcortex c1 sync yui
```

To bring Yui back, enter Overworld yourself then run `/npcortex c1 dimension yui minecraft:overworld`. Verify Yui's UUID, equipment, rotation and server/client visibility. The operator MUST already be in the destination dimension, and the destination chunk must be loaded. No chunks are force-loaded and no dimension-portal pathfinding is part of P1-C.

### Acceptance checklist (all OPEN until tested on Windows)

- [ ] `dev-forge.ps1 -Task build` exits 0 using Forge 47.4.10 and Java 17
- [ ] Damage reduces HP through Vanilla/Forge damage processing; check that Damage Events and armor work
- [ ] Fatal damage/death happens once with correct phase and no server/Viewer duplicate
- [ ] `keepInventory=true` holds all ItemStacks after death/rejoin/respawn
- [ ] `keepInventory=false` drops ItemStacks once and inventory remains empty after respawn/rejoin
- [ ] Death while C1-P1-A motion is active stops safely and does not keep applying movement
- [ ] Death, save/quit, reopen, then respawn preserves three identity UUIDs and no ghost player
- [ ] Real survival hostile mob damage and Forge `LivingDeathEvent` cancellation tested separately
- [ ] Controlled dimension transfer in both directions with known safe target; inventory, UUID, tracking and rotation preserved
- [ ] Three-player (Yui, Hina, Ai) isolation and independent lifecycle; then 5 and 10 when useful
- [ ] P0 initial spawn/rejoin/retracking/despawn and P1-A/B movement, equipment, journal replay regressions unchanged
- [ ] Crash injection between death/drop/snapshot and dimension transfer is audited before full Quality Gate closure

### Known limitations and important safety notes

- **Crash atomicity is not guaranteed:** Lifecycle SavedData, Inventory SavedData, and ItemEntity/World Save are separate. A crash between drop spawns and world saves can leave ambiguous drops and inventory. The runtime refuses automatic respawn on an unresolved `TRANSITION`; **manual reconciliation is not yet implemented**. Do not claim exactly-once world drops or guaranteed rollback.
- PlayerList login/clone, death screen, vanilla bed-based respawn, scoreboard/stats, complete XP death rules, LivingDropsEvent parity, full potion/hunger ticking and portal mechanics are **not fully implemented**. Mod compatibility (including TaCZ) is unverified. The death path deliberately does not call `ServerPlayer.die` with a fake connection.
- Death-drop lifecycle mutations snapshot the actual Vanilla Inventory into P1-B storage without fabricating a user-issued journal transaction. They do not provide distributed commits across C0 SQLite/Forge World Save.
- Selective lifecycle is intentionally server-authoritative and bounded; no AI planning, autonomous survival, inventory trading, new skins, or background per-tick Qwen polling are added.
- The test suite uses standalone Java 17 and NBT test doubles for SavedData. **These do not compile Forge sources or simulate world/network damage.** Build and gameplay must be reported separately. On compile/runtime failure, follow Audit → Options → Approval; do not push.
