# M04 Option D — Integrated Sprint + Gameplay Acceptance Harness

**Date:** 2026-10-26. **Status: partial candidate / HOLD for world mutation.**

This candidate implements an additive D-Lite parent/step *metadata* store, a
read-only Forge acceptance capture command, an operator-driven 1.20.1 datapack,
and a read-only World/SQLite correlation report. It intentionally DOES NOT
claim the full Gate 0–6 gameplay implementation.

## Baseline and limitations

The full repository comes from the most recent available **M04 Foundation**
ZIP in this conversation. `F:\AI\NPCortex` itself was **not mounted** into the
Linux authoring sandbox. A subsequent engineering audit described local changes outside
the earlier manifest. Merge-review against that local tree; **do not overwrite
local source, saves, SQLite, configuration, old HOLD attempts or outbox**.

The Forge/47.4.10 mappings and GameTest runtime cannot be type-checked here:
Gradle 8.8 is unavailable and `services.gradle.org` cannot resolve. The
existing C1 equip/unequip changes therefore also still need Windows compile
and gameplay confirmation. Pure Java contract != compiled Forge module.

## What is implemented in this Option D iteration

- `backend/src/npcortex_backend/cplus_m04_store.py` — additive SQLite
  `cplus_m04_runs`, `cplus_m04_steps`, `cplus_m04_intents`; atomically reserves
  `(world,actor)` and `(world,dimension,block)` in an IMMEDIATE transaction;
  blocks existing unresolved G2/E1 tasks; exact request/attempt/identity
  correlation; prepares irreversible-effect intent; appends exactly one
  evidence witness per sequential step; HOLD for partial/unknown effects;
  metadata-only retries. **Not imported by HTTP server, planner, Forge, or any
  automatic executor.** An entry in this DB is NOT mutation permission.
- `CPlusM04Runtime.java` — read-only `/npcortex m04 capture <npc>` and
  `/npcortex m04 preview-auto <npc>`, alongside existing status/preview.
  Capture includes World/Actor/Session, inventory/equipment, movement and
  FOLLOW/transport occupancy, a bounded F2 observation, and an explicit
  `mutation_permitted=false`.  It copies the JSON payload on server thread
  and writes via a bounded worker queue into:
  `<world>/data/npcortex/m04-evidence/m04-<uuid>.json`.
  Asynchronous QUEUED is **not** proof of completed disk IO; verify the file.
- `integrations/datapacks/NPCortex_M04_Acceptance` — no tick loop, no block or
  item changes. Functions `menu`, `preflight`, `inventory`, `after_rejoin`.
  The user need not supply XYZ; `preview-auto` targets the block in the NPC's
  actual facing direction and will correctly HOLD if no target is visible.
- `scripts/m04_acceptance_review.py` — reads the exact test world and an
  explicitly selected SQLite DB in read-only mode, correlates real World/Actor
  IDs, identifies old G2 and new M04 HOLDs, and **never reports Gameplay PASS**
  from metadata alone. File-based fixtures cover matching/mismatching DBs.
- Regression harness `scripts/test-m04-option-d.ps1` plus focused Python and
  pure Java tests. Minecraft/Forge GameTest integration for native
  mining/pickup is **not implemented** while its safety prerequisites HOLD.

## Run on Windows / real Forge

Use a *copy* of the world and SQLite with a real eligible test NPC. Shut down
Minecraft before extracting sources. Merge source against local work, preserving
`backend/data`, `forge-mod/run/saves`, journal, outbox and configs.

```powershell
cd F:\AI\NPCortex
.\scripts\test-m04-option-d.ps1
.\scripts\install-m04-datapack.ps1 -WorldName 'New World (2)'
.\scripts\dev-backend.ps1
# In another PowerShell:
.\scripts\dev-forge.ps1 -Task runClient
```

In Minecraft as an authorized operator:

```mcfunction
/reload
/function npcortex_m04:menu
/function npcortex_m04:preflight
```

