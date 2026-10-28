# Windows Gameplay Checklist — NPCortex C-F3 E1-E Gate 2 (Read-only)

Test only on a **new disposable Minecraft Forge 1.20.1 world**, Java Temurin 17, with a local Backend. The E2 commands are operator-only. Do NOT unpack the ZIP over a valuable world or live secret/config folder. Use a clean extraction and back up the previous save.

## Separate commands (PowerShell)

**BUILD only:**
```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task build
```

**Focused TEST only:**
```powershell
cd F:\AI\NPCortex
.\scripts\test-cplus-e2-readonly.ps1
```

**Full backend TEST (optional, independent):**
```powershell
cd F:\AI\NPCortex
.\scripts\test-cplus-e2-readonly.ps1 -IncludeFullBackend
```

**BACKEND only — Terminal A:**
```powershell
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1
```

**RUN MINECRAFT only — Terminal B:**
```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

## Game acceptance order

1. Confirm Yui is spawned (`/npcortex c1 info yui`) and C-F2 still reports `break=UNKNOWN` (`/npcortex debug cplus world yui`). Keep Yui stationary for initial E2 read-only tests.
2. **Submit new Task in current session**: `/npcortex debug cplus tasksubmit yui` then `/npcortex debug cplus taskqueue yui`. If old earlier-session E1 tasks block head, manually cancel them with their requester before this test; **do not delete SQLite rows**.
3. `/npcortex debug cplus taskdispatch yui` — expect `E2 reserved task=... #...; run ... taskverify ...; NO world mutation`. This reserves a `READ_CELL` lease and moves FIFO head to `DISPATCHING`. Observe `/npcortex debug cplus taskqueue yui`.
4. `/npcortex debug cplus taskverify yui` — expect `E2 read-only VERIFIED task=... receipts=3; lease released; NO world mutation`. Verify task state via `/npcortex debug cplus taskstatus yui <task_id>`. **Neither command should move Yui or change a block/inventory item.**
5. FIFO: submit two tasks, verify #1 before #2. Attempt a second `taskdispatch yui` before verifying #1; it must HOLD/not take #2. After verifying #1, dispatch/verify #2.
6. Cancel/lease-release: submit new task, `taskdispatch`, then **as the original requester** run `/npcortex debug cplus taskcancel yui <task_id>`; state becomes `CANCELLED`. Confirm a new task can dispatch. If no local ticket exists, `taskverify` must HOLD, never recreate a lease or issue world effects.
7. Resource collision: use two NPCs (e.g. Yui and Ai) **standing at the same block center** with fresh tasks. Dispatch Yui first, then Ai: Ai must get `RESOURCE_BUSY`/`HOLD` message; cancel/verify Yui, then dispatch Ai and verify. Opposite order should behave analogously. Do not teleport NPCs with E2; use existing test placement only.
8. Backend restart: dispatch a new task, **do not verify**; stop and start Backend. Old task must be `HOLD`, not auto-verified or replayed, and lease fenced. Original requester may cancel held task before sending a new one. Backend offline should fail closed without game crash and no automatic retry.
9. Bad data tests (synthetic automated): forged ALLOW, wrong actor/world/dimension/session, duplicate/late proofs, corrupt receipts, stale generations, expired leases and unexpected restart. Do not modify production DB; use tests/faults only on disposable copies.
10. Regression: C1 spawn, identity, normal C2 navigation/follow/stop, C1 inventory equipment, C-F1 inspect/sync/audit, C-F2 world and E1 submit/status/cancel. Note genuine MSPT for 1/3/5/10 NPC and worst-case tick spikes; synthetic tests do not count.

## Return evidence

- Windows `BUILD SUCCESSFUL` output (or Java compile errors), focused test summary and optional full backend suite summary.
- Chat screenshots for E2 `DISPATCHING` → `READ_ONLY_VERIFIED`, `receipts=3`, FIFO order, cancel, collision, Backend Restart → HOLD.
- Explicit PASS/FAIL/PENDING for each test; do not label Gate 2 complete solely from Python tests.
