# C0 Test Report — 2026-10-15

Baseline: B3-B HTTP Lifecycle Hardening ZIP. Scope: Backend C0 only.

## Automated test results

Strict Python 3.13 regression (`-W error::ResourceWarning`):

| Round | Tests | Result |
|---|---:|---|
| 1 | 172 | PASS |
| 2 | 172 | PASS |
| 3 | 172 | PASS |

The original **116 B3-B tests** remain; **56 C0 test executions** now exercise
agent contracts, HTTP, optimistic/concurrent state, SQLite rollback, verified vs
unverified actions, 3-failure model budget, idempotency/restart, and v3→v4
migration. Several C0HTTPTests intentionally inherit behavioral tests and
execute them again with an HTTP server alive; totals count executed tests, not
necessarily distinct named scenarios.

Separate evidence:
- Started actual `python -m npcortex_backend`, fetched HTTP `/health`, observed
  SQLite schema v4 on startup: PASS.
- Constructed v3 database with original pre-C0 B3-B source containing B1
  transcript, B2 dialogue turn and B3 Social Run; opened with C0 `WorldStore`:
  migration v4 and `PRAGMA integrity_check=ok`, legacy row counts unchanged:
  PASS (manual cross-version smoke test).
- Byte compared all **three** shipped Forge Java source files with B3-B ZIP:
  identical.
- Python source/test syntax compile: PASS.

## Unconfirmed quality gates

- Gradle Forge Build: **NOT VERIFIED**. Wrapper attempted to download Gradle
  8.8 but `UnknownHostException: services.gradle.org` stopped it before Java
  compilation. Local JDK here is 21, not recommended Forge JDK 17.
- Windows Python 3.13 strict regression: user verification **PENDING**.
- Real Ollama/Qwen structured C0 decision: **PENDING** user verification.
- Real Minecraft C1 sensor/executor: **NOT IMPLEMENTED** and not a C0 gate.
- Minecraft A2/Forge command compatibility on new package: user verification
  **PENDING** (Forge Java code itself is unchanged).

## Safety/architecture limitations

- Backend cannot attest physical truth. "Verified" means **new reported
  snapshot matches** a typed postcondition. Trusted Forge game-state reporting
  is required at C1, plus authenticated transport before untrusted remote use.
- Headless actions are **requests only**; no real blocks moved/changed.
- No background tick or autonomous game event scheduler.
- UUIDs and outcomes are stored in SQLite; make an offline backup before schema
  upgrade. The newer schema is not backwards-readable by B3-B.
- No GitHub operations performed. No Test, No Push.
