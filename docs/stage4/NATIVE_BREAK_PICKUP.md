# Stage 4 Option D / Architecture B+ - integrated native break + pickup (CANDIDATE)

Owner-approved 2026-10-28. Offline gates PASS; **live GUI and gameplay are PENDING** (Owner evidence required).
Wire/receipt summary: `docs/contracts/native_effect_receipt_v1.json`; wire fixture: `backend/tests/fixtures/native_effect_wire_v1.json`
(written by the Java flow self-test, replayed byte-for-byte by Python).

## 1. What it is

A reusable native-interaction foundation: the Manager GUI asks an NPC to break one block (`Brk`) or pick up one dropped item (`Pck`).
Forge owns and executes the effect; the Python backend only records, coordinates and refuses. No Qwen/backend dispatch, no job
coordinator, nothing hard-coded to oak.

Flow: Preview (server picks the target, volatile nonce, TTL 60 s) -> Confirm -> RESERVE (durable, claims taken in the same
`BEGIN IMMEDIATE` transaction as the actor fence) -> break: timed mining on the server thread (Minecraft survival rate, volatile
accumulator, resets on tool change) -> at progress >= 1.0 request START -> only after the matching START ACK, ONE server-thread task:
final precondition, native call inside try/catch, observation -> typed receipt -> FINALIZE (or HOLD). Pickup skips mining.

Authority: the packet sender must be an online owner/op allowed by `config/npcortex-native-actions.json` (deny-by-default, strict
parser, 1 s background reload, stale after 4 s = deny-all). Install with `scripts\install-native-allowlist.ps1`.

## 2. Persistence (additive only)

SQLite tables `cplus_wn_bindings`, `cplus_wn_binding_events`, `cplus_wn_runs`, `cplus_wn_terminals`, `cplus_wn_holds`,
`cplus_wn_claims`. SavedData `npcortex_native_binding`. No existing table, row, journal, receipt or HOLD record is changed.
States: RESERVED, EXECUTING, EFFECT_VERIFIED, EFFECT_PARTIAL, FAILED_NO_EFFECT, CANCELLED_NO_EFFECT, HOLD_IN_DOUBT,
RECONCILED_PERSISTED_TRANSFER, RECONCILED_PERSISTED_STATE_UNCHANGED, RECONCILED_EFFECT_OBSERVED. HOLD_IN_DOUBT never turns into
NO_EFFECT/COMPLETED by itself; only an operator Reconcile (judged from the observed persisted state, never claiming "did not happen").

Claims (ACTIVE/HELD/RELEASED): exclusive per block; per item entity UUID plus a conservative same-item proximity rule
(horizontal 2.0, vertical 1.0). Unrelated NPCs are not serialized. Claims coordinate NPCortex actors only. A HELD claim is never
released automatically. Cross-fences with No-op, E3 G2, M04, C1 inventory/motion/lifecycle and C2 navigation/follow work in both
directions (a pending native attempt blocks them and they block it).

World binding: compare-and-advance handshake (world_id, folder key, epoch; the epoch is confirmed by the world save). Fails closed.
`scripts\native-rebind.ps1` is the audited operator override. It is NOT proof of physical identity (first bind is trust-on-first-use;
restoring the last saved state is indistinguishable).

## 3. Evidence rules

- Break: `destroyBlock` return + block state afterwards + drop/collateral observation. A cancelled break event or a refusal is
  NO_EFFECT only when the cell is observed unchanged. An exception after dispatch is HOLD (EXECUTOR_EXCEPTION).
- Pickup: VERIFIED = inventory delta == pickup event == entity reduction and the entity is gone. PARTIAL = delta == entity reduction,
  entity remains (vanilla fires NO pickup event for a partial add, so event 0 is accepted). NO_TRANSFER = all witnesses quiet.
  Anything else = HOLD AMBIGUOUS_EVIDENCE.
- Inventory journal: the attempt id is the C1 transaction id; capacity/NEW/<512 prechecked before dispatch. A commit failure after
  the effect = HOLD INVENTORY_COMMIT_FAILED with the intended receipt; the operator persist-only repair needs REPLAY checked first,
  the original post-effect snapshot, unchanged inventory structure, same incarnation and session. No pickup/break is ever repeated.
- Receipt hashes: Java canonical JSON is byte-identical to Python (`ensure_ascii`, DEL escaped); Forge compares the backend's
  `receipt_hash` and never adopts another actor's terminal.

## 4. Verified vanilla facts (javap + real GameTest)

`destroyBlock` runs the Forge break event, mineBlock, removeBlock and playerDestroy but does NOT check reach/mayInteract (the
executor does). The vanilla server never completes mining by itself (the client sends STOP; server threshold 0.7) - hence the
volatile accumulator. `ItemEntity.playerTouch` has no distance check (the executor checks proximity, delay, thrower/owner). A
partial add fires no pickup event. `ServerLevel.save` writes SavedData before chunks and entities. `isUnderSpawnProtection` is
false on the base server. `popResource` honours doBlockDrops.

