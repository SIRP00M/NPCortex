# Stage 4 Mutation Safety Foundation: NO-OP world action (offline candidate, 2026-10-28)

Owner-approved Option D+ (authority + permission + durable ledger + no-op executor + Manager GUI/backend integration in one
milestone). **The only action is `noop`: it changes nothing in the world.** `break_block`, `place_block`, `pickup_item`,
`use_item`, `equip_item` do not exist in any new code path and are not representable in the allow-list or the ledger.
Everything below is OFFLINE verified. Live Minecraft behaviour is PENDING (see section 9).

## 1. Flow

```
Manager GUI button "No-op" (ManagerScreen)  -> NoopReq{opId, npc name, reconcile}      (no UUID, token or coordinates on the wire)
  -> ManagerNetwork.handleServer: requester = NetworkEvent.Context.getSender()          (authenticated connection)
  -> ManagerCore.handle: host.authorized (op level 2 or single-player owner)  AND  one operation per player / per NPC
  -> WorldEffectFlow.run(requester, npc)                                                 (pure Java; server thread)
       1 policy  : Owner allow-list file config/npcortex-world-actions.json (deny-by-default, re-read per request)
       2 actor   : C1 identity + live avatar + lifecycle + dimension (server state only), session = per-JVM session id
       3 ownership: no C2 goal, Follow, E3 G2/G3, transport recovery, C1/C2 motion receipt, unresolved C2 journal row, other WE run
       4 RESERVE (backend, outbox-fsynced body): durable intent + actor fence, ONE active attempt per (world, actor)
       5 START   (backend): durable EXECUTION_STARTED marker. The executor can only be called with a Started token minted from
                 a validated EXECUTING reply: no marker, no executor.
       6 ONE server-thread task: re-resolve identity, re-check policy/requester/ownership, fixture hash -> executeNoop -> fixture hash
                 (no asynchronous hop anywhere inside this task; pinned by WorldEffectFlowSelfTest)
       7 FINALIZE (backend): typed NO_EFFECT receipt -> durable terminal -> ACK{durable_ack, release_actor_fence}
       8 GUI reply: OK only after a validated durable ACK; every uncertain end is UNKNOWN and points at Reconcile
```

Authority is NOT derived from permission level 2, `source.getPlayer()`, a command, a data pack, a backend token or a nonce.
Those remain separate gates. A data pack, command block, console or the backend cannot start a world action: the only caller of
`WorldEffectRuntime.request` is the Manager packet path (static contract `Stage4NoopFoundationContract`). The backend cannot
verify a player: it records the Forge-attested decision (`origin=MANAGER_PACKET`, `authorization.decision=ALLOW`, entry id,
allow-list sha256) and refuses anything else; a holder of the local token could still forge that body (documented limit, same
trust boundary as E3).

## 2. Ledger states (`backend/src/npcortex_backend/cplus_we_ledger.py`, tables `cplus_we_runs`, `cplus_we_terminals`)

| State | Meaning | Fence |
|---|---|---|
| `RESERVED` | intent + fence stored; executor has NOT run (marker not durable) | held |
| `EXECUTING` | durable EXECUTION_STARTED marker; outcome not yet recorded | held |
| `NO_EFFECT_VERIFIED` | executed after the marker, typed receipt with equal pre/post fixture hash | released by the ACK |
| `FAILED_NO_EFFECT` | stopped before executing (precondition, lost start ACK, restart before the marker) | released |
| `CANCELLED_NO_EFFECT` | cancelled before the marker (requester left, operator reconcile of a RESERVED row) | released |
| `HOLD_IN_DOUBT` | unknown after the marker (restart, session ended, fixture changed) | **held** |
| `RECONCILED_NO_EFFECT` | operator released a held row; the earlier attempt is NOT claimed verified | released |

A successful no-op is never `CANCELLED`. Rules: a terminal is never re-judged (hash compared on replay); a late receipt cannot
resurrect a HOLD; cancel is refused once the marker exists; rows of a session that no longer exists settle on the next reserve /
start-up (RESERVED -> FAILED_NO_EFFECT because the executor cannot have run; EXECUTING -> HOLD_IN_DOUBT); nothing is replayed,
deleted or reset. Exactly-once physical effects and atomicity with Minecraft chunk storage are NOT claimed (a no-op has no
chunk write, so it proves nothing about chunk-save crash safety).

Routes (local token required): `POST /v1/cplus/we/{reserve,start,finalize,cancel,hold,reconcile,status}`. Typed receipt:
`docs/contracts/world_effect_receipt_v1.json`.

