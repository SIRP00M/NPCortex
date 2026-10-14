# NPCortex B3 — AI-Driven Headless Social Runtime (Implementation Candidate)

**Base:** the user-accepted B2-C Persistence Architecture Refactor Full Repository.

## Added

- `backend/src/npcortex_backend/social.py` — `SocialStore` with durable Social Run + Step journal, active/terminal statuses, explicit operator stop, failure budget (3 consecutive provider failures), idempotent step records, SQLite transactions.
- `backend/src/npcortex_backend/social_runtime.py` — Qwen speaker/stop selection, strict JSON/UUID checks, bounded 2–24-step orchestration, response retry, secure context isolation, per-dialogue serialization, crash-window recovery via original B2 request UUID.
- New HTTP API: `POST /v1/social-runs`, `GET /v1/social-runs/<uuid>`, `POST /v1/social-runs/<uuid>/step`, `POST /v1/social-runs/<uuid>/stop`.
- `OllamaProvider.chat_json()` with Ollama JSON format for coordinator only; legacy `.chat()` remains unchanged.
- SQLite schema v3 with additive `social_runs`, `social_steps`; existing B1 and B2 data and API are preserved. No destructive migration.
- `backend/tests/test_b3_social.py` — isolated tests: speaker choice, no impersonation, budgets, stop, retries, restart, partial-commit recovery, concurrent requests, HTTP and Ollama JSON mode transport.
- PowerShell `scripts/test-b3-social.ps1` for real Qwen acceptance (including pause/resume and post-restart verification), `scripts/test-b3-regression.ps1` for strict Windows Python regression.
- `docs/b3-headless-social.md` API contract, rationale and C extension roadmap status.

## Changed

- `ChatService` exposes creation/inspection/step/stop service methods, shares the B2 dialogue lock, and refuses manual turn insertion into an active B3-run-owned dialogue. Refactored the internal B2 turn method to make it callable safely under that same lock.
- B2 explicit close also closes the associated active B3 run. Existing B2 dialogue calls without a B3 run preserve their contract.
- README, API docs, architecture and roadmap updated for new candidate, v3 schema and future C boundary.
- B2 migration test now expects the **current schema v3** while still validating v1→v2→v3 data survival.

## Explicitly out of scope

No Minecraft Entity, NPC UI, pathfinding, world reading/events, autonomous loop/timer, NPC daily-life agents, dynamic relationships, social memory across dialogues, TaCZ or other game actions. No GitHub operations.

## Compatibility and risks

- SQLite **v3 is forward-only**. Back up the SQLite file with the Backend stopped before first B3 startup. Do not downgrade a migrated DB by launching B2-C against it.
- Explicit request UUID required for every B3 step, enabling safe retry after loss of HTTP response and potential between-journal crash.
- Qwen may return malformed/non-compliant JSON; planner retries once and returns structured 502 if still invalid. Three consecutive model failures close the social run. A new dialogue/run is needed then.
- Existing in-memory locking is **single-backend-process**; no multiplayer auth or distributed locking is claimed.
- An already-running Ollama/Qwen model and Windows environment are needed for user acceptance; fake Ollama HTTP tests do not prove real Qwen response reliability.
- Forge source unchanged; full Forge Build depends on Gradle distribution availability and suitable JDK 17.
