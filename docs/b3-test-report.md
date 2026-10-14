# NPCortex B3 — Automated Test Report (2026-10-15)

## Status
**Implementation Candidate — automated tests passed in this environment.** Windows + Ollama Qwen 3 8B + Forge client quality gates still require user-side evidence. This report does **not** claim B3 is finally accepted.

## Source and scope
- Baseline: `/mnt/data/NPCortex_v0.1.1-alpha_B2C_PersistenceArchitectureRefactor_FullRepository.zip`, audited before modifications.
- B3: AI-driven **headless** speaker/stop coordinator for existing B2 dialogues; 2–8 actors, step budget, SQLite v3 additive persistence, idempotent request UUID, terminal states, failure budget, SQLite race recovery.
- A2/B1/B2 endpoints unchanged; Java Forge sources not modified.
- No B3 background worker, Forge entity, world events, game movement, actions, social simulation of environments, goals, or C implementation.

## Results executed here

| Quality Gate | Result |
|---|---|
| Existing A2/B1/B2-C regression suite | 70 tests passed |
| Combined test suite after B3 | **111 tests passed** |
| Strict `ResourceWarning` suite | **111/111 PASS on 3 completed runs** (independent commands) |
| B3 direct storage/service tests | PASS: speaker selection, turn cap, termination, concurrency, retry, restart, operator close, model failure budget |
| B3 local HTTP API integration | PASS with deterministic mock AI provider |
| B3 real HTTP Ollama adapter protocol | PASS against local test HTTP server (planner `format=json`, speech plain chat) |
| SQLite B2-C schema v2 → B3 v3 | PASS, tested against a real DB constructed using unchanged B2-C source |
| B1 transcript and B2 dialogue retained | PASS: v1 transcript 2 rows, B2 dialogue 1 row, actors and world preserved |
| Database schema version | v3, additive new tables `social_runs` and `social_steps` |
| Python compilation | Source syntax checked via Python test import / compilation pass |
| Forge Build | **BLOCKED** before compile: Gradle Wrapper cannot resolve `services.gradle.org` (`UnknownHostException`); environment Java 21, user Forge requires JDK 17 |
| Qwen real model quality and Windows 3.13 ResourceWarning | **NOT TESTED HERE**; run user acceptance scripts |
| Minecraft gameplay | **NOT TESTED HERE**; unchanged bridge smoke must be repeated by user |

Existing tests for intentionally simulated failures may log expected exception tracebacks, even when the final suite is `OK`.

## Recovery details
- B3 planner can use `OllamaProvider.chat_json()` (JSON-format Ollama HTTP request) or a generic provider's `chat()` fallback. Only a validated allowed `actor_id` may be committed.
- If a dialogue turn is persisted but its B3 journal step is not, the original `request_id` can replay/recover without another model request. A fresh request is blocked until recovery, avoiding an untracked turn.
- Three consecutive model failures close the B3 run with status `failed` and close its dialogue; previous data is retained. Model-capacity errors do not use this failure budget.
- An existing B2-C v2 database is migrated forward to v3 atomically. **Back up with backend stopped**; B2-C cannot re-open schema v3 without upgrading.

## User-side gate
1. Run `./scripts/test-b3-regression.ps1 -Repeat 3` on Windows (Python 3.13 if that's your active Python) — expect each run `Ran 111 tests ... OK`, no `WinError 32`, no unclosed SQLite warnings.
2. Start Ollama with `qwen3:8b`, start `./scripts/dev-backend.ps1`, then in a second terminal run `./scripts/test-b3-social.ps1` — expect PASS checks, actual AI choices and speech, two or more NPC turns, `completed` or `stopped`, never exceeding budget.
3. Save RUN UUID, restart backend, use `./scripts/test-b3-social.ps1 -VerifyRunId '<uuid>'` — expect persisted run, consistent transcript.
4. Optional: run initial script with `-PauseAfter 2`, restart backend, `-ResumeRunId '<uuid>'` to verify continuation after restart.
5. Run `./scripts/phase3-check.ps1`, `./scripts/dev-forge.ps1 -Task build`, and `./scripts/dev-forge.ps1 -Task runClient` with JDK 17; test Minecraft `/npcortex status` and `/npcortex ask yui Hello!`. These validate legacy Forge bridge, not B3 in-game behavior.

## GitHub
No commit/push/branch/merge performed. User manages GitHub; No Test, No Push.
