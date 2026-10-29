# PAP-G1b real persistence validation (2026-10-28, test-only)

Runner: `scripts\pap\run-pap-g1b.ps1` (Forge GameTest server, throw-away world `forge-mod\run-gametest\world`, wiped before/after; `-SkipKill` for measurements only), `scripts\pap\pap_g1b_backend_crash.py` (isolated temp SQLite). New test classes: `PapG1bPersistenceGameTests` (namespace `npcortexpap`), `PapG1bKillGameTests` (`npcortexpapkill`), plus a copy of the `arena` template per namespace in `src/test/resources`. Not part of the default run or of `build`'s self-tests. No production file, live world (`saves\`, `run\`) or live DB touched.

Scope of "real": the PRODUCTION `C1InventoryData` / `C1InventoryJournal` (512 cap) saved and loaded by Minecraft's real `DimensionDataStorage` / `SavedData.save` (DataVersion wrapper, compressed NBT file) inside a real headless Forge `GameTestServer`. GameTests run on the server thread, so the timings are server-thread stalls. Still NOT TESTED: a dedicated server process, a real client, a world with realistic chunk load (the test world is nearly empty, so level.save baseline is a floor), disk contention, an NPC population actually generating 512 receipts through gameplay.

## A. Real SavedData save/load, 512 receipts per NPC (median of 9 timed iterations after 3 warm-up; max in brackets)
| NPCs | file bytes (gz) | `DimensionDataStorage.save` | fresh-storage load (restart read) |
|---|---|---|---|
| 1 | 25,548 | 3.1 ms (3.9) | 4.7 ms (5.0) |
| 3 | 76,398 | 4.7 ms (7.1) | 5.3 ms (7.3) |
| 5 | 127,129 | 7.3 ms (7.7) | 6.1 ms (10.3) |
| 10 | 253,975 | 13.8 ms (14.9) | 8.6 ms (11.9) |

Every actor's 512 receipts and revision survived save + load (asserted). File bytes match the earlier serialization estimate (254 KB at 10x512), and the save time (13.8 ms) matches the earlier NBT-write estimate (14.1 ms), so the earlier serialization numbers were a fair proxy at 512.

## B. Server-thread stall: real `ServerLevel.save` (SavedData + chunks + entities) on the server's own storage
| State | median | max | vs 50 ms tick |
|---|---|---|---|
| baseline, C1 data not dirty (nearly empty world) | 1.3 ms | 9.6 ms | |
| C1 dirty, 1 NPC x 512 | 2.5 ms | 3.7 ms | 5% |
| 3 NPCs | 5.4 ms | 6.5 ms | 11% |
| 5 NPCs | 8.1 ms | 9.6 ms | 16% |
| 10 NPCs | 14.6 ms | 15.5 ms | 29% |

The save is synchronous on the server thread (the stall is the call time). This is a save-time stall, not a per-tick MSPT cost; MSPT between saves is unaffected by the journal (lookup is a map hit; `save` builds the tag only when saving). Vanilla autosaves roughly every 5 minutes (6000 ticks) and on stop, and C1 is rewritten whole whenever anything in it changed.

## C. Crash windows (real process kill, disposable instances only)
- **X2, journal vs disk (real storage, in-JVM):** a receipt saved by `DimensionDataStorage.save` is on disk; one committed in memory is absent from a fresh-storage read; it appears after the next save. PASS.
- **X2, real JVM kill (`Runtime.halt(137)` in the GameTest JVM, second JVM on the same world):** A (saved) present, B (saved after a block change) present, C (memory-only) absent. The block change made before B's save was **lost** (chunk not saved). PASS (as an observation). Meaning: a receipt can be durable while its world effect is not (receipt without effect; observed). The opposite divergence (effect durable, receipt not) follows from the same independent save paths but was not observed here. Either way X2 degrades to HOLD and must never be read as "exactly once".
- **X0, real backend process kills (`pap_g1b_backend_crash.py`):** a process killed after RESERVE only: its row is FAILED_NO_EFFECT and the claim RELEASED at the next backend start; killed after the durable START: EXECUTING became HOLD_IN_DOUBT and the claim HELD; nothing replayed, nothing deleted. PASS. (The next process start sweeps any earlier unfinished attempts, as documented.)
- Not tested: kill in the middle of `ServerLevel.save` (SavedData is written before chunks per `NATIVE_BREAK_PICKUP.md` s4; the order was not exercised by a mid-save kill), torn writes of the `.dat`, power loss, and any claim of exactly-once world mutation (none is made).

