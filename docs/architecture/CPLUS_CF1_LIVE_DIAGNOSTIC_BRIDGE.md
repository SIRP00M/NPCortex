# NPCortex C-F1 D+ — Live Diagnostic Bridge (Synthetic-only, E-ready)

Status: SECOND-STAGE CANDIDATE. Baseline: C-F1 D+ staged foundation on top of C-F0 Windows acceptance. User-confirmed old gameplay regression still works (specific coverage only). No GitHub operations.

## Audit, before change

- `CPlusDurableLedger`: append-only fsync/hash-chained journal, pure Java, previously NOT integrated with Minecraft. `force(true)` must never happen on server END tick.
- `C2ActionJournal`: legacy one-row/actor SavedData, cannot be replaced or used as crash-durable proof. `C1InventoryJournal`: authority for inventory snapshots/receipts.
- Backend C-F1 SQLite projection/outbox is staged, with no transport or endpoint; never pretend `forge` and SQLite share a transaction.
- C1/C2 movement and Follow remain unchanged. New integration must not start, retry or resume old-world effects.

## Changes in this candidate

- `CPlusF1AsyncDiagnostics`: one daemon IO worker, FIFO bounded queue (32), immutable futures returned after journal `force(true)` acknowledgement. No blocking IO in normal server tick or command handler. Queue saturation rejects and never performs fallback synchronous writes.
- World-bound journal (`boundWorld()`); fail-closed on wrong-world path, checksum mismatch, truncated record, lock conflict and IO failure. No auto-truncate, recovery, replay or relocation.
- Per-actor session fencing: each new opened journal process chooses `previous persisted epoch + 1` before any new probe Intent. Synthetic intent/cancel events are the ONLY recorded events. No actual C1/C2 Action is journaled here.
- Privileged opt-in dev commands: `/npcortex debug cplus inspect <npc>` and `/npcortex debug cplus probe <npc>`; these are diagnostic fixtures, not the Unified Public API promised for C-F3. Require operator (permission level 2) through existing `/npcortex` root.
- Data is saved in the CURRENT WORLD directory: `data/npcortex/cplus_f1_diagnostics.bin`, not a global user profile or mod config. Shutdown closes the journal and releases file lock after a bounded drain. The stop hook is NOT a regular server tick.
- The server thread only queues synthetic operations and handles completion messages via `server.execute()`. No reading game state or sending chat from IO worker.
- E-ready event identities (world/actor/task/action/attempt/event/parent) remain unchanged, without activating saga orchestration.

## Invariants and remaining hazards

1. Synthetic probe uses `FENCE -> INTENT -> CANCELLED` with `synthetic-no-world-effect` evidence marker; only because NOTHING is applied to Minecraft. This receipt cannot verify a live effect.
2. No world mutation, no inventory changes, no passive item pickup, no motion ownership changes, no follow commands or scheduler replacement.
3. `close()` on server stopping may await up to three seconds off ordinary ticks. A stalled filesystem can cause stop delay; bounded queue/full disk cause fail-closed behavior, not fake ACKs.
4. Journal fsync for a new file does not guarantee the directory entry/world save/back-end SQLite are atomically committed. Windows disk power-loss behavior and startup-migration safety are NOT proven.
5. A corrupted journal is held for manual inspection. A `C2ActionJournal.reset` MUST NOT be interpreted as clearing this ledger.
6. Data is limited by current ledger max size (32 MiB); full retention/compaction and operator quarantine tools are future scope.
7. A real executor must add verified IO barriers, snapshot evidence, permission & chunk validation, `IN_DOUBT` reconciliation and true world-effect fault injection separately before being enabled.

## D+ to E

The wire identities/parent-event identity stay stable. E saga workflow and partial progress tracking are still later work; no automatic compensation, no assumed undo, and no forced transfer from other actors.
