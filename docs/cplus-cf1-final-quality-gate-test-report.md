# NPCortex GWI C+ C-F1 D+ — Final Quality Gate Cleanup — Test Report

**Date:** 2026-10-22. **Baseline:** `NPCortex_GWI_CPlus_CF1_DPlus_RecoveryHardening_H1-H5_FullRepository_CANDIDATE.zip`.

## Scope / change control

- **No production runtime, Forge gameplay, C1/C2, Inventory, Navigation, database schema, world state or credential protocol edits.**
- Fix `scripts/test-cplus-f1-auto-local-auth.ps1`: replace Windows PowerShell → `python -c` nested-quote invocation with a standalone, argument-safe Python bootstrap self-test file.
- Add Python regression tests for bootstrap success/no credential leak and corrupt credential fail-closed, plus a static check preventing the quote bug from returning.
- Add Java cursor fault matrix in the *existing test class*: truncated/missing-LF, wrong hash, cursor beyond journal, numeric overflow, uppercase, oversized, orphan `.tmp` while a valid cursor exists. Tests preserve evidence and act on temporary files only.
- Add unified final gate PowerShell runner and a real Forge MSPT/manual Windows evidence checklist. No GitHub operations.

## Tool-verified automated tests (this candidate)

| Gate | Result | Environment / caveat |
|---|---|---|
| Java C-F1 Hardening | **36/36 checks PASS** | OpenJDK 21, `javac --release 17`, synthetic tempfile only |
| Java Transport | **12 checks PASS** | Isolated |
| Java Async Diagnostics | **16 checks PASS** | Isolated |
| Java D+ Synthetic | **35 checks PASS** | Isolated |
| Java C-F0 | **71 checks PASS** | Isolated |
| Java Local Authentication | **9 checks PASS** | Cross-language Java reads Python-created temporary secret |
| Follow / Navigation | **22/22 Java suites PASS** | Pure Java regression, not Forge gameplay |
| Python bootstrap runner regression | **3/3 PASS** | CPython 3.13.5 on Linux; test helper runs as standalone script |
| Backend full Python suite | **258/258 PASS**, 47.123 s; process exit 0 | CPython 3.13.5 on Linux / `ResourceWarning` promoted to error |

### Prior Windows gameplay evidence provided by operator (unchanged production runtime)

- **Test A PASS:** `journal=9`, after sync `cursor=backend=9`, `pending=0`, audit `READY`.
- **Test B PASS:** Backend offline yields `ConnectException` and `HOLD`, no automatic replay; after restart `journal=cursor=backend=11`, audit `READY`.
- **Test C PASS:** Save/Rejoin moves actor epoch `3 → 4`; new synthetic `CANCELLED` event reaches sequence 14 with `ACKed=14`, audit `READY`.
- **Test D PASS:** C1/C2 Follow, Navigation and Inventory regression operator-confirmed.
- Windows Forge `build` on original H1–H5 baseline operator-confirmed PASS. Test-only changes in this cleanup do not substitute for an exact-ZIP rerun.

### NOT verified or not part of C-F1

1. **Windows execution of repaired Auto Local Auth `.ps1`** (PowerShell process quoting differs from Linux; the fix requires actual Windows acceptance).
2. **Copied-world cursor corruption / disk fault injection on Windows**, especially that `.tmp` remains and `audit/sync` HOLD fail-closed.
3. **Real 1/3/5/10 NPC Forge MSPT**, measured during idle and manual diagnostic sync; synthetic throughput is explicitly not a tick-time measurement.
4. **Sudden Minecraft process kill / physical power-loss** through journal fsync and cursor atomic replace on Windows. Run only against backups/disposable worlds and databases, if authorized. No power-loss test was performed in this environment.
5. **Actual World Mutation / irreversible world effects**: expressly excluded from C-F1 implementation; will need C-F2 observation and later GWI skills/reconciliation. Do not count synthetic verification as a world-effect pass.

### Acceptance rule

The previously observed gameplay A–D is accepted **for those scenarios**, and this Candidate has updated automated coverage. Do **not** mark C-F1 full Quality Gate complete until Windows runner + deliberate copied-world fault + actual MSPT evidence is gathered and reviewed. No C-F2 source code changes are authorized by this Candidate.
