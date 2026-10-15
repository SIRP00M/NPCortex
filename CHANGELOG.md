# Changelog

NPCortex maintains **one consolidated changelog** for the `Development` line. Historical sprint labels below describe implementation milestones, **not independent releases**; the Forge source continues to declare **`0.1.1-alpha`** and Python declares `0.1.1a0` in this snapshot.

> **Scope of this snapshot:** C0-B backend agent reliability + Windows acceptance-script fix, based on the earlier B3 Development line. The public [`Development` branch](https://github.com/SIRP00M/NPCortex/tree/Development) must be updated by the project maintainer; this local ZIP alone does not change GitHub. C0 is a **headless backend protocol** and **not** a Minecraft NPC/entity/autonomous gameplay implementation.

All historical test results below apply **only** to the stated test scope and environment. Windows owner acceptance is identified as *owner-reported*; Python unit tests cannot substitute for real Qwen or Minecraft gameplay.

## Unreleased — Documentation consolidation (2026-10-15)

### Changed

- Consolidated the prior B3, B3-B, C0, C0-B planner and C0-B acceptance-script changelog files into **this** `CHANGELOG.md`; removed the duplicate `docs/c0-changelog.md`.
- Rewrote `ReadMe.md` as the entry point for the active Development-line source: capabilities, limitations, Windows startup and acceptance scripts, SQLite migrations and C1 scope.
- Aligned architecture/roadmap documentation with the actual C0 headless source and distinguished the local ZIP from the remote branch.

### Not changed

- No Python/Java implementation, Forge build configuration, SQLite migration or runtime protocol was changed as part of this documentation consolidation.
- No GitHub operations, automatic release/tag, or new gameplay feature.

## C0-B — Windows Acceptance Script Fix (2026-10-15)

**Base:** C0-B Structured Decision Reliability. **Scope:** PowerShell verification only.

### Fixed

- `scripts/test-c0-headless.ps1`: materialize action-ledger and filtered-status pipeline results as PowerShell arrays before checking `.Count` to avoid incorrect handling of a single matching action.
- Check that a persisted completed goal has exactly **two** actions: **one `unverified`** and **one `succeeded`**. Print `action_id`, `skill_id`, `status` and `verdict` diagnostics only when counts disagree.
- `backend/tests/test_c0_windows_acceptance_script.py`: three static source guard tests for the corrected assertions and mismatch diagnostics.

### Evidence and limits

- Owner's earlier real-Qwen mock-world test produced one rejected false-success action followed by one independently verified action. Direct API inspection showed the correct persisted statuses even when the old PowerShell count assertion failed.
- The source ZIP's [`docs/c0b-acceptance-test-report.md`](docs/c0b-acceptance-test-report.md) records **185/185 Python regression tests in each of three Linux runs**, and **3/3** targeted tests; these are historical report results, **not** reruns claimed by this changelog entry.
- The maintainer subsequently reported the Windows C0-B acceptance-script fix passed. This means the script's Windows validation was accepted; it **does not** demonstrate Minecraft world actions or C1.
- No changes to Python backend runtime logic, Ollama transport, DB schema (still **v4**), B1–B3 APIs or Forge Java code.

See [Windows steps](docs/c0b-acceptance-windows.md).

## C0-B — Structured Decision Reliability (October 2026)

**Base:** C0 Complete Headless Agent Runtime.

### Changed

- Ground Qwen action choices in **skills advertised by the current observation**, not a global list of always-available tools; provide strict argument shapes and bounded grounded examples.
- Use a per-decision **JSON Schema** in Ollama `/api/chat.format`, `temperature: 0` and a C0-only `num_predict: 256` budget; the B3 social coordinator retains its pre-existing JSON mode.
- Supply server-generated failure reasons on the retry when the first response violates JSON shape, skill availability, observation or argument preconditions.
- Introduce `PlannerDecisionError` and HTTP 502 code **`planner_invalid_decision`** for invalid model answers after validation retries, distinct from **`provider_unavailable`** for transport/model availability errors.
- Add **10** planner-focused tests, `scripts/test-c0-planner.ps1`, and [planner verification guidance](docs/c0-planner-reliability.md).

### Unchanged and limitations

- SQLite **v4**, existing Goal/Action state machines, result verification, B1–B3 social APIs, Forge and physical skill execution are unchanged.
- Models can still fail to produce valid decisions; no fallback action is invented and no model response proves a physical world mutation.

## C0 — Complete Headless Agent Runtime (October 2026)

**Base:** B3-B HTTP Lifecycle Hardening.

### Added

- `agent_contracts.py`: validated, world/dimension/actor-bound observation snapshots with positions, blocks, inventory, entities/drops, advertised capabilities, and typed success criteria.
- `agent_skills.py`: modular SkillRegistry contracts for **`move_to`**, **`break_block`**, **`place_block`**, **`pickup_item`**, including argument and precondition checking and postcondition evidence requirements.
- `agent_runtime.py`: explicit bounded **observe → decide → action request → result → verify** flow driven by Qwen and reported snapshots; durable request/observation/result identities, recovery after pending actions and conservative verification.
- Additive **SQLite v3 → v4** migrations with durable `agent_goals`, `agent_observations`, `agent_decisions` and `agent_actions` without removing B1–B3 history.
- New HTTP endpoints under `/v1/agent/*`, headless Python tests and `scripts/test-c0-regression.ps1`, `scripts/test-c0-headless.ps1`.
- [C0 protocol](docs/c0-headless-agent-runtime.md), [Windows verification](docs/c0-windows-verification.md), [C0 → C1 handoff](docs/c0-c1-handoff.md).

### Explicit limits

- The backend **does not** read a real Minecraft server world or execute registered skills there. A caller must submit snapshots and report effects. There is **no** autonomous background world tick/scheduler.
- No player-based NPC entity, movement/navigation, mining, tree harvesting, building, crafting, survival, TaCZ integration, or dynamic relationship engine was implemented by C0.

## B3-B — HTTP Request Lifecycle Hardening (October 2026)

**Base:** B3 Headless Social Runtime. Focus: Windows HTTP `WinError 10053` stability, with no social gameplay changes.

- Bounded, chunked draining of bodies for oversized POST requests before **413**, and unknown routes before **404**; close connections deliberately for invalid framing, too-long or slow requests rather than reading unbounded data.
- `backend/tests/test_http_request_lifecycle.py`: **five** focused HTTP lifecycle tests; `scripts/test-b3-http-lifecycle.ps1` for repeatable Windows checks.
- [HTTP lifecycle verification](docs/b3-http-lifecycle-hardening.md) explains trade-offs. Reject drains longer than **1 MiB**, malformed framing, or slow requests can still result in TCP resets by design.
- No SQLite migration, Ollama/AI planner change, Forge Java change, or new API routes.

## B3 — AI-Driven Headless Social Runtime (October 2026)

**Base:** B2-C Persistence Architecture Refactor.

### Added

- `social.py` with persistent Social Runs/Steps, active/terminal states, user-requested stop, bounded consecutive model-failure budget and idempotent journal records.
- `social_runtime.py`: Qwen selects the **next NPC speaker** or stops; caller explicitly advances a run (2–24 bounded steps). JSON/UUID validation, retry, per-dialogue serialization and crash-window recovery through B2 request UUIDs.
- Additive **SQLite v2 → v3** migration: `social_runs`, `social_steps` tables while preserving older actors/dialogues/transcripts.
- `POST /v1/social-runs`, `GET /v1/social-runs/<uuid>`, `POST /v1/social-runs/<uuid>/step`, `POST /v1/social-runs/<uuid>/stop`.
- `OllamaProvider.chat_json()` for Qwen coordinator JSON; no change to legacy `.chat()` behavior.
- B3 tests and `scripts/test-b3-social.ps1` / `scripts/test-b3-regression.ps1`; [headless social guide](docs/b3-headless-social.md).

### Behavior and risks

- A B2 dialogue owned by an active social run cannot receive uncoordinated manual turns. Closing a dialogue terminates its associated active run.
- Up to three consecutive model failures can close the run; backend in-process locks are for one process only; model validity and real Qwen remain distinct acceptance concerns.
- No self-starting social scheduler, relationship scores, Minecraft NPC entities, physical actions or C world simulation.
- Schema migrations are forward-only; **stop backend and back up SQLite before upgrade**.

## B2-C — Persistence Architecture Refactor (October 2026)

- Introduced `SQLiteDatabase` short-lived units of work for explicit snapshot reads, `BEGIN IMMEDIATE` writes, rollback and deterministic connection close.
- Routed B1 WorldStore and B2 DialogueStore data access through the shared persistence helper; transcript metadata/read snapshots are atomic.
- Closed SQLite connections in Windows-sensitive test fixtures and added six focused persistence/HTTP tests, [stability verification](docs/b2-c-stability-verification.md), and `scripts/test-b2-stability.ps1`.
- Drain bounded unknown POST bodies before existing 404 handling; shared HTTP server lifecycle settings for production and tests.
- The B2-C change by itself preserved **SQLite schema v2**; later B3/C0 changes upgraded the schema separately.
- No Forge Java modifications or autonomous world simulation.

## B2 — Explicit Multi-Actor Dialogue Engine (October 2026)

- Added `dialogues.py` with **2–8** explicit participants (actors and optional players), strict world/dimension checks, speaker identity and UUID, up to **64 stored turns**, bounded context and atomic SQLite turn commits.
- Additive **SQLite v1 → v2** migration: `dialogues`, `dialogue_participants`, `dialogue_turns`; B1 records preserved.
- Added `POST /v1/dialogues`, `GET /v1/dialogues/<uuid>`, `POST /v1/dialogues/<uuid>/turns`, and `POST /v1/dialogues/<uuid>/close`.
- Qwen generates **one explicitly requested actor's speech** from Character Pack and dialogue-local history; it does not choose future speakers in B2. Repeated speakers, stale sequences and closed dialogues return structured errors.
- Per-dialogue locks/model concurrency budget, request UUID idempotency and no partial turn append on failed provider/storage calls.
- Added B2 regression tests, Windows `scripts/test-b2-dialogues.ps1`, [API details](docs/api.md), and [Windows checklist](docs/b2-verification.md).
- Existing `/v1/chat` remains in-memory and `/v1/actor-chat` remains private actor conversation. Neither is a Forge entity or autonomous agent.

## B1 — World and Actor Identity (October 2026)

- Added `identity.py` for UUID, Minecraft dimension ID and player identity validation. Character Packs are templates, not Actor instances.
- Added `world_store.py` and **SQLite v1** world/actor registry (`worlds`, `actors`, `conversations`, `transcript`), stable persisted Actor UUIDs, WAL mode/foreign keys and bounded transcripts.
- Added `POST /v1/worlds`, `POST /v1/actors`, `POST /v1/actor-chat`, `GET /v1/actors/<uuid>` and `GET /v1/conversations/<uuid>`.
- Added `NPCORTEX_DB_PATH`, `NPCORTEX_MAX_TRANSCRIPT_MESSAGES`, B1 tests and Windows verification documentation.
- Failed Ollama calls do not commit incomplete persisted exchanges; original Forge `/npcortex ask` still calls legacy `/v1/chat`.
- B1 did **not** add Minecraft NPC spawning, pathfinding, NPC-NPC social activity or C0 agent actions.

## A2 — Forge Chat Bridge Stability Baseline (`v0.1.1-alpha`, 2026-10-14)

### Backend

- Ordered same-session requests by `(player_id, character_id)` with bounded queued requests and wait times. Defaults: two outstanding requests/session, 20-second per-session wait, 128 sessions, 30-minute idle TTL, four concurrent model requests, 15-second provider wait.
- Maintained `/health` and `/v1/chat` success contracts; added consistent bounded 429/503 errors with stable `code` fields and validated environment settings.
- Rejected ambiguous duplicate `Content-Length` and unsupported chunked request bodies. Stored only bounded in-memory history on legacy chat.
- Aligned backend package metadata to Forge source `0.1.1-alpha` (`0.1.1a0` for Python packaging).

### Forge bridge and tests

- Clarified HTTP timeout/refused connection/invalid backend errors in Minecraft developer chat; existing command names and successful route schema preserved.
- Expanded Python concurrency, session lifecycle, model-capacity, config and localhost HTTP tests with mock provider.
- Documented A2 Windows smoke tests and resource-limit variables in [A2 verification](docs/a2-verification.md).

### Historical limitations

- At this A2 milestone there was **no** persisted actor/dialogue/agent history; later B1–C0 added those separately. There is still no authentication or in-game NPC entity in this source tree.
- Environment-specific ForgeGradle and real Ollama/Minecraft test results must not be inferred from the original mock Python suite.

---

## Migration and release notes (apply to the current source)

- Current SQLite schema is **v4**; on startup earlier v1/v2/v3 databases are upgraded **forward**. Stop the backend and back up `backend/data/npcortex.sqlite3` before replacing development versions. Downgrading code against an upgraded database is unsupported without restoring a compatible backup.
- The server is a localhost development service without authentication. Do not expose it remotely.
- C1 True Player-based NPC is a separate architecture/feasibility effort: networking, rendering, lifecycle and compatibility are not implied by a C0 headless acceptance result.
- Keep using a **single `CHANGELOG.md`** for future changes; add newer entries at the top under `Unreleased` or a specifically tagged version only when a release is actually made.
