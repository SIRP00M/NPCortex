## Windows test cleanup fix #1 — Run before gameplay

The Forge build passed on the previous Candidate, but `test-cplus-e3-g3-big.ps1` failed twice during `TemporaryDirectory` deletion of test databases (`WinError 32`). The revised Candidate now closes test-only SQLite handles explicitly; no production code changed.

With a clean extraction of this Candidate in `F:\AI\NPCortex` (or replace with your extracted folder):

```powershell
cd F:\AI\NPCortex
# Test only the Python regression first (no Minecraft or Forge build required):
.\scripts\test-cplus-e3-g3-big.ps1 -BackendOnly
# Then also run Java/Gson contract validation using Windows Gradle:
.\scripts\test-cplus-e3-g3-big.ps1
# Forge build and game are separate processes, not part of the test script:
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-backend.ps1
# In a different PowerShell terminal:
.\scripts\dev-forge.ps1 -Task runClient
```

Expected: all 5 G3/G2 Python groups return `OK` with no `WinError 32`; Java self-tests pass when Gradle is available. Run the original gameplay checklist below only afterwards. **Do not edit/delete the real SQLite DB, HOLD attempts, world saves, or tokens** to address test-fixture errors. Backend/Forge production source is unchanged in this fix.

# NPCortex G3 Combined Sprint — Windows Gameplay Checklist

**Use a new Creative test world** and clean-extract this ZIP; keep previous world/SQLite untouched. Keep Backend started before Minecraft. The old G2 `HOLD` attempt must NOT be manually deleted or retried.

## Commands in separate terminals

```powershell
cd F:\AI\NPCortex
# Terminal A: Compile only
.\scripts\dev-forge.ps1 -Task build
# Terminal B: Focused tests (includes Java/Gson tests)
.\scripts\test-cplus-e3-g3-big.ps1
# Terminal C: Backend (leave running)
.\scripts\dev-backend.ps1
# Terminal D: Minecraft (leave running)
.\scripts\dev-forge.ps1 -Task runClient
```

## Happy path: move, finalize, repeat

1. `/npcortex c1 spawn yui` (if not already spawned); `/npcortex c1 info yui`.
2. In a **new test world only**, verify `/npcortex debug cplus taskqueue yui` is empty and `/npcortex debug cplus taskmovestatus yui` is `NONE`.
3. Choose one nearby loaded, flat and safe target (within six horizontal and three vertical blocks); do not reuse example coordinates blindly.
4. `/npcortex debug cplus taskmovepreview yui <x> <y> <z>`; then `/npcortex debug cplus taskmove yui <x> <y> <z>`.
5. After Yui physically reaches the target, inspect `/npcortex c2 status yui` for `COMPLETED`, `verified=true`, `local=true`. **Do not send a second move yet.**
6. `/npcortex debug cplus taskmovefinalize yui` (new command). Expect `G3 COMPLETED ... durable ACK; actor fence released`.
7. `/npcortex debug cplus taskmovestatus yui` must show G3 `COMPLETED` and no active C1 movement.
8. Select a **different nearby loaded target**, repeat steps 4–7. Both tasks must have distinct Goal IDs, no stale completion claim.

If G3 returns HOLD, stop. In particular, do not force `taskmovefinalize` on an attempt already `HOLD` from Save/Rejoin; do not delete SQLite.

## Goal-scoped cancel

1. In another disposable world/session, start a nearby but sufficiently distant valid route so that Yui is still moving.
2. `/npcortex debug cplus taskmovecancel yui`.
3. Expected `G3 CANCEL_PENDING`, and C2 cancellation only for matching Goal. Do **not** use legacy broad `c2 cancel` as a substitute.
4. Inspect `/npcortex c2 status yui` periodically, until C2/C0 terminal reconciliation shows `CANCELLED` (or a different terminal result).
5. Only if C2 is `CANCELLED`: `/npcortex debug cplus taskmovereconcile yui`.
6. Expect G3 `CANCELLED` durable ACK and fence released. Ensure Follow on another NPC is unaffected.

## Negative / fault tests

- An operator other than original requester must not finalize/cancel another user's attempt.
- Cancel after C2 already completed must not alter the finished Goal. Completed or stale goal must not be converted to CANCELLED.
- Backend offline during finalize/ack: no optimistic COMPLETE, new work blocked unless backend has durably committed terminal receipt and status confirms it.
- Backend restart during motion/cancel: unresolved Attempt becomes HOLD; never auto-resume or auto-unlock.
- Save/Rejoin in fresh test world; keep historical attempts in durable DB, no manual deletion.
- C1/C2/Follow/Inventory/C-F1/C-F2/E1/E2 regression; no break/place/pickup mutations.
- Performance 1/3/5/10 NPC MSPT is pending until real profiler evidence exists.

**Do not use a valuable survival world for crash tests or kill Minecraft while writing world saves.**
