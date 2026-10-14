# B2-C Stability Fix — Persistence Architecture Refactor (Implementation Candidate)

- Introduce `SQLiteDatabase` Unit of Work for explicit short-lived snapshot
  reads, `BEGIN IMMEDIATE` serialized writes, rollback, and guaranteed close.
- Route WorldStore and DialogueStore data operations through the central
  manager; preserve SQLite schema version 2 and all B1/B2 endpoint contracts.
- Make B1 transcript read atomic with conversation metadata.
- Close sqlite3 connections explicitly in Windows-sensitive B1/B2 test fixtures.
- Drain bounded bodies of unknown POST routes before sending the existing 404;
  share server lifecycle settings between production and HTTP tests.
- Add six targeted tests for close-counting, rollback, concurrent writes,
  read-only transactions, migration failure cleanup, and repeated 404 responses.
- Add Windows `scripts/test-b2-stability.ps1` and documentation.
- No GitHub operations, Forge Java changes, B3 implementation, or C world simulation.

---

# Changelog

# B2 — Multi-Actor Dialogue Engine (v0.1.1-alpha implementation candidate)

## Added

- `backend/src/npcortex_backend/dialogues.py` — dialogue registry for 2–8
  explicitly selected participants (NPC actors and/or players), with world +
  dimension validation, 64-turn maximum, participant/speaker UUID tracking,
  atomic SQLite turn commits, and idempotent replay using request UUIDs.
- Additive **SQLite schema v1 → v2** migration, preserving B1 tables
  (`worlds`, `actors`, `conversations`, `transcript`) and original data.
  New tables: `dialogues`, `dialogue_participants`, `dialogue_turns`.
- `POST /v1/dialogues`, `GET /v1/dialogues/<uuid>`,
  `POST /v1/dialogues/<uuid>/turns`, `POST /v1/dialogues/<uuid>/close`.
- Qwen generates **one requested NPC's speech at a time**, using that actor's
  Character Pack and dialogue-local shared context. No private B1 transcript
  is injected into other NPCs' contexts.
- A2 session locking/concurrency limits reused per dialogue, including model
  semaphore. Repeated speaker, invalid participant, stale sequence or closed
  dialogue returns a structured 409 without appending a turn.
- Limit and explicit close; responses carry speaker identity, turn sequence,
  remaining turn budget and replay status. Failed model/storage requests cannot
  commit a partial dialogue turn.
- `backend/tests/test_b2_dialogue.py` — migration, isolation, replay, API,
  concurrency, rollback and limits coverage.
- `scripts/test-b2-dialogues.ps1` — real local Qwen and persistence test on
  Windows; `docs/b2-verification.md` and expanded API/architecture docs.

## Preserved

- All B1 routes and player-to-actor persisted transcripts; all A2 `/v1/chat`
  success fields and Forge `/npcortex ask` behavior.
- Same version `v0.1.1-alpha` candidate: no official 1.0 or new release implied.
- Unchanged Forge Java sources and build configuration.

## Explicitly excluded

- No autonomous initiation, daily routines, social relationships, actor goals,
  background world loop, entity spawning, agent actions, world perception,
  multiplayer authorization or C roadmap features.
- No GitHub operations, no file deletion of user databases.

## Known risks

- Backend must be a single localhost process (no shared distributed locks or
  authentication). SQLite v2 cannot be opened by old B1 code; take a stopped-DB
  backup before first migration.
- Last 12 dialogue turns (A2 context window) are sent to Qwen, while up to
  64 are durably saved. Old turns may not influence generated speech.
- A request ID protects *persisted* turns against duplicate processing; a crash
  after model generation but before SQLite commit may regenerate an answer.
- Headless API only. Owner's JDK 17 Forge build / real Ollama-Qwen / Minecraft
  tests remain necessary before considering B2 complete.


## B1 — World & Actor Identity Foundation (v0.1.1-alpha candidate)

- Added `identity.py` for canonical world/actor UUID, namespaced dimension and
  player ID validation. Character Packs remain templates, not Actor identities.
- Added `world_store.py`: SQLite v1 schema (`worlds`, `actors`, `conversations`,
  `transcript`), atomic initialization, WAL, foreign keys, UUID actor registry,
  deterministic conversation identity, atomic exchange commits, bounded stored
  transcripts (200 messages default), explicit future schema-version refusal.
- Added localhost-only `POST /v1/worlds`, `POST /v1/actors`,
  `POST /v1/actor-chat`, `GET /v1/actors/<uuid>`,
  `GET /v1/conversations/<uuid>` with structured 400/404/409/503 errors.
- Extended `ChatService` to support persistent player-to-actor dialogue using
  existing A2 per-session locking and model request limits; failed Ollama calls
  do not append incomplete exchanges to SQLite.
- Added `NPCORTEX_DB_PATH` and `NPCORTEX_MAX_TRANSCRIPT_MESSAGES` configuration.
- Added B1 Python regression tests and full API/Windows verification docs.
- Kept legacy `/v1/chat`, its success response, and the existing Forge command
  unchanged (legacy history stays in RAM by design). No NPC-NPC/C features.
- Recorded future autonomy features in planning documentation only.

**Known risks:** SQLite + actor API have no authentication; single backend
process is required; ForgeGradle Build and real Ollama/Minecraft verification
await a Windows developer machine. No migration from ephemeral A2 RAM is possible.


## v0.1.1-alpha — A2 Stability Hardening (development baseline, 2026-10-14)

This is a stability patch on top of the provided `NPCortex-Development.zip` Forge conversation-bridge baseline, **not** an NPC entity or autonomous-agent release. Forge/Minecraft integration is not yet verified; do not mark this sprint completed until the project owner's Windows quality gate passes.

### Backend

- Serialize requests sharing the same `(player_id, character_id)` for coherent LLM context; limit outstanding requests per session (default 2, including the active one) and their wait time (default 20 s).
- Bound in-memory sessions (default 128); remove idle histories after 30 minutes and evict least-recently-used **inactive** sessions on pressure. Active and queued sessions cannot be evicted.
- Limit simultaneous Ollama calls (default 4) and waiting for provider capacity (default 15 s).
- Preserve `/health` and `/v1/chat` success schemas. Preserve the error string while introducing stable `code` identifiers and bounded 429/503 busy responses.
- Validate backend environment configuration early (port, model, Ollama origin URL, character-packs directory and resource-limit ranges).
- Reject unsupported chunked request bodies and ambiguous duplicate Content-Length headers.
- Align backend package metadata/version with Forge `0.1.1-alpha` (`pyproject.toml` distribution version `0.1.1a0`).

### Forge bridge

- Display descriptive backend HTTP errors and distinguish HTTP timeout, connection refusal and invalid backend configuration in Minecraft chat.
- No new gameplay feature or entity; no command names, HTTP routes or successful response fields changed.

### Tests and docs

- Retain the original five Python tests and add concurrency, history lifecycle, model capacity, config validation, and live localhost HTTP (mock provider) regression tests.
- Correct README/Windows instructions to say that the Gradle Wrapper **is included** in this ZIP.
- Document new environment variables, HTTP error codes and owner-run Windows/Minecraft acceptance checklist.

### Limitations

- No persistent chat memory, authentication, real NPC, navigation or agent actions.
- Session cleanup is lazy on subsequent requests, and eviction forgets prior chat context.
- Backend calls to real Ollama/Qwen and ForgeGradle/Minecraft gameplay remain **unverified** in this tool environment; see `docs/a2-verification.md`.
- No GitHub actions were performed. Commit, push, branch and merge remain the project owner's responsibility.