## D. Larger caps (SIMULATED, not measured through the real server)
The real 512 numbers fit a line: about 2.3 us per receipt of save time plus about 2 ms fixed, and about 50 B gzip per receipt. Linear projection (SIMULATED, consistent with the earlier NBT-only series): 10 NPCs full is about 49 ms at 2048 (one tick budget) and about 97 ms at 4096. 1 NPC full is about 7 ms at 2048 and about 11 ms at 4096. Whether a real 2048/4096 stays linear (GC, larger NBT trees, disk) was not measured; a real measurement needs a test-instrumented journal build, which I have not made.

## E. Recommendation (evidence-based input; nothing selected or implemented)
- **Cap.** At 512 the measured stall is acceptable up to 10 NPCs (about 15 ms), so cost is not what forces a change; lifetime is (about 256-512 inventory-changing native actions per NPC, never pruned). A cap of **2048** is the leading candidate for up to about 5 full NPCs (projected about 25 ms) and is borderline at 10 (projected about one full tick). **4096** projects about 100 ms at 10 NPCs and is not recommended unless persistence is split per actor or made incremental. Any fixed cap only postpones exhaustion, so exact-replay-safe retention (archive plus an index of replayable ids) remains required, as a separate approval.
- **Reserve.** There is an evidence-based reason for some reserve, but not for 64 specifically: the persist-only repair of an INVENTORY_COMMIT_FAILED HOLD and operator commands (`copyhand`) both need a free journal slot (`repair` commits a receipt and throws at FULL, `journalPrecheck` only guards new attempts). A reserve held back from autonomous dispatch keeps repair possible. One active attempt per actor means a small reserve covers repairs; 64 costs about 3 KB per NPC, so it is cheap but unmeasured policy. Owner chooses the number.
- Do not change production until the above and the retention design are approved.

## F. Status
PASS (executed): real SavedData save/load at 512 (1/3/5/10 NPCs), real `ServerLevel.save` stall at 512, X2 in-JVM and real-kill observations, X0 backend real-kill. PENDING/NOT TESTED: real 2048/4096 (SIMULATED only), dedicated server, realistic-world chunk-save cost, mid-save kill, live gameplay. BLOCKED: nothing. Regression: `compileTestJava` OK; backend discovery 742 tests, all pass when `PYTHONPATH` is absolute as `run-offline-verification.ps1` sets it (a relative `PYTHONPATH` fails one pre-existing subprocess test); the full `gradlew build` and `run-offline-verification.ps1` were not rerun.

## G. Owner decisions
1. Journal cap (512 stays / 2048 / 4096 / per-actor persistence first) and reserve size. 2. Whether retention/archive with exact replay gets its own sprint. 3. Whether to authorize a test-instrumented real 2048/4096 measurement. 4. Sign PAP-G0 invariants I1-I8 (below).

## H. PAP-G0 invariants I1-I8 for sign-off (verbatim from `PAP_G0_CONTRACT.md` s1)
I1 Durable START ACK before any world effect; restart sweep: RESERVED (start never durable) -> FAILED_NO_EFFECT with claims released; EXECUTING -> HOLD_IN_DOUBT with claims HELD (`cplus_wn_store._sweep`; X0 observed above). I2 Exact replay: same id + same fingerprint = REPLAY, same id + other fingerprint = CONFLICT, answered even when the journal is FULL. I3 HOLD_IN_DOUBT never becomes NO_EFFECT/COMPLETED by itself; only operator Reconcile from observed state; HELD claims never auto-release. I4 No eviction or auto-prune of receipts; FULL fails closed ("migration required") and is prechecked before dispatch. I5 Default-deny autonomous mutation; the allow-list is Owner config. I6 Movement keeps the C2 durable goal / cancel / terminal witness / recovery; the WN START protocol is not imposed on movement. I7 Approach before native reserve: no block/item claim is held during an extended walk (PAP-G3 requirement, not yet code). I8 No SQLite/outbox/journal/HOLD record is reset, re-identified or replayed; no Tier 1 durability relaxation (WAL + `synchronous=FULL` on the E3/M04 stores, `BEGIN IMMEDIATE`).
