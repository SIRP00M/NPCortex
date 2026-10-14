# NPCortex B2-C — Test Report

**Implementation Candidate** — targeted Windows SQLite/HTTP lifecycle fix.
Source baseline: `NPCortex_v0.1.1-alpha_B2_MultiActorDialogue_FullRepository.zip`.
No GitHub operations performed.

## Audited user evidence

User's Windows Python 3.13 test run: `Ran 64 tests` with `14 errors`.
- **13 errors** during `TemporaryDirectory.cleanup()` with `PermissionError:
  [WinError 32]` on `.sqlite3` files. The tests contained numerous raw
  `with sqlite3.connect(path) as db:` contexts. Python's sqlite3 connection
  context manager handles transaction outcome **but does not call close()**.
  `ResourceWarning: unclosed database` in the same log reinforces this diagnosis.
- **1 error** in legacy `test_validation_errors_keep_error_field_and_add_code`
  for POST `/nope`: `ConnectionAbortedError: [WinError 10053]`. The original
  handler returned 404 before consuming the sent request body; on Windows this
  may lead to a reset. The exact network-side cause is not independently
  reproduced in this Linux environment.
- User separately ran `scripts/test-b2-dialogues.ps1`: ten checks passed, and
  old dialogue persisted as four turns after their backend-restart test.

## Changes and verifications in this environment

- Explicit `SQLiteDatabase` Unit of Work for read-only snapshots, serialized
  writes, rollback, maintenance migration and guaranteed connection closure.
- B1 and B2 persistence operations moved to the same manager, without modifying
  schema, HTTP request/response shape, or Forge source.
- Test-only SQLite contexts now close (and commit/rollback) explicitly.
- Unknown HTTP POST drains a bounded body before returning pre-existing JSON 404.
- Six lifecycle regression tests, including counting the number of explicit
  sqlite3 `close()` calls and repeating 404 POST 25 times.
- Python automated suite **70/70 PASS**, including all prior **64** tests,
  on **three consecutive runs** with `python -W error::ResourceWarning`.
- Cross-version compatibility was tested using **the original B2 code** to
  create a real SQLite v2 file with B1 transcript and B2 dialogue; this
  unchanged database was reopened with B2-C, preserving all old records and
  schema version 2 (`PRAGMA integrity_check = ok`).
- HTTP localhost health and threaded server shutdown were exercised directly.
- Python `compileall` passed.
- Forge Build **BLOCKED**: attempted offline Gradle Wrapper but it tried to
  download Gradle 8.8 and failed with
  `java.net.UnknownHostException: services.gradle.org` before build began.
  Environment Java is 21, not JDK 17; **no successful Forge build claimed**.
- Native Windows Python 3.13, real Qwen 3 8B, Windows PowerShell and Minecraft
  gameplay **not rerun in this environment**. Needs user validation.

## Scope and risk

- SQLite `PRAGMA user_version` remains **2** (B1 v1 migration path preserved),
  no schema rewrite, no deleted data, and no new persistence format.
- One connection per operation; no connection pool or cross-process AI/runtime
  scheduling. SQLite still permits only one writer at a time.
- `WorldStore.get_conversation()` uses one snapshot for metadata + messages.
- Read `query_only` is enabled per read connection; transaction rollback and
  close are verified in regression tests.
- No B3 or autonomous world features.
- **Quality Gate pending** Windows 70 tests, genuine Qwen, backend restart,
  Forge build and Minecraft acceptance.
