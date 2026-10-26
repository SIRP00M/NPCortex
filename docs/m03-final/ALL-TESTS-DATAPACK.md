# NPCortex M03 All-Tests Datapack — Windows Minecraft 1.20.1

**This is an operator-triggered acceptance harness, not a fake PASS generator.** It adds 40 `/function npcortex_m03_suite:*` entry points for Test 6A–10 using the M03-FINAL Forge bridge. It does **not** mutate inventory/blocks, spawn NPCs, kill processes, auto-repeat navigation, reset SQLite/journals/outboxes, or perform Git operations. It is temporary and removable.

## Requirements / installation

- **Use a COPY** of the Minecraft test world, configuration and SQLite database. Do not overwrite the running baseline. Run from this updated Full Repository candidate, **not** the earlier unpatched M03-FINAL: `loss-terminal-auto`, `measure-auto`, `move-all` and `follow-self` require the companion TEMP Forge bridge changes.
- JDK 17 for Forge. Run `.\scripts\dev-forge.ps1 -Task build` and `.\scripts\test-m03-final.ps1` (the latter runs many gates; log failures honestly). Start backend and `runClient` with proper test config.
- Close the copied world and run `.\scripts\install-m03-alltests.ps1 -WorldName 'COPIED_WORLD'`. Installer also installs the base M03 pack if absent; neither pack is overwritten if already present.
- Alternatively install standalone `NPCortex_M03_AllTests.zip` in the copied world's `datapacks` folder; **Forge code in this Candidate is still required**. `pack_format=15` for Minecraft 1.20.1.
- Join as the actual **online OP**, run `/reload`, then `/function npcortex_m03_suite:enable`. See the **clickable chat menu** via `/function npcortex_m03_suite:menu` (plus clickable Test 7/9/10 submenus); click a phase instead of typing commands. Server restart requires new enable.
- Wait for `snapshot saved ...` after each capture; asynchronous network result can appear later than the `/function` acknowledgement. **Only evidence from backend/Forge/world can establish PASS.**

## Functions, expected observations and required human steps

| Test | In-game function(s) | Expected / next step | Still needs outside Minecraft |
|---|---|---|---|
| 6A Controlled network error | `test6a/start`, then `test6a/check` | one unique unsent reserve, CONNECT_FAILED/HOLD, **no** C2 motion | Compare snapshot, retained envelope, backend state |
| 6B Real backend stop/restart | `test6b/online`, `test6b/offline`, `test6b/restart` | offline reserve HOLD, same envelope, no movement replay after metadata recovery | Stop/start **owned** backend process with PowerShell, same DB/config. Separate accepted-goal window remains required |
| 7 Reserve response loss | `test7/reserve_start`, wait for error, `test7/reserve_recover` | backend commit may exist, response lost; no duplicate goal or movement | Check `AFTER_SUCCESSFUL_RESPONSE` evidence, backend rows, hashes and journal |
| 7 Terminal finalize response loss | `test7/finalize_move`; **wait until C2 COMPLETED**; `test7/finalize_loss`; after G3 error `test7/terminal_recover` | `loss-terminal-auto` captures actual task UUID, arms one-shot loss and requests finalize; no manual UUID | Confirm backend terminal row exactly once, original task ID, no second movement |
| 7 Cancel-ack response loss | `test7/cancel_move`; while moving `test7/cancel_begin`; **wait until C2 CANCELLED + backend CANCEL_PENDING**; `test7/cancel_loss`; then `test7/terminal_recover` | auto binds exact task UUID and one-shot ACK loss | Check cancellation, same attempt, single terminal, no replay |
| 8 Save/Rejoin | `test8/before`; Save & Quit, join **same copied world**; `test8/after`; `test8/recover` | stable world+actor/profile, session changes; no replay | Save/Quit, rejoin, and run `m03_final.py compare-rejoin` on actual snapshots; one eligible NEW move may be tested after |
| 9 C1/C2/Follow/Inventory | `test9/baseline`, `test9/move`+`test9/finalize`, `test9/follow_start`+`test9/follow_conflict`+`test9/follow_stop`, `test9/inventory` | read actual C1/C2/journal; Follow owns actor while following and rejects guarded move | Walk around while Yui follows; use existing-item equip/swap with **unique** transaction IDs; inspect world and snapshots |
| 10 NPC Performance | `test10/start1/3/5/10`, followed by matching `test10/workload1/3/5/10`, then `test10/check` | live NPC list selected automatically, exactly 1/3/5/10 present, 120 sec measurements; no spawns | Ensure requested **exact count** of live NPCs, run ≥3 rounds/count, manually finalize completed NPC moves, measure external CPU/RSS and profiler; compare baseline |

