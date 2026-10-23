# C-F1 D+ Recovery Hardening H1-H5 — Test Report

**Candidate date:** 2026-10-22. **Scope:** Diagnostic-only; no world effects. **Source baseline:** Automatic Local Auth Full Repository Candidate.

## Confirmed on this Linux environment

- Java `javac --release 17`: core C+ contracts and H1-H5 self-tests compile and pass using installed JDK 21 targeting Java 17 language/runtime APIs.
- New `CPlusF1RecoveryHardeningSelfTest`: **18 Java checks PASS** (orphan `.tmp`/malformed cursor fail-closed, backend mismatch, world identity, remote ahead/behind, uncertain action, blocked HTTP worker vs journal worker).
- Existing Java: Async Diagnostics 16, Transport 12, D+ Synthetic 35, F0 Contracts 71 checks PASS.
- Backend new crash/fault tests: **8/8 PASS** using Python 3.13.5, including actual child-process `os._exit(73)` after SQLite commit/before ACK and before commit, restarted idempotent projection, no-ACK IO error, world isolation, 1/3/5/10 synthetic actors.
- Backend focused: Local Auth 7/7, F1 Bridge 9/9, D+ 15/15, F0 Contracts 15/15, C2 16/16, Persistence 6/6 PASS with ResourceWarning promoted to error.
- C2 Follow/Navigation: **22/22 pure-Java suites PASS** from the existing Airborne Follow Regression.
- Full Backend Suite: **not verified** — attempted `python -m unittest discover`, did not complete in bounded runtime; not marked PASS/FAIL.
- Forge Gradle Build: **blocked** — `services.gradle.org` DNS/unavailable Gradle 8.8 distribution; not a Java/Forge compilation PASS.

## Acceptance still pending on Windows (not passed by this report)

1. Windows Python 3.13 / Temurin JDK17 command runner, Forge `build`, genuine client launch.
2. In-game `inspect`, `probe`, `sync`, `syncstatus`, `audit` with Automatic Local Auth; Save/Quit/Rejoin and Backend offline/restart, cursor/receipt consistency, no world changes.
3. Backend SQLite concurrent access, power loss/process-kill during Journal append, cursor atomic replace and hard disk I/O failure on Windows (use disposable copy only).
4. Genuine Forge 1/3/5/10 NPC MSPT measurements, including diagnostic HTTP stalls, and dedicated server/modded interaction coverage.
5. Actual Minecraft world-effect reconciliation, pickup provenance, recovery after world modification—explicitly beyond C-F1 scope.

**Acceptance policy:** H1-H5 Candidate may proceed to Windows Acceptance. Do not close C-F1 full Quality Gate until required Windows evidence is supplied; do not expand into C-F2 automatically without approval.