## 5. Gate evidence (executed 2026-10-28, Windows, offline)

| Gate | Evidence | Status |
|---|---|---|
| G0 | real Forge `gameTestServer` runs the custom avatar; 7 ADR questions answered by javap/GameTest | PASS |
| G1 | NativeActionPolicySelfTest 211, ManagerProtocol 267, ManagerCore 105; static contracts 53 | PASS |
| G2 | NativeEffectFlowSelfTest 834 (scripted ledger, mutation-checked); backend wn store/http/wire 72; accepted stores + no-op 84 | PASS |
| G3/G4 | `scripts\test-native-gametest.ps1 -Repeat 10`: 28 real GameTests x10, all pass | PASS |
| G5 | race x10; ledger measurement 1/3/5/10 actors; real Python backend over loopback HTTP, 5 GameTests, DB verifier LEDGER OK (4 consecutive runs); `-Down` run (no backend): all 5 tests fail, no effect | PASS (offline) |
| G6 | `run-offline-verification.ps1 -HttpRepeats 10`: Java 72 self-test tasks, dist separation 338 classes, backend 709 tests (1 skipped), http_security 0/10 failing. Baseline: 70 / 299 / 625 | PASS |
| Reviewer pass (policy: CRITICAL) | run in candidate r2, see 6b; source-only, it executed nothing | DONE (findings fixed/documented) |
| Live GUI click, real client, data-pack run in a client | - | PENDING |
| Dedicated server, chunk-save/crash window, MSPT/profiler | - | NOT TESTED (G4 performance NOT accepted) |

Ledger latency (observation, 10 hammering actors): p50 ~5 ms, p95 <= ~150 ms, max ~1.0-1.5 s, 0 errors.

## 6. Findings worth keeping

- A single run of the real-backend 10-NPC test lost 4 STARTs (`START_ACK_LOST`, safe: FAILED_NO_EFFECT, nothing executed) right
  after a recompile; not reproduced in 4 later runs. A `transport slow` WARN (>500 ms queued+round trip) now logs the route if it recurs.
- Reserve is on the normal lane (1 thread, queue 32). A full lane now means "nothing was sent" (REJECTED, retry) instead of an
  ambiguous reserve + cancel.
- Natural terrain above a cleared GameTest arena can drop gravel into the NPC's line of sight; every GameTest fixture now caps the arena. Scheduling `succeedWhen`/`onEachTick` from inside a tick callback can corrupt the framework's delayed-task map (server crash / never-ending test); the data-pack test now uses one top-level `succeedWhen`.
- Constructing a second `NativeEffectStore` on a live DB settles every unfinished attempt (restart sweep). One store per process.
- Journal capacity (512 receipts) is shared with operator commands.
- Post-effect memory-only window (effect applied, crash before the receipt reaches the outbox) degrades to HOLD at the next start.

## 6b. Independent review round (2026-10-28, candidate r2)

GPT-6 review: the binding folder key used only the world folder's basename. Now `NativeWorldKey.folderKey` = readable basename +
16 hex of SHA-256 over the full real path (negative test: same basename, different parents). Still an accident guard, not identity:
a copy at the same path or an unresolved link/case change is not detected. Existing native bindings (test databases only) need
`scripts\native-rebind.ps1` once; the Forge log prints `world binding folder key <key> (path <path>)` at the first bind.

Reviewer pass (Sonnet `reviewer`, source-only, 12 findings; none touched the START gate, claims or hash parity). Fixed + tested:
1. Rec. dead end after a lost cancel ACK or a restart sweep: `/active` = none, or a status hash mismatch, now falls through to
   the observation-based discover, and the stale local fence and completion slot are released (never adopts a foreign hash).
2. Unexpected runtime errors after a good START ACK (outside the native-call try/catch) no longer strand the attempt: before the
   executor = FAILED_NO_EFFECT, after = HOLD UNEXPECTED_STATE; `msgDone` also catches a throwing ACK handler.
5. A never-acknowledged HOLD + INVENTORY_COMMIT_FAILED: Rec. resends the identical HOLD first, repairs after its durable ACK.
6. `reserve` swept before checking the binding: a foreign session could settle the live session's rows; now binding first.
7. `start` now refuses a RESERVED row whose session is no longer the bound one.
8. Critical lane capacity = slots + 8 so slot-less REPAIR/RECONCILE cannot crowd out a START.
9. `_FOLDER.fullmatch`; the key is resolved once per run (no repeated `toRealPath` on the server thread).
Documented, not changed: 4 `policy(true)` is not a fresh file read (no I/O on the server thread). The real revocation window is about
one poll interval (1 s), not 4 s: 4 s is only the poller-dead deny-all cutoff. Open question for the Owner: require a snapshot read
AFTER the START request before dispatch (fails closed, shrinks the window to the read latency); 10 canonical JSON floats (only ints/strings/bools are hashed today); 11 E1 coordinator/E2 dispatcher and CPlusE3G3Runtime have
no pre-start guard against a native attempt (read-only E1; G3 is stopped by the final `ownershipBlock`), so "both directions" holds
for C1, C2, E3 G2, M04 and the No-op ledger only; 12 `collateral` is sampled in the same tick as the break.
Open hypothesis (reviewer finding 3, NOT proven): START_ACK_LOST fires on game ticks (100), the headless GameTest server may tick
without a 50 ms throttle, so 10 simultaneous STARTs through a 2-thread lane can exceed 100 ticks in well under 5 s. The START timeout
messages now report elapsed ticks AND wall ms; compare them if it recurs. No timeout was changed.

