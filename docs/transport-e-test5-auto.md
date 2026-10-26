# M03 / Transport E — TEMP Test 5 race automation (Option D)

**Scope:** disposable operator-only Test 5 helper, NOT production autonomous movement. Confirmed build/runtime status on packaging host: **pure Java decision test PASS (14 cases); Forge Gradle build UNVERIFIED** (Gradle 8.8 could not download). **Windows gameplay PENDING.** Do not mark Test 5 PASS without in-game evidence.

## What this does

The Minecraft 1.20.1 datapack provides a one-line `/function`. The temporary Forge bridge performs the actual work because Vanilla 1.20.1 function files cannot dynamically inject NPC-derived integer coordinates into `/npcortex debug cplus taskmove`, and G2/G3 confirmations are asynchronous. It uses the *actual Yui ServerPlayer avatar position*, not the command executor's coordinates. Reads actual backend status, keeps the operator's identity, calls the same `CPlusE3G2Runtime.move`, `CPlusE3G3Runtime.cancelMove`, `CPlusE3G3Runtime.reconcileCancel`, and `CPlusE3G3Runtime.finalizeMove` methods that manual commands use. Does **not** implement new navigation or bypass G3 evidence checks.

It finds a straight, loaded 5-block corridor on flat solid ground in four cardinal directions. If unsafe/no corridor, HOLD; it does not teleport, place/break blocks, or force chunks. Only one movement attempt per `/function` invocation. The helper does not automatically recover, retry/resend, reset fences, or resume after server restart. It watches for `C2_ACCEPTED`, checks an exact goal and real NPC distance, requests cancel once when within 2.5 blocks, then waits for `CANCEL_PENDING` and authoritative C2 journal evidence to run the appropriate terminal action. If C2 succeeds first, terminal finalize is valid, but if no cancel was ever attempted the result is `INCONCLUSIVE` rather than Test 5 PASS. Requires *both* durable backend terminal state and a locally validated terminal ACK from the existing G3 runtime.

HOLD/uncertainty leaves existing task, world data, SQLite and outbox untouched for investigation. `abort` stops the test controller only; **does not cancel the live NPC task**.

## 1. Install (test world, Windows/PowerShell)

1. Extract this full-repository candidate over a **backup/copy** of your current candidate source folder. Do not overwrite `forge-mod/run/saves`, `forge-mod/run/data`, existing backend SQLite files or `.env` when moving files.
2. Open PowerShell at repo root; set JDK 17 for Forge.

```powershell
cd F:\AI\NPCortex
java -version
.\scripts\test-test5-harness.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\test-transport-e.ps1
```

3. Copy the datapack into the test-world save **while Minecraft is closed**. From repo root:

```powershell
Get-ChildItem '.\forge-mod\run\saves' -Directory | Select-Object -ExpandProperty Name
.\scripts\install-test5-datapack.ps1 -WorldName 'YOUR_TEST_WORLD_FOLDER'
```

You can also manually copy `integrations/datapacks/NPCortex_Test5_Automation.zip` into `forge-mod/run/saves/<world>/datapacks/` (copy as ZIP, not extracted).

4. Run Backend (`.\scripts\dev-backend.ps1`) and Minecraft client (`.\scripts\dev-forge.ps1 -Task runClient`) in separate consoles. **Use a disposable/safely backed up test world**, cheats/OP enabled and Yui spawned/on ground. Stand by Yui to keep terrain loaded.

## 2. ONE command to test all steps

Execute in *the in-game chat as an actual OP player*, not from the dedicated-server console or a scheduled tick function:

```mcfunction
/reload
/function npcortex_test5:start
```

No x/y/z values to type. Status and stop commands:

```mcfunction
/function npcortex_test5:status
/function npcortex_test5:abort
```

If `/function` loses player identity due to another function/executor, use equivalent direct OP command:

```mcfunction
/npcortex debug cplus autotest5 start yui
/npcortex debug cplus autotest5 status
```

Do **not** run start repeatedly when status is HOLD, active, or ambiguous; the helper deliberately refuses unresolved work. Keep backend online for the first attempt. Do not manually cancel/finalize the same task during the automated test (it would invalidate correlation).

## 3. Expected results

| Stage | Evidence (game chat + backend) | Expected |
| --- | --- | --- |
| Preflight | `PREFLIGHT ... target=(x,y,z)` | Chosen using Yui location; loaded safe corridor |
| Admission | `ONE taskmove submitted` then `G2 C2_ACCEPTED goal=...` | New task/goal; exact operator, world, session, target |
| Race | `near goal ... taskmovecancel requested ONCE` | One cancellation intent, not repeated or guessed |
| Cancel wins | G2 `CANCEL_PENDING`; C2 `CANCELLED`; G3 `CANCELLED` | Validated durable ACK, `PASS` |
| Completion wins **after cancel issued** | C2 `COMPLETED` + live C0/C1 witness; G3 `COMPLETED` | Validated durable ACK, `PASS` |
| Arrived before any cancel was attempted | G3 `COMPLETED` | `INCONCLUSIVE` (not race PASS); no retry |
| Backend offline/old HOLD/dimension change/inconsistent proof/timeout | `HOLD` | Preserve data, no replay, inspect diagnostics |

**Important:** `C2_ACCEPTED` does NOT mean completed; `CANCEL_PENDING` is NOT terminal. Terminal ACK is checked by the original G3 identity/terminal validator, not fabricated in datapack. Task status is polled read-only and validated against live actor/world/session and the newly created attempt ID. All game observations and actor calls happen on the Minecraft server thread; HTTP is on a bounded worker. There is no automated offline fault injection or save/rejoin in this temporary helper; those remain separate acceptance tests.

## 4. What to send back

1. PowerShell result from `dev-forge.ps1 -Task build` and `test-transport-e.ps1` (JDK and Gradle errors included if any).
2. A screenshot showing the first `PREFLIGHT` and `ONE taskmove` messages, and a final screenshot showing `PASS`, `INCONCLUSIVE`, or `HOLD`.
3. `/function npcortex_test5:status` output after the test.
4. `forge-mod/run/logs/latest.log` and relevant Backend log lines (remove tokens/secrets). On `HOLD`, include `/npcortex debug cplus taskmovestatus yui` and `/npcortex c2 status yui` before touching the task again.

This only automates **Test 5**; it is not a substitute for Tests 6–10 (offline/response loss/recovery/save-rejoin/regression/performance).

## 5. Remove everything after the pass

Wait for PASS and stop Minecraft/Backend (do **not** uninstall in the middle of an active attempt). Then from repo root:

```powershell
.\scripts\remove-test5-harness.ps1 -WorldName 'YOUR_TEST_WORLD_FOLDER' -RemoveFromSource
.\scripts\dev-forge.ps1 -Task build
```

The removal script deletes only the datapack archive from the explicitly named test world, its packaged source, `CPlusTest5Runtime.java`, the pure test policy and its self-test, and *exactly* three temporary registrations/hooks (validated before modifying source). It does **not** delete save folders, journal/SQLite/outbox, backups, C1/C2/G3 production code or any Git data. Rebuild to get a Forge mod jar without the test bridge. If the world is open, `/reload` after removing datapack, but **prefer closing it before removal**.

Keep a copy of the test log and terminal ACK/attempt IDs for acceptance evidence.
