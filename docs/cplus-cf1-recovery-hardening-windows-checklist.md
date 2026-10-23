# NPCortex — C-F1 H1–H5 Windows Gameplay & Fault Checklist

Use a **throwaway single-player world**. Back up the world and backend SQLite before faults. Requires JDK17 / Python3.13 for Windows. No manual Token required; Automatic Local Auth is preserved.

## 1. Build and test

From `F:\AI\NPCortex`:

```powershell
.\scripts\test-cplus-f1-recovery-hardening.ps1
.\scripts\test-cplus-f1-auto-local-auth.ps1
.\scripts\test-c2-airborne-target-projection.ps1
.\scripts\dev-forge.ps1 -Task build
```

## 2. Run processes

Backend PowerShell (same Windows user):
```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
python -m npcortex_backend
```

Minecraft PowerShell (second window):
```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
.\scripts\dev-forge.ps1 -Task runClient
```

## 3. In-game (operator/cheats enabled)

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus syncstatus yui
/npcortex debug cplus audit yui
/npcortex debug cplus sync yui
/npcortex debug cplus syncstatus yui
/npcortex debug cplus audit yui
```

Expected: inspection READY; synthetic CANCELLED; `syncstatus` reports durable *local* world-wide cursor and pending (asynchronous); `audit` reports Backend vs Forge state (READY when fully synchronized, PENDING before sync, HOLD if inconsistent/offline). Both commands have **no world mutation**. After Sync up to 8 events per call, repeat until pending=0; `audit` should show READY if all event states are terminal.

## 4. Fault exercise — disposable world only

- Backend OFFLINE: close Backend, run `probe`, `syncstatus`, then `sync`. Sync must HOLD; inspect/probe continue working even if HTTP is slow/unavailable; no false ACK. Backend ON again (same SQLite DB), manual `sync` and `audit` should recover.
- SAVE/REJOIN: Save & Quit, restart game & backend; `syncstatus` must reflect durable cursor, no rollback to in-memory zero; check session fencing via `probe` and `inspect` after rejoin. Repeated `sync` must not duplicate Event IDs.
- BROKEN CURSOR: **only on a disposable copied test world with Minecraft stopped**, create a file named `cplus_f1_delivery.cursor.tmp` next to world `data/npcortex/cplus_f1_delivery.cursor` using a non-secret test marker. Restart and run `syncstatus`, `audit` and `sync`: expected HOLD; neither deletes the `.tmp` nor repairs automatically. Preserve evidence, restore a clean backup to proceed. Never modify your main world or manually force a cursor forward.
- BACKEND DB CONFLICT: on a copied backend DB, deliberately create mismatch only through test fixtures; `audit` HOLD, never claim world action completion. Do not edit production SQLite manually.
- CRASH WINDOW: new Python child-process automated tests simulate crash before/after SQLite commit and test idempotent redelivery. Actual Minecraft forced process-kill/Windows power-off remains pending; avoid on valuable worlds.

## 5. C1/C2 regression (no new arena required)

```mcfunction
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/npcortex c2 unfollow yui
/npcortex c1 motion yui
/npcortex c1 inventory yui
```

Replace `Dev` with your username. Repeat Creative flying follow and small 3D obstacle area as in earlier baseline. None of these commands should change because of Recovery Hardening.

## Quality Gate recording

| ID | Test | Status |
|---|---|---|
| H-01 | Java/Python focused regression | PENDING on Windows |
| H-02 | Windows Forge Build | PENDING |
| H-03 | `inspect/probe/sync/syncstatus/audit` | PENDING |
| H-04 | Backend offline then online, manual resend | PENDING |
| H-05 | Save/Rejoin durable checkpoint | PENDING |
| H-06 | Corrupt cursor `.tmp` HOLD without deletion | PENDING |
| H-07 | Follow/Nav/Inventory/Cancel regression | PENDING |
| H-08 | Genuine 1/3/5/10 NPC MSPT | PENDING |
| H-09 | Power loss / Minecraft process-kill matrix | PENDING |

No Break/Place/Pickup/Container action, World Mutation, automatic replay or Saga Execution is present in this build. C-F2 Source Audit follows only after C-F1 Acceptance and separate authorization.
