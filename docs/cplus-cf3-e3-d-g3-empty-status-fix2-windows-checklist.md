# NPCortex E3-D G3 Big Sprint Fix #2 — Windows Gameplay Checklist

**Purpose:** Confirm `POST /v1/cplus/e3/status` no longer returns HTTP 500 on a fresh NPC/world and that G3 completion/cancel behavior remains correct. Use **a disposable Creative world**, a clean ZIP extraction, and the existing private credentials. Preserve any historical SQLite DB, world saves and old unresolved attempts for later reconciliation.

## Four separate Windows PowerShell workflows

Use the **actual extracted repository root** (the folder containing `scripts`, `backend`, `forge-mod`); replace the example path when using `NPCortex-Development` nested inside an extraction.

### 1. Focused tests (do not require Minecraft)
```powershell
cd F:\AI\NPCortex
.\scripts\test-cplus-e3-g3-big.ps1 -BackendOnly
# After BackendOnly passes, also validate real Java/Gson contracts:
.\scripts\test-cplus-e3-g3-big.ps1
```
Expected for BackendOnly: six Python test modules finish with `OK`, no `WinError 32` and no `NoneType` / HTTP 500. Failure in Java/Gradle is separate from Backend tests.

### 2. Forge Build (separate)
```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task build
```
Expected: `BUILD SUCCESSFUL`. Do not assume prior Build PASS proves this package passes; the Forge source is unchanged, but always verify the clean extraction.

### 3. Backend (keep running in its own terminal)
```powershell
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1
```
Optional health check from another terminal:
```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
```
Confirm startup is using your intended **test** Backend DB, not a live save you intend to protect.

### 4. Minecraft (separate terminal)
```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

## Gameplay A — The actual HTTP 500 regression

1. Start the Backend; create a **new Creative test world** with Cheats enabled.
2. `/npcortex c1 spawn yui` (skip if already present), then `/npcortex c1 info yui`.
3. `/npcortex c2 status yui` should be `NONE` while idle.
4. `/npcortex debug cplus taskmovestatus yui` → expect **`E3 G2=NONE`**, `C2=NONE`, `no G3 completion proof`. No HTTP 500 or `backend HOLD/offline`.
5. Inspect the Backend terminal: `POST /v1/cplus/e3/status HTTP/1.1 200`.
6. Repeat the status check once: there must still be no new movement task created or world mutation.

**Stop and collect logs** if HTTP 500, stale actor/world ID, or `completion_verified=true` with `NONE`.

## Gameplay B — G3 durable completion (after A passes)

1. `/npcortex debug cplus taskqueue yui` — ensure no unfinished E1 head.
2. Select a flat, safe, loaded target **within 6 horizontal / 3 vertical blocks** of the actual NPC. Never paste sample XYZ without first inspecting current coordinates.
3. `/npcortex debug cplus taskmovepreview yui <x> <y> <z>`.
4. `/npcortex debug cplus taskmove yui <x> <y> <z>`; wait until C2 completes, **do not retry** while pending.
5. `/npcortex c2 status yui` — require `COMPLETED`, `verified=true`, `local=true` and actual Yui motion to the target.
6. `/npcortex debug cplus taskmovefinalize yui` — require durable G3 `COMPLETED` and actor-fence release.
7. `/npcortex debug cplus taskmovestatus yui` — `COMPLETED`, `completion_verified=true`.
8. Repeat steps 2–7 at a **new** reachable target. Distinct task/goal IDs, no old fence blockage.

## Gameplay C — Goal-scoped cancel and recovery

1. In a separate disposable world/session, start one valid move and use `/npcortex debug cplus taskmovecancel yui` while its exact C2 Goal is active.
2. Confirm `CANCEL_PENDING` doesn't release a fence; C2 must report matching Goal `CANCELLED` / C1 stopped.
3. `/npcortex debug cplus taskmovereconcile yui` — only when terminal cancel evidence exists; require durable `CANCELLED` ACK.
4. Backend offline/online or Save/Rejoin while unresolved: expect `HOLD`, no auto replay and no false completion. No manual DB deletion/reset.
5. Run C1/C2 Follow/Inventory/E1/E2/C-F1/C-F2 regression and (when available) real 1/3/5/10 NPC MSPT test; record each case and keep untested gates PENDING.

**Do not run World Mutation, Qwen Auto-dispatch, E3-E DAG or GitHub commands.** A previous unverified `HOLD` attempt must not be converted to `COMPLETED` by merely inspecting an old journal.
