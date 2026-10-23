# NPCortex C-F1 D+ Fix #1 — Windows-Safe SQLite Test Hardening

## Baseline / approved scope

Baseline: `NPCortex_CPlus_CF1_DPlus_AsyncDiagnosticBridge_FullRepository_CANDIDATE.zip` (380 entries).
The user reported Windows Python 3.13 `WinError 32` while deleting temporary `npcortex.sqlite3` files in `backend/tests/test_cplus_f1_dplus.py`, with unclosed sqlite3 connections (`ResourceWarning`). Eight of thirteen Backend D+ tests encountered **tearDown errors**; this is not evidence that the D+ projection assertions themselves failed.

## Root cause — verified against Source

Three test-only contexts in `ProjectionTests` (fixture creation and two data assertions) used `with sqlite3.connect(self.db)`, whose context manager commits/rolls back but **does not call `close()`**. On Windows, open SQLite handles block `TemporaryDirectory.cleanup()` and produce `PermissionError: [WinError 32]`. The existing `backend/tests/sqlite_test_utils.py::sqlite_test_connection()` uses `contextlib.closing()` plus transactional `with db` and supplies the required explicit close. The production `CPlusF1Projection.connection()` already closes the SQLite connection in `finally`; it is not changed by this fix.

## Exact implementation (Fix #1, scoped)

- In `backend/tests/test_cplus_f1_dplus.py`, replace the **three** raw SQLite `with ... connect` contexts with the existing `sqlite_test_connection` helper; keep fixture schema assertions, event logic and other tests unchanged.
- Add **two regression tests**: (1) explicit close + persisted commit before temporary-directory deletion, (2) explicit close + rollback on an exception before deletion. Both assert using the old connection raises `sqlite3.ProgrammingError` after context exit.
- Add optional focused Windows PowerShell runner `scripts/test-cplus-f1-sqlite-windows-fix1.ps1`; leave the existing full C-F1 script unchanged.
- Documentation only: this report, Windows checklist, `CHANGELOG.md` (single cumulative file), `PENDING_TESTS.txt`.

No changes to production Python/SQLite schema, C+ Java classes, Forge/commands, C1/C2 physics/navigation/Follow, C1 inventory, Journal, World Mutation or Github.

## Automated verification conducted here

| Suite | Result | Environment |
|---|---|---|
| C-F1 D+ Backend tests incl. two new tests | **15/15 PASS** | Linux, CPython 3.13.5, `-W error::ResourceWarning` |
| C-F0 Backend Contracts | **15/15 PASS** | same |
| C2 Backend focused | **16/16 PASS** | same |
| Persistence lifecycle | **6/6 PASS** | same |
| C-F1 D+ async bridge pure Java | **14 checks PASS** | OpenJDK 21 with `javac --release 17` |
| C-F1 D+ pure Java synthetic | **35 checks PASS** | same |
| C-F0 pure Java Contract | **71 checks PASS** | same |
| Follow / Navigation pure Java | **22/22 suites PASS** | same Java target, unchanged implementation |

The C-F1 Backend test originally had 13 tests; two additional tests bring it to 15. On Linux, `-W error::ResourceWarning` and explicit closed-connection assertions provide regression evidence. **This is not proof that Windows Python 3.13 passed**; run the provided script on Windows. No Forge build was performed for this fix because it only changes test code and docs. No live gameplay or real-world crash recovery is claimed.

## Windows Quality Gate

1. On Windows with Python 3.13, run `scripts/test-cplus-f1-sqlite-windows-fix1.ps1` (15+15+16+6 focused Python tests). Confirm no `ResourceWarning`, `PermissionError`, locked temporary SQLite files or error in `tearDown`.
2. Run `scripts/test-cplus-f1-live-diagnostics.ps1` to validate the existing Java + Backend C-F1 suite, and `scripts/dev-forge.ps1 -Task build` on Temurin 17.
3. Optional gameplay regression: confirm `/npcortex debug cplus inspect|probe yui`, C1 spawn/inventory and C2 Follow/Unfollow continue to work. No new in-game commands in Fix #1.
4. Confirm any unrelated full-backend-suite failures are reported separately; never infer real world-effect crash recovery from a passing synthetic fixture.

**Status:** Test-only Fix #1 candidate; Windows Python 3.13 and Forge/gameplay confirmation pending.
