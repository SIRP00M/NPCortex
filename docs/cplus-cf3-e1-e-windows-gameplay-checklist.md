# Windows Gameplay Checklist — C-F3 E1-E / Gate-1

Test on a **new disposable world**, separate from valuable saves. Java 17, Forge 1.20.1, local backend running with same local auth secret (do not print it). Both multiplayer testers need Minecraft operator permission **2**; normal player access is intentionally unavailable.

**Build Fix #1:** Previous candidate failed Java `compileJava` with 2 wrong `idOperation(...)` argument counts. This ZIP corrects only those 2 call sites. **Run BUILD first; do not proceed to gameplay or Gate-2 if build fails.**

## Separate terminals/actions

**BUILD only**:
```powershell
cd C:\path\to\NPCortex
.\scripts\dev-forge.ps1 -Task build
```
**TEST focused only**:
```powershell
cd C:\path\to\NPCortex
.\scripts\test-cplus-e1-extended.ps1
```
**TEST full backend, separate optional gate**:
```powershell
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
python -W error::ResourceWarning -m unittest discover -s backend/tests -q
```
**BACKEND only** (terminal A):
```powershell
cd C:\path\to\NPCortex
.\scripts\dev-backend.ps1
```
**RUN GAME only** (terminal B):
```powershell
cd C:\path\to\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

## Gameplay

1. Fresh world, cheats enabled. Spawn Yui via existing C1 spawn command and confirm active avatar. Do not run on real/valuable saves.
2. Execute `/npcortex debug cplus world yui` then `/npcortex debug cplus epreview yui`. Observe C-F2 `break=UNKNOWN` and E-P0 read-only report.
3. Operator/player A: `/npcortex debug cplus tasksubmit yui`; record `task_id`, `request_id` (from backend SQLite if required), sequence #1. Operator/player B: repeat; record #2. Sequentially submitting commands avoids simultaneous network reordering; order is server *accepted sequence*, not chat message timestamp.
4. `/npcortex debug cplus taskqueue yui` shows `#1:QUEUED, #2:QUEUED`; `/npcortex debug cplus taskstatus yui <task_id>` returns the matching task and exactly three read-only nodes.
5. Player B: `/npcortex debug cplus taskcancel yui <A_task_id>` must be **REJECTED**. Player A cancels own task and status becomes `CANCELLED`. Player B then cancels own task. Confirm no movement/world/inventory mutations.
6. Submit another task, restart Backend and Save/Quit/Rejoin world, start Backend again; `/npcortex debug cplus taskqueue yui` still has queued task, same UUID and sequence; task MUST NOT auto-run.
7. Backend offline: `tasksubmit` must report HOLD/backend unavailable without automatic retry. Start backend and manually submit a *new* request; observe durable queue behavior.
8. Re-run C1/C2 Follow, navigation/stop, inventory transaction/replay, C-F1 inspect/sync/audit and C-F2 world checks. Check `latest.log` for errors.
9. Measure real 1/3/5/10 NPC MSPT, normal vs busy queue and concurrent requests; synthetic 1/3/5/10 unit tests do not replace this.

## Evidence to return

- Windows Forge build PASS/FAIL and relevant stack trace if failed.
- E1 focused runner complete output and optional full backend suite.
- Chat/log screenshot or transcript for tasksubmit #1/#2, queue, status and cancel rejection.
- Backend offline→online and Save/Rejoin results.
- Any C1/C2 regression or unexpected state/error. If a Gate fails, **stop; do not open Gate-2**.
