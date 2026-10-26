# NPCortex — GWI C-F3 Option E / E3-D G2 Fix #1 Test Report

**Date:** 2026-10-24  
**Baseline:** `NPCortex_GWI_CF3_E3D_G2_GuardedC2Navigation_FullRepository_CANDIDATE.zip` (452 entries; valid CRC).  
**Change authority:** User-approved **Fix #1, Option B**, correcting the G2 Java `requireIdentity` response correlation; no World Mutation/G3/E3-E approval.  
**Status:** IMPLEMENTATION CANDIDATE — **Windows Build & Minecraft Gameplay PENDING**.

## Root cause (verified in source)

- `CPlusE3G2Runtime.payload(...)` constructs a request with identity fields at the top level, plus `snapshot` containing `observation_id`; it intentionally does **not** include `observation_id` at the request root.
- The Python backend `GuardedMotionStore.reserve(...)` and `.report(...)` return the stored `observation_id` **at the response root**, and preserve request/task/attempt/requester/world/dimension/session/actor IDs.
- The original Java `requireIdentity(sent,received)` required `sent.observation_id`, which cannot exist for this request and therefore rejects a valid Reserve **and** Report reply. Gameplay showed `E3 G2 HOLD: invalid reserve reply; DO NOT retry blindly`.
- **Fix:** `CPlusE3G2Runtime.requireIdentity` delegates to `CPlusE3G2Identity.requireIdentity`. This compares `sent.snapshot.observation_id` to `received.observation_id` after verifying the unchanged top-level identity, snapshot actor/world/dimension/session consistency, and `world_effects` strictly boolean `false`.

## Exact implementation differences

- Modified `forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusE3G2Runtime.java`: delegate Reserve/Report to shared validator; no movement admission/C1/C2 logic changed.
- Added `forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusE3G2Identity.java`: pure Gson identity guard, fail-closed for absent/mismatched observation, identities, or world_effects.
- Added `forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusE3G2IdentitySelfTest.java`: actual Java/Gson reserve/report positive and negative tests (run through Gradle `verifyG2Identity`).
- Modified `forge-mod/build.gradle`: registers `verifyG2Identity` test task, does not change production dependency graph.
- Added `backend/tests/test_cplus_e3_g2_fix1_contract.py`: authenticated real HTTP Reserve/Report/Status parity, corrupted snapshot, invalid ACK, forged completion, duplicate request. Backend runtime unchanged.
- Added `scripts/test-cplus-e3-g2-fix1.ps1` and this report/checklist, prepended single `CHANGELOG.md` and `PENDING_TESTS.txt`.

## Local verified evidence

| Test | Result | Notes |
|---|---|---|
| Baseline G2 Python motion+HTTP | PASS 13/13 | isolated in temp SQLite DBs |
| G1 preview+HTTP | PASS 9/9 | no movement started |
| E2 dispatcher+HTTP | PASS 13/13 | no world mutation |
| E1 coordinator+HTTP | PASS 11/11 | FIFO invariants |
| C-F2 v2 observation | PASS 16/16 | server snapshot contract |
| C-F1 bridge | PASS 9/9 | diagnostic-only transport |
| C-F0 contracts | PASS 15/15 | foundational contracts |
| **Original focused suites subtotal** | **PASS 86/86** | all suites separately returned exit code 0 |
| **Fix #1 real Backend HTTP contract tests** | **PASS 11/11** | separately returned exit code 0 |
| **Total isolated focused checks** | **PASS 97/97** | **not** the full backend suite; bundled run hit tool timeout |
| Java source syntax / Forge Java17 compilation | PENDING | container lacks compatible Gradle deps and Java17 toolchain |
| Java self-test `verifyG2Identity` | PENDING | requires local Gradle / Gson and Java17 on Windows |
| Full Backend suite | PENDING | not claimed |
| Minecraft movement + report ACK | PENDING | requires Windows test world |
| Old G2 attempt recovery | PENDING (G3) | do not auto-replay or delete row |
| C1/C2/Inventory/Follow/C-F1/C-F2 full regression | PENDING | no blanket PASS claim |
| Real 1/3/5/10 NPC MSPT | PENDING | synthetic != gameplay |

## Security and lifecycle invariants

1. Forge still requires a local authenticated, operator-initiated request, fresh server snapshot, current actor/world/session, Follow/C2 motion ownership and loaded local target.
2. Missing/late/forged ACK cannot be promoted to success. `C2_ACCEPTED` is **not** arrived/verified/completed; G3 remains future work.
3. Python G2 attempts in `RESERVED`, `C2_ACCEPTED`, or `HOLD` remain durably fenced. No implicit cleanup, no SQLite deletions, no automatic replay, no forced UUID reuse.
4. Read-only identity fix does not grant permission to modify blocks or inventory; `UNKNOWN` never becomes `ALLOW`.
5. No modifications to C1/C2/Follow/Inventory/C-F1/C-F2 source or GitHub operations.

## Windows commands (from clean extraction)

**Build Forge:** `./scripts/dev-forge.ps1 -Task build`  
**Focused Python (existing):** `./scripts/test-cplus-e3-g2.ps1`  
**Fix #1 Python + Java tests:** `./scripts/test-cplus-e3-g2-fix1.ps1`  
**Backend-only test when Java/Gradle unavailable:** `./scripts/test-cplus-e3-g2-fix1.ps1 -BackendOnly`  
**Full Backend option (slower):** `./scripts/test-cplus-e3-g2-fix1.ps1 -IncludeFullBackend`  
**Backend server:** `./scripts/dev-backend.ps1` (dedicated terminal)  
**Run Minecraft:** `./scripts/dev-forge.ps1 -Task runClient` (dedicated terminal).

See `docs/cplus-cf3-e3-d-g2-fix1-windows-gameplay-checklist.md` for commands and safe test workflow.
