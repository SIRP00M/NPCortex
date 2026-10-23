# NPCortex C+ C-F1 D+ -> E-ready Architecture

Status: STAGED FOUNDATION CANDIDATE. Not deployed as a live world-action runtime. Baseline: C-F0 Windows acceptance 8/8 user-confirmed. No GitHub operations.

## Decision

Implement D+ crash-consistent, effect-aware recovery foundations **without** attempting a distributed Forge-world/SQLite transaction. Reserve event/task/parent identities for future E provenance graphs and saga planning (C-F3+). Do not implement Saga execution in C-F1.

## Source-backed initial gap

- `C2ActionJournal` has only one last row per actor in Minecraft SavedData; SavedData is not immediate fsync. Unreadable rows can be skipped.
- `C1InventoryJournal` is the existing inventory authority and must not be replaced by a second ledger.
- Backend SQLite v4 independently commits agent actions; no atomicity with Minecraft world saving.
- C-F0 transition model lets EXECUTING/VERIFYING go to FAILED without effect-specific evidence; D+ overlay guards side effects.
- Custom ServerPlayer passive pickup behavior and Forge pickup events still need focused source/gameplay verification before implementing Pickup Skill.

## Added in this staged candidate

- `CPlusDurableLedger`: pure-Java append-only framed record log for **synthetic/read-only** actions, SHA-256 hash chain, 32 MB read/write bound, FileChannel exclusive writer lock, `force(true)` durability ACK, fail-closed truncation/corruption handling; one new session FENCE required after re-open. **Never call synchronous force() on the Minecraft server tick.** No game subsystem creates or writes this log yet.
- Immutable `Event` schema: sequence/event UUID, world/actor/task/action/attempt UUID, optional parent event UUID, epoch, stage, action fingerprint, evidence reference. Parent link is E-ready metadata, not proof of provenance.
- Lifecycle checkpoints: FENCE, INTENT, EXECUTION_STARTED, OBSERVED_PARTIAL/EXTERNAL, IN_DOUBT, RECONCILED, VERIFIED, CANCELLED. No execution without same-session durable INTENT; no implicit retry, forged completion, or cancellation-as-undo.
- `CPlusF1EffectGuard` + `CPlusEffectRecovery` (Java) and mirrored Python `cplus_f1_effects`: UNKNOWN, PARTIAL, VERIFIED and EXTERNALLY_CONSUMED, with user-authoritative observed quantities; a task may count only actual inventory progress, never items held by somebody else.
- `cplus_f1_projection.py`: opt-in additive SQLite tables for event projection and transactional outbox with bounded reads, idempotent event/ACK IDs and stage/epoch/identity conflict checks. Stays on schema v4; no live service endpoints, no network transport, and no SQL migration of old tables.
- New synthetic Java and Python tests. Existing C0/C1/C2 implementation files and commands stay unchanged.

## Hard safety constraints

1. The server world is authoritative for effects. Journal evidence IDs are *references* only; staging code has no world permission to validate them.
2. Fsync/SQLite COMMIT ordering can establish durable **intent**, but CANNOT guarantee world mutations are atomically persisted with Minecraft world save. A crash window after side effect remains `IN_DOUBT` until authoritative reconciliation.
3. Duplicate event IDs with identical bytes should be idempotent in the Python projection; contradictory replay rejected. Java ledger immutability rejects duplicate append; caller must first check recorded ACK/recover, not reissue action.
4. After restart, fence with new increasing actor epoch before new event writes. Intent from old epoch cannot start execution in a new session.
5. No 'Undo' assumption. A drop may be picked up by this NPC, another NPC/player, hopper, merge, despawn or be destroyed. Unknown provenance remains UNKNOWN; verified inventory of another actor does not count toward this actor's goal.
6. Compensation (e.g. gather replacement logs) is a NEW authorized action, only after world re-observation. Never mint duplicate items or remove them forcibly from another actor.
7. Corrupt/truncated log loads fail closed; no silent truncation or skipping. Manual quarantine/repair tooling and full retention/compaction remain pending.
8. A live asynchronous journal writer, bounded wait queues, crash/process-kill tests and Forge-world integration are still needed before enabling any mutation.

## D+ to E migration without replacing contract IDs

Future C-F3 E task graph can index: `(worldId, actorId, taskId, actionId, attemptId, eventId, parentEventId)` and calculate progress from VERIFIED/PARTIAL/UNKNOWN effect observations. A saga must specify whether each step is reversible, compensatable or irreversible; rollback is not a default. E would add graph edges, event provenance trust, orchestrated partial progress and bounded history compaction, without making a second inventory authority.

## Scope not implemented here

No Break/Place/Use/Chest/Pickup Executor; no C1/C2 lifecycle changes; no new Minecraft commands, no runtime registration, no scheduler replacement, no legacy journal migration, no automatic restart replay, no live Forge <-> SQLite transport or outbox worker, no true async fsync barrier, no direct Forge permission/claim evaluation, and no measured gameplay/MSPT changes.

## Release gating

This is an auditable **C-F1 D+ staged implementation**, not final C-F1 Foundation Acceptance. Before turning it on for world mutation: implement async durability ACK path that cannot block ticks; fail-closed IO/error handling and fencing integrated on Forge startup; wire backend inbox/outbox with verified authorization, handle delayed duplicate ACK; design world/inventory effect observers with partial provenance; inject real process crashes at each checkpoint; re-check legacy save migration; test 1/3/5/10 NPC MSPT on Windows and actual Forge 1.20.1 build. Require renewed scoped approval for any new live executor or runtime migration.
