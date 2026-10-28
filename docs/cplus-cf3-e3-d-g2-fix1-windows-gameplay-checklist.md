# NPCortex — E3-D G2 Fix #1 Windows Gameplay Checklist

**Important:** The previous G2 failed Reserve validation *after the Backend may have persisted* a `RESERVED` attempt for that same world/actor. **DO NOT** delete SQLite, reset credentials, clear the old attempt, replay the old task, or repeatedly use `taskmove` in that world. G3 will provide terminal reconciliation. Preserve saves and diagnostic logs.

## 0 — Setup safely

1. Back up the existing `C:\path\to\NPCortex` installation and the old Minecraft save / Backend data **without modifying the originals**. Extract the new Full Repository ZIP into a **separate clean directory**; don't overlay old versions.
2. Use Java 17 Temurin and a **NEW disposable Creative world**, different from the world that produced `invalid reserve reply`; enable cheats/Operator. The new world gets its own world UUID and NPC actor; it cannot silently repair an old G2 attempt.
3. Leave the old Backend DB intact. Check `taskmovestatus` in the old world **read-only** if desired, but do not submit another move there.

## 1 — Independent Windows PowerShell terminals

**Build (Terminal A, one time):**

```powershell
cd C:\path\to\NPCortex
.\scripts\dev-forge.ps1 -Task build
```

**Focused tests (Terminal A after Build):**

```powershell
.\scripts\test-cplus-e3-g2-fix1.ps1
```

This runs the existing focused Backend suites, the new real Backend HTTP parity tests, and `verifyG2Identity` (actual Java/Gson reserve/report validator). If needed, to test only the Python side:

```powershell
.\scripts\test-cplus-e3-g2-fix1.ps1 -BackendOnly
```

**Backend server (Terminal B, keep open):**

```powershell
cd C:\path\to\NPCortex
.\scripts\dev-backend.ps1
```

**Minecraft client (Terminal C, keep open):**

```powershell
cd C:\path\to\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

## 2 — New test world / fresh Yui

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 status yui
/npcortex debug cplus taskqueue yui
/npcortex debug cplus taskmovestatus yui
```

Only spawn Yui if absent. The E1 queue should be `[]`; C2/Follow must not own motion; G2 status should show no unresolved attempt for **this new world**. If this is not true, **STOP** and inspect evidence; do not clear the DB manually.

Pick a flat, **loaded**, walkable, obstacle-free target 2–4 blocks from Yui. Use Yui's CURRENT `/npcortex c1 info yui` coordinates; previous `(127 75 74)` is only a historic example and must not be blindly reused.

```mcfunction
/npcortex debug cplus taskmovepreview yui <x> <y> <z>
/npcortex debug cplus taskmove yui <x> <y> <z>
```

Wait for asynchronous messages (don't submit a second `taskmove`).

**Expected success path:**

```text
NPCortex: E3 G2 typed motion admission queued (operator-only); no block mutation
NPCortex: E3 G2 C2_ACCEPTED task=<uuid> goal=<uuid>; C2 goal queued, motion pending; NOT COMPLETED (G3 pending)
```

Physically watch Yui: a `C2_ACCEPTED` receipt **alone does NOT prove movement or arrival**. Motion may be refused by C2 if a legitimate guard trips. Never equate `C2_ACCEPTED` with `COMPLETED`.

After submitting, inspect (read-only):

```mcfunction
/npcortex debug cplus taskmovestatus yui
/npcortex c2 status yui
/npcortex c1 info yui
```

Record the C2 state, current position, UUID, whether NPC actually walked, and the Backend terminal `POST /v1/cplus/e3/reserve` and `POST /v1/cplus/e3/report` HTTP status codes. Expect HTTP 200/200 in clean accepted normal path.

**Stop on HOLD or any invalid reply.** Preserve both logs and DB as-is; do not retry or attempt to reuse the actor until G3 reconciliation.

## 3 — Negative scenarios (other NEW disposable worlds/actors, never overwrite the first G2 attempt)

- Invalid far-away target or unloaded chunk → HOLD before Backend reserve; no motion and no forced load.
- Follow already active → G2 must HOLD, cannot hijack C1/C2 Follow owner.
- E1 work `QUEUED/HOLD` ahead of move → request denied by Backend FIFO head guard.
- Backend offline → HOLD, no unacknowledged movement and no automatic retry.
- Different requester/actor/world/session/observation, late ACK, `world_effects=true` → Java Self-test/backend Contract Tests must reject (cannot inject a forged server response from normal Minecraft chat commands).
- Save/Rejoin during an unresolved G2 attempt → backend may HOLD; do not auto replay. Full G3 terminal cancel/ack/reconciliation is PENDING.
- C1/C2 real nav/follow/inventory, C-F1/2 inspect/Save/Rejoin regression: test using legacy checklists in separate worlds.

## 4 — Return evidence

Provide: `BUILD SUCCESSFUL`, targeted `verifyG2Identity` PASS, focused Python PASS, screenshots showing `C2_ACCEPTED` and physical movement or exact HOLD, `/npcortex c2 status yui`, `/npcortex c1 info yui`, and Backend HTTP reserve/report codes. Do not regard Java syntax checks as a replacement for Forge compile.
