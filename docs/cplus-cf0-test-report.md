# NPCortex C+ C-F0 — Implementation & Test Report

Date: 2026-10-22. Baseline: `NPCortex_AirborneFollowTargetProjection_Fix_FullRepository_CANDIDATE.zip`. User approved F0-A..F0-G ONLY. No GitHub operations.

## Scope delivered

- F0-A source-to-ownership map and collision matrix (`docs/architecture/ACTION_ENGINE.md`)
- F0-B pure Java v1 identity + typed action envelope, Python matching v1 wire validation (`CPlusIdentity`, `CPlusActionContract`, `cplus_contracts.py`)
- F0-C guarded lifecycle state transitions and proof requirements, failed/uncertain replay safety (`CPlusLifecycle`, Python `transition`)
- F0-D bounded resource key validation, deterministic acquire ordering, FIFO queue ticket contracts only (`CPlusResources`)
- F0-E persistence/crash/migration contract and existing C1/C2 journal compatibility plan (`ACTION_CONTRACTS.md`)
- F0-F standalone JVM/backend contract tests and Windows regression runner
- F0-G proposed unified command/public API routes, module permissions, legacy mapping (`CPlusCommands`, `UNIFIED_COMMAND_API.md`)

**Explicit not delivered:** Gameplay Action Engine, World Interaction, queuing/locking runtime, Forge command registration or replacement, persistence migration, C1/C2 gameplay changes. Current code retains `C2Scheduler` and `C2ActionJournal` untouched. Contracts are NOT wired to C0/Forge active runtime yet.

## Local results (actual executions)

| Test | Result | Evidence/limit |
|---|---|---|
| Baseline ZIP integrity | PASS | `ZipFile.testzip() == None`, 347 archive members |
| C-F0 isolated Java | **PASS — 71 assertions** | OpenJDK 21 `javac --release 17`, standalone `CPlusF0ContractsSelfTest` |
| C-F0 backend contract tests | **PASS — 15 tests** | Python `unittest` discovery `test_cplus_f0_contracts.py` |
| Existing 22 follow/terrain Java suites | **PASS — 22/22** | All existing isolated suites from airborne baseline script compiled `--release 17` and executed on OpenJDK 21 |
| Existing focused backend C2 | **PASS — 16/16** | `test_c2*.py`, no runtime source altered |
| Full backend suite | **NOT VERIFIED** | Attempt stopped due to execution timeout; partial console warnings do not establish global pass/fail |
| Real JDK17 runtime | **PENDING** | Container JDK is 21; `--release 17` checks bytecode/API target only |
| Forge Gradle `compileJava` | **BLOCKED** | Wrapper attempted Gradle 8.8 distribution download; unavailable in container; no Forge compile evidence |
| Windows client/server Gameplay | **PENDING** | No Minecraft/Forge gameplay performed |
| Multi-NPC 1/3/5/10 MSPT | **PENDING** | No real Forge profile; isolated JVM benchmark is not evidence |
| Crash/rejoin/inventory journal migration | **PENDING / OUT OF SCOPE** | No active C+ persistence or migrations in this Sprint |

### Local commands

```bash
# Isolated F0 Java (real JDK21, Java17 target):
out=$(mktemp -d)
javac --release 17 -encoding UTF-8 -d "$out" \
    forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus/*.java \
    forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF0ContractsSelfTest.java
java -cp "$out" io.github.sirp00m.npcortex.CPlusF0ContractsSelfTest
rm -rf "$out"

PYTHONPATH=backend/src python -m unittest discover -s backend/tests -p 'test_cplus_f0_contracts.py' -q
PYTHONPATH=backend/src python -m unittest discover -s backend/tests -p 'test_c2*.py' -q
```

On Windows run `.\scripts\test-cplus-f0-contracts.ps1`, `.\scripts\test-c2-airborne-target-projection.ps1` and `.\scripts\dev-forge.ps1 -Task build` under Temurin 17; use `docs/cplus-cf0-windows-checklist.md` for gameplay.

## Known limitations / next approval

- Java and Python schemas are both version 1 with common named fields, but cross-language **real message serialization and endpoint acceptance are not wired nor tested**. C-F1 must decide durable fingerprints, persistence keys, write/reconcile windows before making any mutation live.
- `CPlusCommands` is a proposal only, and follow/stop alias translation is not yet safe to assume when concurrent tasks exist.
- Resource FIFO tickets and ordering do not implement locks, fairness, leases or queue persistence. Real deadlock recovery/backpressure require C-F3.
- Permission enforcement and server-thread authority are design invariants, not live enforced by the new stand-alone classes; existing Minecraft permissions remain as they were.
- Do not mark C+ Foundation fully accepted until Windows Forge build/gameplay gates pass and C-F1..C-F6 receive separate approval and verification.
