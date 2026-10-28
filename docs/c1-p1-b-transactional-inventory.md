# NPCortex C1-P1-B — Transactional Player Inventory & Equipment (Candidate)

## Approved Scope

Custom ServerPlayer (Forge 1.20.1), Vanilla Inventory slots 0–35 (main/hotbar), 36–39 (armor feet→head), 40 (offhand), test-command item mutations, full Vanilla inventory NBT persistence, per-NPC idempotency receipts, and equipment synchronization only on mutation or verified tracking. No backend changes, C0 skill execution, item trading, world mining/building, crafting, autonomous AI, or automatic transfers are implemented.

The Vanilla `ServerPlayer.tick()` and `doTick()` remain disabled; do NOT enable them for equipment. The existing C1-P0 Two-Phase Synchronization is retained. Forge equipment packet uses six `EquipmentSlot` values after confirmed client entity presence and on operator-triggered changes. Unchanged inventory slots are private and not sent over the network.

## Persistence, IDs, and Crash Semantics

- `npcortex_c1_p1b_inventory` in Overworld `SavedData`: contains a full Vanilla `Inventory.save(ListTag)` ItemStack NBT snapshot, selected hotbar index, a monotonic revision, and the matching UUID/signature receipt journal **in the same SavedData record** per GameProfile UUID. No modifications to B1–C0 SQLite or to the old `npcortex_c1_p0` identity records.
- Actor/Profile/World UUIDs are unchanged. Missing P1-B data in an old world restores an empty inventory.
- Each operator mutation is executed on the server thread: validate UUID and requested operation → check existing receipt → snapshot old inventory → apply Vanilla Inventory mutation → write next snapshot + commit receipt → mark dirty → synchronize equipment. A failure restores the old in-memory inventory. Replaying the **same transaction UUID and same operation** returns `REPLAY` without changing any slots; the same UUID with a different operation returns `CONFLICT`.
- The journal is capped at **512 receipts per NPC** and then fails closed. It never silently evicts old IDs and re-enables a duplicate action. An explicit journal-compaction policy is deliberately deferred beyond the proof stage.
- **Important durability boundary:** `APPLIED_PENDING_WORLD_SAVE` means mutation and receipt are committed **in server memory**. Vanilla Minecraft persists both at its regular save checkpoint; `APPLIED` is NOT an fsync acknowledgement. Run `/save-all flush` when testing durability. A process crash before a world save can roll back both the snapshot and its matching receipt; this is not a cross-world or cross-SQLite exactly-once protocol.
- If SavedData inventory record is malformed/duplicate, it is **blocked**, not restored as an empty bag. Unparseable NBT is copied unchanged on subsequent saves by other actors; backup the world and investigate before recovery. An empty/missing record from pre-P1-B worlds is supported.
- There is no per-idle-tick inventory scan and no Qwen polling. Normal mods or code that mutate the NPC inventory **outside the P1-B adapter** are not transactionally captured. Forge ItemStack NBT is used, but interoperability with every modded capability still requires actual tests.

## Operator commands (permission 2)

All are under `/npcortex c1`:

```mcfunction
/npcortex c1 inventory yui
/npcortex c1 equipment yui
/npcortex c1 ledger yui
/npcortex c1 give yui minecraft:diamond_sword 1
/npcortex c1 give yui minecraft:bread 32
/npcortex c1 equip yui 0 mainhand
/npcortex c1 equip yui 10 offhand
/npcortex c1 equip yui 11 head
/npcortex c1 clear yui 10
/npcortex c1 copyhand yui 10
```

An **optional final transaction UUID** may be appended to `give`, `equip`, `clear`, or `copyhand`. Omitting it auto-generates an ID for a new operation. For replay safety, supply a fixed explicit UUID (the same operation twice must not add items twice). `copyhand` copies the **operator's held stack** into a target NPC slot including NBT, damage and enchantments **without removing it from the operator**; this is explicitly a **creative fixture / debugging command**, NOT transfer or trade. `equip` swaps the chosen main Inventory slot with the target location; `mainhand` is selected hotbar slot, `offhand` is 40, `feet`=36, `legs`=37, `chest`=38, `head`=39. Armor slot compatibility is checked by Vanilla `LivingEntity.getEquipmentSlotForItem`.

### Windows commands

```powershell
cd F:\AI\NPCortex
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

### Gameplay acceptance checklist

Back up test world. Spawn `yui`, `hina`, `ai` in separate positions. Record UUIDs using `c1 info`. For each independently give items, inspect inventory and equip items; check client visual mainhand/offhand/armor. Have the player hold a damaged/enchanted Vanilla item and `copyhand yui 10`, then equip from 10 and check retained damage, enchantments and count after rejoin.

Test idempotency **using same explicit transaction UUID**:

```mcfunction
/npcortex c1 give yui minecraft:bread 2 11111111-1111-4111-8111-111111111111
/npcortex c1 give yui minecraft:bread 2 11111111-1111-4111-8111-111111111111
/npcortex c1 give yui minecraft:bread 3 11111111-1111-4111-8111-111111111111
/npcortex c1 ledger yui
/save-all flush
```

Expected: first `APPLIED_PENDING_WORLD_SAVE`, then `REPLAY`, then `CONFLICT`. The bread quantity increases **only once by two**. Leave/rejoin the world, repeat with the same UUID, verify `REPLAY` and unchanged stack count; verify selected hotbar and 3 UUIDs. Check `/npcortex c1 sync yui` after reconnect and equipment appearance. Exit tracking distance and return, and check visible held items. Despawn one of three NPCs; the other two must retain inventories and movement/rotation. Respawn the despawned NPC; inventory must remain preserved. Also test clearing an occupied slot and restart.

## Quality gates and limitations

- `test-c1-p1-inventory.ps1` and `test-c1-p1-inventory-saveddata.ps1` use pure-Java and mock NBT test doubles. They do not constitute Forge compilation or actual disk durability proof.
- Actual Windows Java 17 Forge Build, real Minecraft inventory/equipment display, preserved damage/enchants/NBT, dedupe across saved restart, P0 network repair and P1-A movement/rotation regression are **required before completion**.
- Single-player 3-NPC smoke tests passed *before* this change per user report, but must be rerun in P1-B to rule out regressions.
- 5/10/20 NPC performance, LAN/multiplayer, world crash injection with disk faults, custom skin, modded capability integration, autonomous actions and player-to-player transfers are deferred.
- There is no guarantee of crash-proof cross-container, player-to-player, or cross-Backend transfers. Such work needs a later approved end-to-end reconciliation protocol.
