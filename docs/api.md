# NPCortex local HTTP API (v0.1 draft)

Endpoint (backend defaults to `http://127.0.0.1:8765`):

- `GET /health`: process liveness only, **not** Ollama readiness.
- `POST /v1/chat`: asks a configured character for an English response.

Request:

```json
{
  "character_id": "yui",
  "player_id": "local-player",
  "message": "Want to explore?"
}
```

Successful response:

```json
{
  "character_id": "yui",
  "character_name": "Yui",
  "reply": "Of course! Let's see what's out there!",
  "model": "qwen3:8b"
}
```

`reply` is generated and will vary. `player_id` is a non-secret identifier used solely to partition ephemeral chat context; limit 64 letters/digits/underscore/hyphen. `character_id` must be a valid configured character. Chat messages must be 1–2000 characters.

On errors, JSON contains `{"error": "..."}`. Errors include HTTP 400 for bad input/character, 502 if Ollama fails, 500 for unexpected server failures. The API contains **no action schema** or game state mutation endpoint in v0.1.


## A2 error contract (backward compatible)

Success response fields are unchanged. Errors still contain `error` (human-readable message); A2 also adds `code` (stable machine-readable identifier). Example:

```json
{"error":"This conversation is busy; retry after its previous reply","code":"session_busy"}
```

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `invalid_request`, `invalid_character` | Invalid JSON/input/character |
| 404 | `not_found` | Unknown endpoint |
| 413 | `request_too_large` | Exceeds 8192-byte body limit |
| 429 | `session_busy` | Too many same-session requests, or waiting timed out |
| 502 | `provider_unavailable` | Ollama call failed or returned invalid data |
| 503 | `session_capacity`, `model_busy` | No available session capacity or model slots |
| 500 | `internal_error` | Unexpected backend error |

For same-session requests, the first call holds its turn until Ollama finishes; following calls may wait (default 20 seconds) and see that turn in their history. If they cannot get the turn in time, they fail without adding a user message to history. Session history expires after 30 minutes idle, and at most 128 sessions are retained by default; an inactive least-recently-used session can be evicted earlier at capacity. The backend does **not** authenticate callers: use loopback only.

## B1 — World & Actor Identity API (new; localhost development only)

B1 adds stable UUID-based identity and SQLite-backed **player-to-specific-actor** transcripts.
This is NOT NPC-to-NPC dialogue or autonomous scheduling (reserved for B2/B3). It does
not register Forge entities, send game events or know real in-game locations.

All new endpoints use `application/json` and the existing 8192-byte POST limit. UUIDs
must be lowercase canonical hyphenated strings. World IDs are **stable IDs supplied by
the caller or generated once then saved**: never use display names as identity.
`dimension_id` is a namespaced resource ID, such as `minecraft:overworld`, and
must match the registered actor. Registered actor dimension is immutable in B1.

### 1. Register a world

`POST /v1/worlds` → 201

```json
{"name":"My Single Player World"}
```

Response: `{"world_id":"<generated-uuid>","name":"My Single Player World"}`.
Optionally send a stable `world_id` UUID, e.g. one stored by Forge in the world save
*in a future integration*. Re-registering the same world ID/name is idempotent;
conflicting names return 409. Save the returned ID.

### 2. Register an actor instance

`POST /v1/actors` → 201

```json
{
  "world_id":"<world-uuid>",
  "dimension_id":"minecraft:overworld",
  "character_id":"yui"
}
```

Response includes `actor_id`, `world_id`, `dimension_id`, `character_id`.
Use a different actor UUID for each Yui instance, even when the pack is the same.
Optionally provide an existing stable `actor_id`; identical re-registration is
idempotent, changed identity returns 409. The character pack must exist.
`GET /v1/actors/<actor-uuid>` retrieves the actor or returns 404.

### 3. Player ↔ a specific actor

`POST /v1/actor-chat` → 200

```json
{
  "world_id":"<world-uuid>",
  "dimension_id":"minecraft:overworld",
  "actor_id":"<actor-uuid>",
  "player_id":"local-player",
  "message":"Hey, remember my name?"
}
```

Response contains `actor_id`, `world_id`, `dimension_id`, `conversation_id`,
`character_id`, `character_name`, `reply`, `model`. The deterministic
`conversation_id` is stable for the same `(world_id, actor_id, player_id)`.
`GET /v1/conversations/<conversation-uuid>` returns conversation metadata and its
bounded persisted `messages` (`role`, `content`). These endpoints are available
only to a trusted **local caller**, without authentication. Do NOT port-forward.

### B1 persistence and legacy behavior

- DB default: `backend/data/npcortex.sqlite3` (excluded from Git/ZIP release builds).
- First boot creates SQLite schema version 1. Existing schema v1 is reused; an
  unsupported version aborts startup rather than silently resetting data.
- New actor-chat exchanges persist atomically **only after Qwen replies**. A failed
  model request does not append either part of the exchange. An empty conversation
  record may still be created before a failed attempt.
- At most `NPCORTEX_MAX_TRANSCRIPT_MESSAGES` (default **200 messages = 100 turns**)
  are persisted per conversation; only the last A2 `SessionStore.max_messages`
  (default **12 messages = 6 turns**) are sent back to Qwen.
- `/v1/chat` has the exact original A2 success response and continues to use
  **ephemeral in-memory context** keyed by `(player_id, character_id)`.
  A2 had no disk history; there is nothing to auto-migrate. B1 does not silently
  merge old legacy sessions into actor conversations.
- All new identities must be created via local APIs. The Minecraft command
  `/npcortex ask` still uses `/v1/chat`; it does not yet know actor/world IDs.
  Forge entity binding belongs to a later Sprint.

New failure codes: 400 `invalid_identity`, 404 `registry_not_found`, 409
`identity_conflict`, 503 `storage_unavailable`. A2 error fields and other codes
remain compatible. Concurrent turns for the same actor/player/world are serialized
*within one backend process* using the A2 session lock. Multiple separate backend
processes sharing the same DB are **not supported**; simultaneous cross-process
reads could produce stale Qwen context. No user/authentication or anti-spam model.