A `capture QUEUED` message means the server handed the evidence payload to
its IO worker. Verify a corresponding file under the world's
`data/npcortex/m04-evidence` directory. The menu never issues world mutation,
so it cannot prove native mining, cancel safety, item pickup or survival timing.

After test on PowerShell (point to **the same DB as the running Backend**;
check `NPCORTEX_DB_PATH`):

```powershell
.\scripts\review-m04-evidence.ps1 -WorldName 'New World (2)' `
  -Database 'F:\AI\NPCortex\backend\data\npcortex.sqlite3'
```

To remove only the temporary datapack after completing evidence collection,
close the world and run:

```powershell
.\scripts\remove-m04-datapack.ps1 -WorldName 'New World (2)'
```

The Java evidence-only capture command is part of this Candidate's Forge
source; remove or disable it by a reviewed source change after acceptance,
not by deleting world records. No existing M03 acceptance datapack was removed.

## Sequential gates / authoritative status

| Gate | Result | Reason / next proof |
|---|---|---|
| G0 Auth + shared claims | **PARTIAL / HOLD** | Dormant SQLite actor/target claims implemented and tested; source-command provenance and common live C1/C2/Follow guard not proven. Old G2 HOLD is blocked. |
| G1 World permission/observation | **PARTIAL / HOLD** | Bounded read-only preview and observation; mutation permission still UNKNOWN; no claim-mod adapter. |
| G2 Equip/unequip | **SOURCE IMPLEMENTED / GAMEPLAY PENDING** | Previous C1 code; Windows Forge compile and normal save/rejoin required. |
| G3 Real timed mining | **NOT IMPLEMENTED / HOLD** | Cannot assume ABORT stops native delayed destroy; no proven quiescence, event guard or permission. |
| G4 Native pickup | **NOT IMPLEMENTED / HOLD** | Custom ServerPlayer does not run normal pickup phase; need native physical adapter with actual distance and custody proof. |
| G5 World effect recovery | **PARTIAL / HOLD** | Additive intent and step metadata only; no authoritative native event bridge/cross-save reconciliation. |
| G6 End-to-end | **NOT IMPLEMENTED / HOLD** | Cannot invent COMPLETE without real block/drop/inventory effects and durable terminal. |
| P 1/3/5/10 NPC | **PENDING** | No real simultaneous NPC/mutation workload data. |

**Important:** All old M03 deferred 6B–10 tests, unresolved `C2_ACCEPTED`
without G3 terminal, and previously recorded outbox/HOLD attempts remain
preserved. Never equate a successful C0/C2 move with a released G3 fence.

## Gate 3 blocker and required next implementation evidence

Inspect the exact cached Forge `ServerPlayerGameMode.handleBlockBreakAction`
implementation at the Windows repository and reproduce `START`/early `STOP`/
`ABORT`/delayed break behavior using an **isolated GameTest fixture**. Only
allow one concrete block after a non-UNKNOWN permission policy, shared actor
and target admission, persisted effect intent and a cancellable native break
guard are verifiably active. If the allowed 1.20.1 public APIs cannot prove
quiescence, propose narrowly scoped accessors with approval rather than
silently introducing reflection/AT/Mixin. Only then wire `ItemEntity.playerTouch`
with physical intersection and actual inventory observation; its method alone
does **not** prove pickup range.

## Automated verification in this authoring environment

- 26 focused backend M04 Python contract/store tests PASS.
- 4 read-only evidence reviewer tests PASS.
- Original 11 M04 static boundary checks PASS.
- New Option D static datapack/harness 13 checks PASS.
- Pure Java M04 contract `javac --release 17`: 13 assertions PASS (executed on JDK21).
- Full backend suite: timed out (no PASS asserted).
- Forge Build: blocked before compile due unavailable Gradle 8.8 distribution.
- Windows Minecraft gameplay: NOT RUN.
- **No GitHub Operations; no actual world mutation implementation.**