## 7. Known limitations

Claims are cooperative among NPCortex actors only (no protection against players or other mods). Binding is not proof of identity.
Outbox files are evidence and are never replayed. A token holder can forge Forge-attested fields. 10-NPC numbers are observations.
The data pack was validated by parsing/executing in the GameTest server, not in a real client. Scripted-backend tests model the
contract's state rules; only the five `npcortexreal` tests use the real ledger.

## 8. Owner acceptance (minimal, PENDING)

1. `scripts\install-native-allowlist.ps1` (reviews and writes `config/npcortex-native-actions.json`), start the backend, `runClient`.
2. Create a NEW world whose name contains "Native"; `scripts\install-native-datapack.ps1`; `/reload`; `/function npcortex_native:setup`.
3. Spawn two NPCs; Manager GUI: `Brk` -> Confirm on a log in front of one NPC. Expect: crack animation, block gone, chat "Break complete: EFFECT_VERIFIED".
4. `Pck` -> Confirm next to the drop. Expect inventory +N and "Pickup complete".
5. Both NPCs `Brk` on the same log: exactly one wins. Run `/function npcortex_native:check/s3_contention`.
6. Quit during mining, relaunch, open the Manager: a pending attempt must show Rec.; use it. Save the log lines and the `verify_native_db.py` output.
Until this evidence is supplied, gameplay stays PENDING.

## 8b. Automated acceptance harness (test-only, replaces the manual log reading)

```
scripts\native-acceptance.ps1 -Action Start      # isolated backend :18765, temp DB + secret; backs up forge-mod\run\config\npcortex-common.toml
scripts\native-acceptance.ps1 -Action Client     # runClient with the isolated credential; create a world with "Native" in its name
scripts\native-acceptance.ps1 -Action Install -WorldName <World>     # data pack into that world (refuses names without "Native")
  in game: /reload   /function npcortex_native:setup   /function npcortex_native:info
scripts\native-acceptance.ps1 -Action Allow      # allow-list from the player UUID + anchor found in latest.log
  in game: /function npcortex_native:guide ; Manager Brk/Pck + Confirm for each scenario ; /function npcortex_native:check/all
scripts\native-acceptance.ps1 -Action Report     # read-only PASS / FAIL / INCONCLUSIVE / NOT_TESTED per scenario, evidence folder kept
scripts\native-acceptance.ps1 -Action Stop       # stops only its own backend (start-time checked), restores your toml; keeps all evidence
```
Scenario ids: A0 denied targets, A1 break (S1), A2 pickup (S5-S7), A3 contention (S3), A3b adjacent logs (S2), A4 interleaving,
A5 Save&Quit/Rejoin/Reconcile, A6 multi-NPC row (S8; tiers 1/3/5/10, functional only, not MSPT), A7 failures/HOLD/claim inspection.
Rules: a ledger row without the world line, or a world line without a ledger row, is never PASS (INCONCLUSIVE). HOLD_IN_DOUBT and HELD
claims are reported as needing an Owner decision, never cleared; only a claim that is ACTIVE/HELD while its attempt is terminal is FAIL.
Overall PASS needs every scenario PASS. The data pack only builds disposable fixtures (blocks, drops, an axe in an NPC hand) and
prints world checks; every Break/Pickup comes from the Manager (authorization, nonce, claims, START ACK all apply).
Limits: the world side is chat text from latest.log (start a fresh game launch per attempt, or the old lines are still there); the
interleaving of E3/G3 motion is evidence-only (no refusal message exists for it); no durable-commit claim is made from a world line.
Tests: `backend/tests/test_native_acceptance_report.py` (25), the GameTest that parses and runs the whole pack (178 lines).

## 9. Commands (Windows PowerShell)

```
cd forge-mod; .\gradlew.bat build                         # compiles, runs all Java self-tests (no GameTests)
scripts\test-native.ps1                                   # G1-G5 offline runner
scripts\test-native-gametest.ps1 -Repeat 3                # real Forge GameTests, scripted ledger
scripts\test-native-real-backend.ps1 [-Down]              # real Python ledger, temp DB, port 18765 (never touches 8765)
scripts\run-offline-verification.ps1 -HttpRepeats 10      # G6
cd forge-mod; .\gradlew.bat runClient                     # client; backend: python -m npcortex_backend from backend\
```
