# NPCortex Sprint 4 Fix #1 — Windows Gameplay Acceptance Checklist

## Install/build (user controls repo; no Git operation by assistant)

Open the delivered FULL Repository ZIP into `C:\path\to\NPCortex` (make backup of existing world/backend DB first).

PowerShell #1:
```powershell
cd C:\path\to\NPCortex
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
```
PowerShell #2: `cd C:\path\to\NPCortex; .\scripts\dev-backend.ps1`.
PowerShell #3: `cd C:\path\to\NPCortex; .\scripts\dev-forge.ps1 -Task runClient`.

## Test A — Legacy journal reconciliation (no manual reset)

1. In the **same test world** and **same backend SQLite database**, run `/npcortex c2 status yui` after loading Sprint 4 Fix #1.
2. If the old row is the precise `P1-D C1 motion resumed during replanning; fail closed` signature with a known goal/action, expect `CANCEL_PENDING` before eventual C0 terminal (`CANCELLED`/`FAILED` etc.). The original goal is *not* resumed.
3. Wait for C0 acknowledgment, use `/npcortex c2 status yui`. Do not manually `/npcortex c2 reset` or delete journal/DB; if it stays pending inspect the backend connection and retry logs.
4. Once terminal, issue a **new** `/npcortex c2 navigate yui <integerX> <feetY> <integerZ>` to a valid local target and verify successful execution. Important: navigate/navhier X and Z must be integers; Y may be half-height `69.5`.

## Test B — New 3D replan with dynamic obstacle

1. Use the known Sprint 4 stone-platform fixture, or rebuild it in a disposable world. Verify `/npcortex c2 navhier yui 83 70 -59` returns `PATH_FOUND` **before** starting.
2. `/npcortex c2 navigate yui 83 70 -59` then (while C2 is MOVING) place a wall as before: `/fill 82 70 -61 83 71 -61 minecraft:stone`. Use `/npcortex c2 status yui`.
3. If the wall occurs mid-route and a safe detour exists, expect replanning within bounded budget, C1 physics settling if needed, and a verified final C0 goal. If no path exists, expect safe failure; do not count a pre-existing wall or late wall after completion as a replan PASS.
4. Inspect both `/npcortex c1 motion yui` and `/npcortex c2 status yui`, capture logs if interrupted or endlessly pending.

## Test C — Foreign owner / cancellation

- While REPLANNING, issue an explicit operator `c1 jump/move/settle` in a safe fixture. This is **not** AUTO_SETTLING; C2 must fail closed and reconcile the old C0 action, never commandeer the operator session.
- Test `/npcortex c2 cancel yui` during 3D movement; wait for C0 terminal and verify a new C2 navigation without reset.
- Test backend offline and restoration during `CANCEL_PENDING`; terminal acknowledgment must be required before new C2 goal.

## Safety and performance (separate pending gates)

- Save/Quit/Rejoin with C0 pending; verify no movement replay, backend reconciliation. Hard-crash and DB/SavedData consistency are NOT proven.
- STEP_DOWN, slabs/stairs, unloaded chunk, C1 combat and NPC death / respawn are pending independent fixtures.
- For actual 1/3/5/10 NPC, record Forge server MSPT, planning latency, retry counts and correctness. Pure Java isolation is not MSPT.

**Report PASS only for the fixture(s) actually observed on Windows Minecraft, not for everything on this checklist.**
