# NPCortex C-F3 E3-D G3 Big Sprint — Fix #2 Test Report

**Date:** 2026-10-25  
**Scope:** Python Backend empty movement status correction and regressions only  
**Baseline:** `NPCortex_GWI_G3_BigSprint_WindowsSQLiteTestFix1_FullRepository_CANDIDATE.zip`  
**State:** Fix implemented; focused tests PASS; Windows gameplay acceptance PENDING

## Source-backed defect

In `backend/src/npcortex_backend/cplus_e3_g2_motion.py`, `GuardedMotionStore.status(world_id, actor_id)` fetched the most recent movement row. For a new world/new actor it received `row = None` and used `row[12] == 'COMPLETED'` in the **empty** branch, producing `TypeError: 'NoneType' object is not subscriptable`. The authenticated `POST /v1/cplus/e3/status` request consequently returned HTTP 500; Forge displayed its generic `HOLD/offline` message despite the Backend running.

### Correction

Only the empty branch now sets `completion_verified: False`. It still returns `world_id`, `actor_id`, `world_effects: False`, `state: 'NONE'`, `execution_mode: 'GUARDED_C2'`. Existing attempts still use `_result()` and their durable `state`. No changes to endpoint security, DB schema, admission/cancel/finalize, Minecraft world code or existing attempt data.

### Files changed from baseline

1. `backend/src/npcortex_backend/cplus_e3_g2_motion.py` — one empty-return correction and explanatory comment.
2. `backend/tests/test_cplus_e3_g3_empty_status.py` — new unit and authenticated HTTP regression covering new/empty world, unrelated actor/world, no row creation, restart, HOLD, durable terminal COMPLETED and CANCELLED, auth and invalid identity.
3. `scripts/test-cplus-e3-g3-big.ps1` — add new test module to the existing focused runner.
4. `CHANGELOG.md` — one consolidated update.
5. `PENDING_TESTS.txt` — current candidate status at top; older history retained.
6. `docs/cplus-cf3-e3-d-g3-empty-status-fix2-test-report.md` — this report.
7. `docs/cplus-cf3-e3-d-g3-empty-status-fix2-windows-checklist.md` — acceptance commands and stop conditions.

## Reproduction and automated evidence

**Pre-fix:** Calling `GuardedMotionStore(new_temporary_db).status(valid_new_world_uuid, valid_actor_uuid)` reproduced `TypeError: 'NoneType' object is not subscriptable` on Python 3.13.5. The user's actual Windows backend log also shows this stack trace and HTTP 500.

**Post-fix, isolated extracted ZIP working copy, Python 3.13.5:**

| Scope | Result | Meaning |
|---|---|---|
| `test_cplus_e3_g3_empty_status.py` | **14/14 PASS** | Direct SQLite plus real loopback HTTP contract; includes two inherited G2 HTTP tests |
| Six G3/G2 focused modules (including above) | **59/59 PASS** | Completion, Cancel ACK, migration/restart, G2 reserve/report, no auto-replay |
| All `test_cplus_e*.py` files | **113/113 PASS** | E-family coordinator regression (includes the 59 focused cases) |
| C-F0 contract, C-F1 bridge, C-F2 observation suites | **15/15 + 9/9 + 16/16 PASS** | These three suites are distinct from E-family 113 |
| ZIP integrity | **PASS when packaged** | CRC + presence and unchanged baseline member validation |

No Forge code was modified. The user's reported Windows Forge Build PASS applies to the previous Candidate; this new ZIP has **not** been run on their machine. Java/Gson self-tests through Gradle, all Windows Python reruns, full Backend suite, Minecraft gameplay, failure injection and real MSPT remain **PENDING**. The focused Python results do not prove live cancellation/recovery or movement success.

## Required Windows acceptance

1. Run `scripts/test-cplus-e3-g3-big.ps1 -BackendOnly` and the full focused script (including Gradle Java tests), then `dev-forge.ps1 -Task build` separately.
2. Start Backend, confirm `GET /health` succeeds, and in a new disposable world run `/npcortex debug cplus taskmovestatus yui`. Expect `E3 G2=NONE`, **no** HTTP 500, no fake completion.
3. Normal G3: one motion -> C2 `COMPLETED` with verified/local -> `taskmovefinalize` -> status COMPLETED -> a second distinct motion and finalize.
4. Cancel and recovery with exact Goal, backend restart, HOLD/no auto replay, baseline regression, as described in Checklist. Stop on contradictory evidence rather than deleting the DB or retrying an unknown attempt.

**Security/invariants:** Never infer completion when there is no row. `NONE` does not release an actor fence; existing rows persist unchanged. `UNKNOWN` world permission stays `UNKNOWN`. G3 remains operator-only; no World Mutation, E3-E Automatic DAG, or Qwen dispatch. No GitHub operations.
