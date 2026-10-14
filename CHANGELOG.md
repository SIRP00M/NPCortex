# Changelog

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
