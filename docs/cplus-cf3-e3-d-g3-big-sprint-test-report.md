## 2026-10-25 update — Windows SQLite temporary database cleanup (Fix #1)

**User-reported result on original Big Sprint Candidate:** Forge Build PASS. Python `test-cplus-e3-g3-big.ps1` failed in `test_cplus_e3_g3_lifecycle.py`, 2 errors, both `PermissionError: [WinError 32]` attempting to delete temporary `world.db` / `old.db` after the tests. This is a test-fixture database-handle problem, not evidence that the Forge build failed.

**Root cause from source:** the three raw `with sqlite3.connect(...) as db:` usages in the lifecycle test used the SQLite transaction context manager, which **does not close** the DB handle when exiting. Windows disallows removal of an open SQLite database file.

**Exact fix:** all three test-only connections are now scoped by `contextlib.closing` with a nested SQLite transaction context so they explicitly commit/rollback and **then close** before the directory is cleaned. Added temporary database file rename probes after closing to detect an open handle on Windows. No backend runtime or Forge source modified. The production component of this Candidate is identical to the previous Build-PASS candidate; the fix only updates this test file and documentation.

**Evidence for this revised candidate:** Python 3.13.5 Linux runtime G3 lifecycle 9/9 PASS, five G3/G2 runner test groups 45/45 PASS, broader 13 E-family Python test files 99/99 PASS; ZIP integrity and baseline-file comparison to be verified during packaging. **Windows test rerun is PENDING** and is the only definitive verification of `WinError 32` resolution. Java/Gradle contract tests and in-game G3 acceptance also remain PENDING unless separately confirmed.

**Operational note:** the SQLite files shown in the error are disposable test-fixture paths under `%LOCALAPPDATA%\Temp`, not the backend world DB. Do not delete the real NPCortex SQLite database or reset any historical G2/G3 attempt. This fix does not auto-release actor fences or reinterpret HOLD attempts.

# NPCortex GWI First World Action — E3-D G3 Combined Sprint (candidate)

## Scope and baseline

- Baseline: `NPCortex_GWI_FirstWorldAction_G3-1_TerminalContracts_FullRepository_CANDIDATE.zip`, extracted clean.
- G3-2 Verified completion, SQLite durable terminal receipts, actor fence release only after terminal proof.
- G3-3 Goal-scoped cancellation, recorded cancel intent, C0 cancellation ACK reconciliation and restart HOLD.
- G3-4 regression runner and Windows Gameplay acceptance checklist.
- **No E3-E automatic Movement DAG**, no E4/E5 permissions or world effects, no Qwen dispatch, no GitHub operations.

## Actual source changes

- `C2AgentRuntime.java`: process-local C0/C1/C2 verified witness created only after C2 succeeded, C1 receipt COMPLETE and actual destination check; goal-scoped cancellation refuses foreign/Follow goals. Witness cleared on server shutdown.
- `CPlusE3G3Runtime.java`: server-thread authoritative live checks + bounded async authenticated HTTP status/finalize/cancel-begin/cancel-ack; Java G3-1 terminal shape validator reused. New operator commands `taskmovefinalize`, `taskmovecancel`, `taskmovereconcile`. Never auto-replay.
- `CPlusF1DiagnosticRuntime.java`: register operator-only commands.
- `C1VanillaLifecycleCoordinator.java`: shutdown of new G3 bounded async worker.
- `CPlusE3G2Runtime.java`: status can display verified COMPLETED after G3; no movement change.
- `cplus_e3_g2_motion.py`: preserves G2 records while migrating SQLite constraints to include CANCEL_PENDING/COMPLETED/CANCELLED; durable terminal/cancel intent tables, atomic receipt+state transition and idempotency; old unresolved rows remain HOLD on restart.
- `server.py`: adds three authenticated loopback-only G3 endpoints; existing token guard preserved.
- Focused Python tests for finalization, identity mismatch, wrong C0/C1/C2 evidence, terminal duplicates, cancel-ACK mismatch, restart HOLD, old schema migration, HTTP auth and concurrency.

## Test evidence / limitations

- PASS: G3 lifecycle Python tests 9/9.
- PASS: E-related backend tests 99/99 from `test_cplus_e*.py` discovery in this isolated extraction.
- PASS: Previous G3-1 contract tests 8/8 included in the 99 tests.
- PENDING: Full backend suite; last full-suite attempt exceeded time budget (not counted PASS).
- PENDING: Windows Temurin 17 Forge build and Gradle Java/Gson tests; local isolated javac with missing Forge dependencies does not constitute compile success.
- PENDING: real Minecraft completion twice consecutively; current-process witness may be absent if C1 terminal phase changes, C2 history was overwritten or player moves out of tolerance — fail closed is intended.
- PENDING: cancel mid-movement and after goal/action creation, C0 terminal cancel acknowledgment, Backend offline→online, Save/Rejoin, fault injection.
- PENDING: actual multiplayer requester checks, 1/3/5/10 NPC MSPT, C1/C2/Inventory/Follow/C-F1/C-F2 regressions.

## Critical safety conditions

1. **Old G2/G3-1 HOLD records do not become COMPLETED automatically.** Old C2 journal text is not sufficient. Do not delete/edit SQLite to sidestep fencing.
2. C2 `COMPLETED` alone is insufficient; Forge process-local witness must originate from C2's live verified result and match C+ admission identities. G3 finalization requires current avatar near the exact admitted target, C1 idle, C2 terminal journal, authenticated original requester and durable SQLite ACK.
3. Cancel command first durably writes CANCEL_PENDING, then cancels **only** the exact active C2 goal. Terminal `CANCELLED` release requires C2/C0 reconciliation and C1 stopped/grounded. Do not conflate cancellation with rollback.
4. If Backend restarts mid-motion/cancel, unresolved rows become HOLD and are not auto-replayed or finalized. Dedicated recovery for older uncertain attempts remains a future scoped gate.
5. Forge build or Gameplay failure holds acceptance; do not proceed to E3-E/E4/E5 until verified. UNKNOWN world permissions remain UNKNOWN.

## Architecture tradeoff

The G3 combined candidate uses explicit operator commands rather than automatic terminal polling. This keeps new functionality behind an opt-in gate and avoids creating a second C2 controller. E3-E automatic DAG may be considered only after stable G3 completion/cancellation, recovery and FIFO acceptance.
