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
- Fresh databases use SQLite schema **v3** (B1, B2 and B3 additive tables).
  An existing B1 schema v1 is **migrated atomically** without deleting or rewriting
  actor or transcript rows. Future/unknown versions fail closed.
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


## B2 — Explicit Multi-Actor Dialogue API (new; localhost only)

B2 adds **multi-participant, shared conversation transcripts** for NPC↔NPC and
Player↔NPC interactions. Create a dialogue with 2–8 distinct participants,
including at least one registered Actor, all in the same world and dimension.
This is a **headless API**: Forge `/npcortex ask` remains unchanged. Qwen supplies
one NPC's speech when explicitly requested by a caller; the backend does **not**
automatically select speakers, tick the world, or initiate social behavior.

### 1. Create dialogue (201)

`POST /v1/dialogues`:

```json
{
  "world_id": "<world-uuid>",
  "dimension_id": "minecraft:overworld",
  "actor_ids": ["<yui-uuid>", "<guide-uuid>"],
  "player_ids": ["local-player"],
  "topic": "Meet each other and talk about an oak tree",
  "max_turns": 12
}
```

`player_ids` may be omitted/empty for pure NPC↔NPC conversations. `max_turns`
defaults to 24 and must be an integer 2–64. Returns `dialogue_id`, `world_id`,
`dimension_id`, `topic`, `max_turns`, `status`, `participants`, `turns` and
`turns_remaining`. For NPC actors, participant entries include `character_id`.
Use the returned UUID for all subsequent requests.

### 2. Record one player's turn (200)

`POST /v1/dialogues/<dialogue-uuid>/turns`:

```json
{
  "speaker_type": "player",
  "speaker_id": "local-player",
  "message": "Hey Yui and Guide!"
}
```

No AI inference occurs for a player-authored turn. Player must be a participant.

### 3. Ask Qwen to generate ONE specified NPC's speech (200)

```json
{
  "speaker_type": "actor",
  "speaker_id": "<yui-uuid>",
  "request_id": "<new-canonical-uuid>"
}
```

Do **not** include `message` for an actor turn; the requested actor's Character
Pack + shared dialogue transcript/topic become Qwen input. Response:

```json
{
  "dialogue_id": "<uuid>",
  "turn": {
    "seq": 2, "request_id": "<uuid>", "speaker_type": "actor",
    "speaker_id": "<yui-uuid>", "content": "Hello there!"
  },
  "status": "open", "turns_remaining": 10, "replayed": false
}
```

For safe HTTP retries, **generate and persist the `request_id` on the client
before making a POST**. Repeating the same ID/speaker request returns the saved
turn with `replayed=true` and does not call Qwen again, even after restarting the
backend. Reusing the ID for a different speaker or changed player text returns
409. Omitting request_id is allowed, but makes timeout retries non-idempotent.
A generated response that was *never persisted* (e.g. crash before SQLite commit)
may be regenerated on retry.

### 4. Inspect or close (200)

- `GET /v1/dialogues/<dialogue-uuid>` — full metadata, participants and ordered
  turns (up to 64, `seq`, `speaker_type`, `speaker_id`, `request_id`, `content`).
- `POST /v1/dialogues/<dialogue-uuid>/close` with `{}` — closes manually;
  idempotent. Dialogues also close automatically at `max_turns`.

Turns are **explicit**; consecutive turns by the same speaker are rejected 409.
A closed/full dialogue rejects new turns, but accepts exact idempotent replays.
Shared context sent to Qwen includes up to the last 12 dialogue turns (the A2
session context default), not private B1 transcripts. Dialogue turn data is
written in a separate SQLite table and is not mixed into the original B1
`transcript` or legacy `/v1/chat` sessions.

Errors retain A2/B1 `{ "error": "...", "code": "..." }` shape. In addition to
400 `invalid_identity`/`invalid_request`, expect 404 `registry_not_found`, 409
`identity_conflict` (unknown participant, repeated speaker, closed/full or stale
turn), 429 `session_busy`, 502 `provider_unavailable`, and 503
`model_busy`/`storage_unavailable`. Model, DB and concurrency failures never
append an incomplete turn.

### Limitations and migration

- B2 uses SQLite schema **v2**, adding `dialogues`, `dialogue_participants`,
  `dialogue_turns`; B1 tables, IDs, transcripts and routes are preserved.
- **Back up `backend/data/npcortex.sqlite3` while backend is stopped** before
  upgrading. Use the same `NPCORTEX_DB_PATH` for restart verification. Downgrading
  B2 schema v2 to B1 is unsupported and refused safely; restore a v1 backup for
  rollback (losing newer B2 dialogue data).
- This is **one backend process** on localhost without authentication. Do not
  use multiplayer/public networking or two backend processes against one DB.
- Qwen output is stochastic and not a verified statement about Minecraft world
  state. No spatial observations, semantic memory, relationship engine, autonomous
  initiation, background simulation, NPC Entities or Forge bindings in B2.
- Dialogue history is deliberately **shared only within that dialogue**;
  private B1 actor chat remains isolated and does not join social dialogue.

## B3 — Headless Social Run API (candidate)

B3 adds `POST /v1/social-runs`, `GET /v1/social-runs/<uuid>`,
`POST /v1/social-runs/<uuid>/step`, and `POST /v1/social-runs/<uuid>/stop`.

Requires a **fresh actor-only B2 dialogue** (at least two NPCs, no player turns),
with a bounded `max_steps` (2–24) that does not exceed `max_turns`.

```json
{"dialogue_id":"<existing-b2-dialogue-uuid>","max_steps":4}
```

Then step once, providing a new UUID for each intended action:

```json
{"request_id":"<generated-uuid>"}
```

Each step calls Qwen to decide `speak` (valid NPC actor UUID) or `stop`, then
uses existing B2 actor speech generation if a speaker was chosen. The runtime
owns the step cap, ordering, and durable journal. Repeat the **same** request
UUID to replay without another model call, including after restart. Planner
output cannot execute world actions. Schema v2 is atomically upgraded to v3;
B1/B2 tables are preserved. See [B3 contract](b3-headless-social.md).


## C0 — Agent Runtime (headless, schema v4)

`POST /v1/agent/goals`, `GET /v1/agent/goals/{id}`,
`POST /v1/agent/goals/{id}/observe`, `POST /v1/agent/goals/{id}/decide`,
`POST /v1/agent/actions/{id}/result`.

See [C0 Agent Protocol](c0-headless-agent-runtime.md) for full examples,
validation, session coordination, idempotency and C1 Player-based Forge Handoff.
There are no real Minecraft world actions in this backend-only sprint.


## Character Profiles (schema v5)

See [character-profiles.md](character-profiles.md) for `POST /v1/actors` selectors, `GET /v1/actors/{id}/profile`, `POST /v1/actors/{id}/profile/revisions` and `GET /v1/worlds/{id}/profiles`. These routes always require the local token header.