## 3. G0 source confirmations (the seven approved questions)

1. **Are G2 and M04 reservations serialized?** Yes, by construction, for one SQLite file. Every store opens its own connection to
   the same file (`ReadOnlyGraph.connection()`: WAL, `synchronous=FULL`, `busy_timeout`; `M04Store._db`: `synchronous=FULL`) and
   every Check + Reserve runs after `BEGIN IMMEDIATE`, which takes SQLite's single write lock. Partial unique indexes cannot span
   tables, so the cross-store check is a SELECT inside that same write transaction. Evidence: `test_concurrent_cross_store_reservations_admit_exactly_one`
   (12 threads, separate connections, G2 vs WE, exactly one winner, no lock errors; repeated 10x by `test-we-noop.ps1`) and a mutation
   check (removing the G2 guard fails the test). Limit: threads in one process, not two OS processes (SQLite's lock is the same).
   Defect fixed: G2 reserve did not look at `cplus_m04_runs`/`cplus_we_runs`; `M04Store._guard_legacy` did not look at WE. Both directions now check.
2. **Can M04 tables evolve?** Not safely: CHECK constraints cannot be altered in SQLite and the M04 step list has no NO_EFFECT. M04
   tables are left untouched; the new store is additive (`cplus_we_*`). No `user_version` change (the repository precedent for every `cplus_*` table).
3. **Historical/local M04 records?** Not assumed absent. The repository contains none (the table name occurs only in `cplus_m04_store.py`; the committed
   fixture database `v4_baseline.sqlite3` has no `cplus_m04_*` table; no production code calls `M04Store.initialize()`; the only importers are tests). A user machine could still have M04 tables from an experiment: the WE
   store neither reads nor writes them except for the SELECT fence check, which treats a missing table as "never used".
4. **Can the journal represent typed no-op outcomes?** `CPlusDurableLedger.Stage` has no NO_EFFECT stage and old readers
   reject unknown stages. It is NOT used. Forge-side durability is the existing `CPlusOutbox` (fsynced JSON per task and route, new
   route names only), so no historical reader sees a new record type.
5. **Effective E3 SQLite durability?** WAL, `synchronous=FULL`, `foreign_keys=ON`, `busy_timeout=3000` for `GuardedMotionStore` and the
   WE store (same inherited `connection()`); asserted by `test_effective_durability_settings`.
6. **Can an executor run before the durable marker?** No: `WorldEffectFlow.executeNoop(Started)` needs a token whose only
   factory is `startAck`, which requires an `EXECUTING` reply echoing the exact attempt with `execution_started=true`
   (tests: start faults TRANSPORT/REFUSE/LOST_REPLY/BAD_REPLY, wrong state, missing flag, claimed world effect; `Started` constructor is private).
   The server-thread task that runs the executor contains no asynchronous hop (event-log window assertion).
7. **Identity consistency?** requester = packet sender; actor/profile/world/dimension from C1 saved state; session from
   `CPlusF2WorldSampler.session` (the same per-JVM id every C+ snapshot uses). The same six identities are bound in the policy decision, the reserve
   body, every later body (exact echo), the backend row, the unique fence and the final server-thread re-resolve (any difference => FAILED_NO_EFFECT).

No destructive migration, incompatible persistent contract or major refactor was needed; no Owner stop was triggered.

## 4. Permission (Owner allow-list)

`config/npcortex-world-actions.json` (Forge config dir; not a data pack, not the database). Missing, unreadable, symlinked, >16 KiB,
non-UTF-8, duplicate keys, unknown keys, unknown action, malformed UUID/dimension/actor => deny everything. Install with
`scripts\install-we-allowlist.ps1 -ConfigDir <config dir> -PlayerUuid <uuid> -Actors Reika` (never overwrites without `-Force`, keeps a `.bak`).
`"*"` in `actors` is an explicit opt-in. The file is re-read on every request, so removing it revokes at once; the decision is re-checked on the
server thread immediately before the executor.

## 5. Files

New: `WorldActionPolicy.java`, `WorldEffectFlow.java`, `WorldEffectRuntime.java`, `cplus_we_ledger.py`, self-tests
`WorldActionPolicySelfTest`, `WorldEffectFlowSelfTest`, backend tests `test_cplus_we_ledger.py`, `test_cplus_we_http.py`,
`test_cplus_we_java_wire.py` (+ `fixtures/world_effect_wire_v1.json`), `scripts/test-we-noop.ps1`, `scripts/install-we-allowlist.ps1`,
`docs/contracts/world_effect_receipt_v1.json`, this document.

Modified (minimal): `ManagerProtocol` (NoopReq, Op.NOOP appended), `ManagerCore` (Host.worldAction, noop handling),
`CharacterManagerService` (host binding), `ManagerScreen` + `en_us.json` (two buttons), `NPCortex` (stop hook),
`CPlusF2WorldSampler` (`session()` accessor), `CPlusE3G2Runtime` (reads the WE pending flag), `CPlusTransport` (retry set: the idempotent WE routes),
`cplus_e3_g2_motion.py` (cross-store guard in reserve), `cplus_m04_store.py` (WE guard in `_guard_legacy`), `server.py` (WE routes),
`ManagerCoreSelfTest`, `ManagerProtocolSelfTest`, `test_milestone_static_contracts.py`.

## 6. Limitations (explicit)

- Offline fixture evidence only. No Minecraft run, no Manager GUI click, no real chunk save, no dedicated server.
- The fixture hash covers the 3x3x3 blocks around the NPC, inventory, equipment and item entities within 8 blocks. A no-op inside one
  synchronous task cannot change them, so equality is a structural check; it becomes meaningful only for a future real executor.
- A backend token holder can forge the Forge-attested authorization fields (same trust boundary as E3).
- Outbox files (`<world>/data/npcortex/transport/<task>-<route>.json`) are never cleaned, as for E3.
- `HOLD_IN_DOUBT` and a stuck `EXECUTING`/`RESERVED` row need the GUI **Rec.** button (operator reconcile); there is no automatic release.
- The race test uses threads in one process.
- G4 performance and the unresolved Rev6 test debt are not touched and not accepted.

## 7. Compatibility evidence

`test_ledger_opens_on_the_committed_v4_baseline_without_touching_it` (copy of `fixtures/v4_baseline.sqlite3`: every pre-existing object and
row byte-identical, `user_version` unchanged, only `cplus_we_*` and the inherited `cplus_e_*` base tables added) and
`test_additive_only_and_historical_rows_untouched` (E3 HOLD row, M04 table and an unrelated table preserved across two initialisations). Existing
E3 G2/G3/M04 suites pass unmodified.

## 8. Verification entry points

`scripts\test-we-noop.ps1` (G1-G4, fail fast; `-Full` adds G5) and `scripts\run-offline-verification.ps1` (G5: Java build + every
self-test, distribution separation, backend discovery, 30x HTTP security).

## 9. Owner Gameplay Acceptance plan (GUI -> Backend -> Ledger -> No-op -> ACK)

Preconditions: backend running with the local secret; world with one live NPC (e.g. `Reika`) created in the Manager; no C2/Follow active.
Install the allow-list with your player UUID. Note the world's `data/npcortex/transport` folder content before starting.

| # | Step | Expected |
|---|---|---|
| A1 | Without the allow-list file: ESC > NPCortex Manager > **No-op** on Reika | status line `[DENIED] ... allow-list file missing`; backend log shows no `/we/` request |
| A2 | Install the allow-list; click **No-op** | `[OK] No-op complete: NO_EFFECT_VERIFIED, durable ACK received, actor fence released`; inventory, blocks and drops unchanged |
| A3 | Click **No-op** again | a NEW task id, `[OK]` again |
| A4 | During a running C2 goal or Follow, click **No-op** | `[REJECTED] NPC busy: ...`; no new `cplus_we_runs` row |
| A5 | After A2, run the existing E3 move (`taskmove`) | still works (fence released) |
| A6 | Stop the backend, click **No-op** | `[REJECTED]`/`[UNKNOWN]`, never `[OK]`; start the backend, click **No-op** again => `[OK]` |
| A7 | Edit the allow-list to another UUID, click **No-op** | `[DENIED] requester is not on the allow-list` |
| A8 | Remove the allow-list (`-Remove`), click **No-op** | `[DENIED]` |
| A9 | Save & Quit while a click is in flight is hard to time: instead quit normally after A2, relaunch, click **No-op** | `[OK]` (session changed, old terminal untouched) |
| A10 | From chat run `/execute as @s run npcortex m04 status` and any data-pack function | no world action can be started from there (there is no such command) |

Evidence to return: the Manager status line text for each step, the server log lines containing `NPCortex world action`/`C+ outbox`, and the output of
`python -m npcortex_backend.dbtools` or a read-only SQLite query: `SELECT state, count(*) FROM cplus_we_runs GROUP BY state;`. Nothing in this
plan mutates the world; any block/inventory change observed is a FAIL to report, not to repair.
