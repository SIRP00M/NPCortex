# NPCortex C-F1 D+ — Automatic Local Authentication: Candidate Test Report

## Scope
- Backend-generated private per-user secret + Forge reader (same user/profile).
- Legacy manually supplied `NPCORTEX_CPLUS_F1_TOKEN` remains supported and takes precedence.
- No C1/C2, inventory, world mutation, backend SQLite schema, action journal, scheduling or legacy command behavior changes.

## Automated test evidence
Test runner: `scripts/test-cplus-f1-auto-local-auth.ps1` (Windows), plus Python `test_cplus_local_auth.py` and standalone Java `CPlusLocalAuthSelfTest.java`.
- Private secret create/reopen, unchanged across restart, not printed.
- Explicit override, invalid file, relative override, missing file, POSIX insecure file.
- Python HTTP loopback authorization (missing, bad, correct, duplicate).
- Java–Python secret file parity, invalid/truncated/oversized file handling.
- Existing C-F1 Bridge, SQLite and C0/C1/C2 regressions should be retested on Windows.

## Actual local results (Linux, CPython 3.13.5, OpenJDK 21 `--release 17`)

- Auto-auth Python: **7/7 PASS** with ResourceWarning elevated to error.
- Existing Bridge 9/9, D+ SQLite 15/15, F0 Backend 15/15, Persistence 6/6, C2 Backend 16/16: **61/61 PASS**; combined Backend focused **68/68**.
- Java Auto-Auth: **7 checks PASS**, plus Python-generated secret read from Java with **9 checks PASS**.
- Java C-F0/C-F1: **71 + 35 + 16 + 12 = 134** existing checks PASS (new 7 => 141 checks).
- Java Navigation/Follow: **22/22 suites PASS** (pure JVM; NOT Forge).
- Real Python backend launch: HTTP `/health` 200, private file created, secret not printed in log, second startup preserved same credential.
- ZIP Integrity: verified; base file count preserved.
- ForgeGradle `compileJava`: **BLOCKED** by unavailable Gradle 8.8 distribution download; Forge compilation NOT certified.
- Full Backend test suite: **did not complete within allotted runtime; NOT verified** (focused tests above PASS).

## Boundaries / pending
- No guarantee against hostile processes with access to the same OS user account or stolen secret.
- The secret protects only C-F1 diagnostic endpoints; legacy backend routes are NOT authenticated.
- Production Windows Forge build, Minecraft, Windows ACL/profile isolation, manual environment overrides, multiplayer/server hosts, true crash recovery and MSPT pending until reported separately.
- The automatic secret is bootstrapped by `python -m npcortex_backend` startup; tests using `make_http_server()` directly must call `initialize_secret()` if they want automatic credentials.