### Test 6B real lifecycle: separate terminal controller

Configure a **copied** Forge instance to use `http://127.0.0.1:18765`, with the same intended test-local credential source. Verify no unrelated listener on port 18765. Create a **consistent SQLite backup** while original backend is stopped or using SQLite's backup API; never copy a live DB file without proper WAL handling. Example for the isolated controller:

```powershell
.\scripts\start-m03-owned-backend.ps1 -Database 'F:\acceptance\m03-copy.sqlite3' -EvidenceDirectory 'F:\acceptance\owned-test6b'
```

Type `stop` then `start` **in that controller** at the phase indicated by the datapack. It refuses to kill unrelated processes. The Datapack only prepares observations; it **cannot** control Windows processes. Avoid switching your live-world backend URL.

### Test 7 extra protocol coverage

`test7/*` exercises live Forge faults on a single Yui attempt. Run `.\scripts\test-m03-final.ps1` for G5 isolated socket loss, idempotency/duplicate, stale identity and foreign-actor coverage; those synthetic protocol checks are **not** Minecraft gameplay PASS. The terminal auto helper will HOLD if it cannot prove the expected backend state, exact goal, requester, session and local C2 terminal witness. A fault expires after 60 seconds. If a fault did not consume, use `npcortex_m03_suite:fault_clear` to disarm it; no durable data is cleared.

**Never** issue `test7/reserve_recover` or `test7/terminal_recover` before inspecting the asynchronous response-loss message. Do not chain blindly with tick delays. Recovery is metadata-only; it must not replay a move. If the actor remains fenced, record HOLD and investigate rather than resetting.

### Test 8 before/after comparison

Evidence files are stored under `<world>/data/npcortex/m03-evidence/*.json`. Inspect snapshots to identify the exact before/after pair, then:

```powershell
python scripts/m03_final.py compare-rejoin --before 'F:\acceptance\before.json' --after 'F:\acceptance\after.json' --output 'F:\acceptance\rejoin-result.json'
```

Stable world/actor/profile, new session, consistent outbox/journal and no replay are necessary but do not alone guarantee safe new-task admission. Any unresolved attempt stays HOLD. Save/Rejoin is **not** simulated by `/reload`.

### Test 9 limitations

`test9/inventory` is read-only; it will not invent inventory items. Equip/unequip with existing items needs the verified existing C1 commands documented in `docs/m03-final/README.md`. There is no empty-slot `unequip` API; report that case HOLD. Follow requires physically walking/turning to see tracking; a command returning success is not enough.

### Test 10 limitations

Exactly count many LIVE actors in the copied world: `measure-auto`/`move-all` never spawn or deactivate any. Same terrain and workload for 1/3/5/10 and ≥3 rounds each. Workload issues **one guarded G2 move per actor**, not movement loops; some may HOLD because safe loaded corridor or actor ownership is unavailable. The Java observer records server tick-event samples; this is NOT whole-server profiler MSPT. Also obtain `/debug start` and `/debug stop` plus trusted OS measurements and later summarize:

```powershell
.\scripts\test-m03-performance.ps1 -EvidenceDirectory 'F:\acceptance\evidence' -Output 'F:\acceptance\performance.json'
```

Without true workload completion, matching results and agreed performance criteria, status remains PENDING/HOLD, not PASS.

## Removing the temporary suite

After copying evidence and closing the world:

```powershell
.\scripts\remove-m03-alltests.ps1 -WorldName 'COPIED_WORLD'
```

This removes only `datapacks/NPCortex_M03_AllTests`. To remove the TEMP M03 Forge bridge later, use the **existing** M03 uninstall procedure from `docs/m03-final/README.md` after acceptance; do not remove it before all tests. Removing this datapack never deletes world saves, evidence, SQLite, outbox, journal or HOLD attempts.

## Required acceptance evidence

Keep screenshots with exact commands, `forge-mod/run/logs/latest.log` redacted, backend logs, `m03-evidence/*.json`, isolated database/transport records and per-NPC goal/attempt IDs. Mark each row IMPLEMENTED / AUTOMATED PASS / WINDOWS GAMEPLAY PASS / HOLD / FAIL / PENDING. Automated or command success alone never certifies M03-FINAL acceptance.
