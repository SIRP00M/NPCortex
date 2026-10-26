# NPCortex — E3-D G2 Windows Gameplay Checklist

**Candidate — do not test on valuable Minecraft worlds.** Keep the currently accepted E2/G1 build as a rollback ZIP. This is a ONE-MOVE/ONE-ACTOR admission gate, not G3 recovery or a general-purpose autonomous task.

## 0 — Build & test independently

**Build:**

```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task build
```

**Focused test (separate, optional):**

```powershell
.\scripts\test-cplus-e3-g2.ps1
```

**Full backend tests (separate, optional, longer):**

```powershell
.\scripts\test-cplus-e3-g2.ps1 -IncludeFullBackend
```

**Backend — separate terminal (leave running):**

```powershell
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1
```

**Minecraft — separate terminal:**

```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

## 1 — Preflight (empty FIFO / known safe region)

Run in a **new test world** with cheats. Use an operator character.

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex debug cplus world yui
/npcortex debug cplus taskqueue yui
/npcortex c2 status yui
```

Spawn only if not present. `taskqueue` should be `[]`. A `QUEUED` or `HOLD` head must be reconciled with its original requester before G2; do not delete/alter DB to bypass FIFO. Ensure Yui is **standing on the ground** in loaded level terrain and not following a player.

Pick a visible, safe, walkable target **2–4 blocks away** within x/z ±6 and y ±3 of Yui's actual `/npcortex c1 info yui` position. Avoid deep holes, water, trees and adjacent walls. `x,y,z` must be the **block column and desired foot height**.

## 2 — Compare Preview vs live movement

Example ONLY for the earlier screenshot with Yui approximately (125,75,74), after checking terrain:

```mcfunction
/npcortex debug cplus taskmovepreview yui 127 75 74
/npcortex debug cplus taskmove yui 127 75 74
```

Expected (asynchronous):

1. `E3 G2 typed motion admission queued...`
2. `E3 G2 C2_ACCEPTED task=<uuid> goal=<uuid>; C2 goal queued, motion pending; NOT COMPLETED (G3 pending)`
3. Observe Yui actually moving **if C0/C2 existing navigation machinery accepts and executes the goal**. This is not guaranteed by the `C2_ACCEPTED` message alone.

If `HOLD`, stop; do not immediately retry the same actor or reset its SQLite state.

## 3 — Monitor ownership and result independently

```mcfunction
/npcortex debug cplus taskmovestatus yui
/npcortex c2 status yui
/npcortex c1 info yui
```

Record actual C2 phase and final position. `C2_ACCEPTED` **must not** be called `COMPLETED`. G3 will add trusted movement verification, terminal cancel ACK and recovery. If C2 ends, the G2 durable record remains unresolved until G3 by design.

## 4 — Risk / conflict regression (use a different NPC or fresh disposable test world)

- Follow active: `/npcortex c2 follow start yui` if that existing command is supported; G2 move must HOLD without stealing C2 Follow motion. Stop Follow through the existing supported C2 command. Test under the actual existing command grammar.
- E1 QUEUED/HOLD head: movement must refuse until old tasks are cancelled/reconciled by rightful requester; never jump queue.
- Backend offline: movement must not start on rejected/unacknowledged reserve.
- Target outside radius/unloaded: no movement and no chunk force-loading.
- Save/Rejoin during active movement: G2 backend attempt becomes `HOLD`, no automatic replay (requires G3 to reconcile).
- C1/C2 inventory/follow/nav/save and C-F1/C-F2 regressions: run legacy checklists, verify no changes.
- Multiplayer and actual 1/3/5/10-NPC MSPT remain PENDING unless performed.

**Stop safety:** If a real C2 goal is running and you need to stop it, use the existing `/npcortex c2 cancel yui` command; G2 still requires later durable reconciliation and remains HOLD/pending. Never use database deletion/reset as a workaround.
