# Permission and world-effect ledger: DESIGN ONLY (G4)

Nothing here is implemented. No runtime ledger, no schema change, no mutation. It fixes the contracts so a later, separately
approved sprint can build one guarded action (`break_block`) without redesign.

## 1. Durability facts the design must respect

| Store | Commit | Survives a crash? |
|---|---|---|
| Backend SQLite (`cplus_e3_*`, `cplus_m04_*`) | immediately, on COMMIT | yes |
| Forge outbox (`CPlusOutbox.write`) | `FileChannel.force` + atomic move | yes (strongest Forge-side record) |
| Forge SavedData (`C2ActionJournal`, C1 data) | in memory + `setDirty`; written on autosave, save and stop; NOT fsynced (stated in `C2ActionJournal`) | only if a save ran after the change |
| World chunks (the block itself) | written when the chunk is saved (autosave, save, unload, stop) | only if saved |

These stores commit independently and never atomically together. **Exactly-once world effects cannot be guaranteed and are not
claimed.** The achievable promise is: no automatic replay of an uncertain effect, every uncertainty ends as an explicit state
(`IN_DOUBT`/HOLD), reconciliation is by observing the world, and a human decides what a retry means.

## 2. Authority (deny by default)

Precedent in the tree: `cplus/CPlusF2ReadOnlyPolicy` already answers every mutation with `UNKNOWN` (never `ALLOW`) and
`usableEvidence` binds evidence to world/dimension/actor/session and a maximum age. The adapter below is that class growing an
`ALLOW` path behind the conditions listed here, not a parallel system.

1. Origin: an attested origin (see `COMMAND_ORIGIN_INVESTIGATION.md`; preferably the GUI packet sender). UNKNOWN origin = DENY.
2. Requester: operator permission AND membership in an Owner-controlled allow-list file in the server config (not a data pack,
   not the database): `{requester UUID, action, block ids, dimension, optional box, max uses per task}`. Missing entry = DENY.
3. Policy preconditions, re-read in the same tick as the effect: actor live and idle (see 3), target in a loaded chunk, within
   reach, correct tool, block id equals the observed id and is on the allow-list, observation fresh (`fresh_until_tick`).
4. Protection mods and spawn protection: the effect goes through the vanilla server game-mode path, which fires the normal break
   events, so any other mod can veto it; a veto is `PERMISSION_DENIED` with no effect. "Cannot tell" is a deny.
5. Allowing never implies AI dispatch: model output only ever creates a REQUEST that passes 1-4.

## 3. Ownership and the motion fence

An action starts only if the actor has no C2 goal, no Follow session, no pending E3/G3 attempt, no transport recovery and no other
effect attempt (the same checks `CPlusM04Runtime.preview` already makes). The action holds the actor's motion fence from intent to
terminal; the fence is released only by the durable terminal ACK, exactly like E3/G3.

## 4. Order of operations for one block break

1. OBSERVE (read-only, fresh) and record the expected pre-state (block id, position, tick).
2. AUTHORIZE (section 2) and take the fence (section 3). Fail here = no effect, no record beyond a rejection.
3. INTENT, written before the effect: backend `prepare_effect` (SQLite) AND an fsynced outbox record (`attempt_id`, action id,
   pre-state, requester, origin kind). The backend ACK is required; backend unreachable = no effect (fail closed).
4. EXECUTE on the server thread through the vanilla break path; timed breaks advance per tick and poll the cancel flag every tick.
5. VERIFY server-side: re-observe the position (and drops). A `VERIFIED_WORLD_RECEIPT` is only a server observation, never a model
   claim. Record it in the outbox and the SavedData journal.
6. SAVE BARRIER: hold the terminal until a world save that includes the chunk has been observed (Forge chunk-save event), so a
   rollback can no longer follow a committed terminal. This shrinks the dangerous window; it does not make it atomic: Forge's
   `ChunkDataEvent.Save` fires when the chunk is serialised, and the region-file write that follows is asynchronous, so a crash
   between the event and the disk write can still lose the block. Row 3 therefore still needs the start-up re-verification.
7. TERMINAL to the backend (idempotent on `task_id`/`attempt_id`/evidence hash, as G3 already is), ACK, release the fence.

## 5. Failure matrix

`pre` = block still as observed, `post` = block changed as expected. Columns are what survives the crash.

| # | Situation | SQLite | Outbox | SavedData | World | Detection | Required outcome |
|---|---|---|---|---|---|---|---|
| 1 | Crash before the effect | PREPARED | intent | maybe | pre | restart sweep finds PREPARED + observation shows pre | `NO_EFFECT`; fence released by an explicit operator reconcile; a retry is a NEW attempt id, never a replay |
| 2 | Crash after the effect, before the terminal | PREPARED | intent (+ receipt if written) | maybe lost | post (if the chunk was saved) | PREPARED + observation shows post | `IN_DOUBT` -> reconcile with a trusted witness (intent record + fresh observation) -> `COMPLETED_RECONCILED`, flagged as reconciled; never auto-replayed |
| 3 | Terminal committed, then chunk rollback | COMPLETED | receipt | lost | pre | start-up re-verification of recent terminals against the world | `COMPLETED_BUT_WORLD_REVERTED` incident, HOLD, no automatic re-execution. The save barrier (step 6) is what prevents it |
| 4 | Lost ACK | terminal stored | kept until ACK | any | post | Forge retries the identical terminal | idempotent terminal returns the stored result; the fence stays held until the ACK arrives |
| 5 | Cancel during execution | PREPARED / CANCEL_PENDING | intent + cancel intent | any | pre or post | executor tick observes the world at the quiescent point | the observation decides: post => `COMPLETED` (never claim CANCELLED when the effect exists); pre => `CANCELLED`; ack only when the executor is quiescent |
| 6 | World changed externally before verification | PREPARED | intent | any | neither pre nor expected post | verify step compares with the recorded pre-state | `EXTERNALLY_CONSUMED` / `UNKNOWN` (the M04 effect enum already has both) -> HOLD; if the pre-state differed at execute time the action aborts with no effect |

Also fail closed, with no automatic recovery: backend down at INTENT, outbox write failure, observation unavailable (unloaded
chunk), duplicate request for an active actor, attempt id reuse, and any identity mismatch (`world`, `actor`, `session`).

## 6. Idempotency and duplicate prevention

One active effect attempt per actor (unique partial index like `cplus_e3_g2_active_actor`); `(world, actor, task, attempt)` is
unique; terminal evidence is hash-compared on replay; a terminal row is never re-judged; a restart turns every unresolved attempt
into HOLD (existing E3 behaviour) and a HOLD attempt can never become executable again. `metadata_retry` in the M04 store already
means "retry bookkeeping only, never re-execute".

## 7. Decisions the Owner must take before implementation

Allow-list format and location; whether mutating commands exist at all or only the GUI packet; save-barrier behaviour for long
autosave intervals (an interim `EFFECT_APPLIED_UNSAVED` state); which blocks are ever allowed first (a placed test log); and the
retry policy after `NO_EFFECT`. A schema change for the ledger tables, the executor and any world mutation each need their own
approval.
