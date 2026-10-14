# Architecture

```text
Minecraft 1.20.1 / Forge 47.4.10 (Java 17)
  /npcortex ask ...
       |
       | HTTP POST /v1/chat (server -> localhost; JSON)
       v
NPCortex Python Backend
  ├─ validate input + load character pack
  ├─ load bounded in-memory session history
  └─ AI provider interface (currently Ollama only)
              |
              | POST /api/chat: qwen3:8b; think=false; stream=false
              v
           Ollama
```

## Boundaries

**Minecraft layer:** game authority, player identity, future NPC entities, future permission checks, future movement/combat and mod compatibility.

**Backend layer:** character prompt, chat memory/session, provider adapters, dialogue. The backend cannot modify game state directly.

**Character packs:** editable JSON files specifying identity and system prompt. The starter schema is intentionally small and may change before v1.0.

**Integrations:** optional modules that explicitly validate and execute allowed game actions; none are implemented yet.

## Session scope

History is stored in Python process RAM by `(player_id, character_id)`, keeping at most 12 messages (six turns). A2 serializes turns for the same session, allows at most two active/queued requests per session, and caps how long requests wait. Idle sessions expire after 30 minutes (lazy cleanup on the next session request); the store holds at most 128 sessions and evicts least-recently-used **idle** sessions when full. Busy sessions are never expired or evicted. Concurrent Ollama requests are limited to four by default. All limits are environment-configurable. Restarting the backend erases history; LRU eviction also forgets old context. This is *not* persistent memory or target re-identification. A production multiplayer server still needs persistent storage, authentication and more comprehensive resource/rate limiting.

## Security

The local API has no auth and is bound to `127.0.0.1`. It is a development prototype, not ready for public hosting. Add authentication, authorization, rate limiting, abuse protection and structured action validation before supporting remote clients. Never accept unaudited actions from LLM text.


## B1: Persistent world and actor foundation

```text
A2 Forge /npcortex ask --> /v1/chat --> A2 RAM sessions (unchanged)

Headless B1 caller (PowerShell/HTTP)
  POST /v1/worlds --> SQLite world registry (UUID)
  POST /v1/actors --> SQLite actor registry (UUID + world/dimension + pack)
  POST /v1/actor-chat --> A2 per-actor/per-player lease
                       --> SQLite persisted conversation history
                       --> AI Provider / Ollama Qwen
                       --> atomic verified user/assistant transcript append

B2: Explicit NPC-NPC multi-party turns (headless API, implemented candidate)
Future C: Goals, perception, planning, actions (ROADMAP ONLY)
```

Conversations have a stable UUID derived from world ID, actor ID and player ID.
Character packs are templates, not NPC identities. SQLite connections are scoped
to individual operations, with WAL mode and foreign keys enabled. Backend is a
**single local process**, so A2 turn locks protect one process only. DB schema
version 1 is explicitly checked and unsupported versions fail closed. Transcript
retention is configurable and bounded; no secret/auth protection exists.
See [B1 API](api.md) and [Future C roadmap](future-autonomy-roadmap.md).


## B2: Explicit multi-actor dialogue runtime (candidate)

```text
Headless caller (PowerShell/HTTP)
  POST /v1/dialogues -> validate world + 2-8 participants -> SQLite v2 dialogues
  POST /v1/dialogues/<id>/turns
       -> A2 per-dialogue lease, per-conversation ordering
       -> validate speaker, turn limit, request_id
       -> player speech: directly append
       -> actor speech: Character Pack + shared dialogue-local history
                    -> A2 Ollama concurrency semaphore -> Qwen response
       -> atomic SQLite turn append (speaker_type + actor/player ID)
  GET /v1/dialogues/<id> -> durable ordered transcript
  POST /v1/dialogues/<id>/close -> no more turns

Existing /v1/actor-chat -> B1 private durable transcript (unchanged)
Existing /v1/chat -> A2 ephemeral player/character chat (unchanged)
```

SQLite migration is additive and transactional v1→v2; three new tables leave
`worlds`, `actors`, `conversations`, and `transcript` intact. The B2 API neither
bridges Minecraft's entity UUIDs nor selects its own speaker. Qwen is asked to
produce one line of speech given an explicitly chosen NPC, no autonomy loop.
The A2 per-session in-process lock and model budget are reused; no distributed
locks or authentication are available. See [B2 Windows verification](b2-verification.md).


## B2-C — SQLite Persistence & HTTP Lifecycle Stabilization

`npcortex_backend.persistence.SQLiteDatabase` centralizes resource ownership:

- `read()`: opens one connection, sets `query_only`, begins a consistent read
  snapshot, and always closes the file handle at scope exit.
- `write()`: opens one connection, begins `BEGIN IMMEDIATE` (SQLite serializes
  writers), commits once after success, rolls back on exception, and closes.
- `maintenance()`: startup-only connection used by the pre-existing v1/v2
  additive migration scripts, whose explicit `BEGIN`/`COMMIT` semantics remain
  unchanged. On migration errors any open transaction is rolled back.
- WorldStore and DialogueStore are the existing domain-specific persistence
  repositories: they route read/write units of work through the same manager.
  No network AI call occurs while an SQLite transaction is open.
- A connection belongs to exactly one operation/thread. No long-lived shared
  SQLite handle, speculative connection pool, distributed lock, or schema v3.
- `WorldStore.get_conversation()` reads metadata and transcript in one read
  snapshot rather than two independent transactions.

The HTTP server factory `make_http_server()` supplies the same threaded
configuration to production and tests; `serve()` closes the server via its
context manager. The unknown-POST path drains a bounded request body before
replying with the existing JSON 404 contract, avoiding premature close of an
unread socket buffer on Windows.

Tests explicitly close raw sqlite3 connections via `sqlite_test_utils` (the
native `sqlite3.Connection` context manager **does not close connections**).
The database remains SQLite v2, with identical B1/B2 API payloads and SQL
schema. No B3 scheduling, NPC entity, autonomous behaviors, or C-world features.

## B3: AI-driven, bounded, headless social runtime (candidate)

```text
Trusted local caller -> POST /v1/social-runs [fresh actor-only B2 dialogue]
                     -> POST /v1/social-runs/<run-id>/step [request UUID]
                        -> per-dialogue B2 session lock
                        -> Qwen JSON planner (choose allowed speaker or stop)
                        -> runtime checks participant, sequence, step budget
                        -> B2 character speech generation (Qwen)
                        -> B2 dialogue_turns + B3 social_steps journal
                        -> idempotent replay/restart recovery
                     -> GET /v1/social-runs/<run-id>
```

B3 adds two tables on SQLite schema v3. Its planner is advisory and cannot
submit Minecraft game actions or access B1 private actor/player transcripts.
A caller must advance each step; this is NOT a free-running/background simulation.
See [B3 social runtime](b3-headless-social.md) for failure budgets and limitations.
Any future C event/sensor/world-action adapter requires a new audit and approval.
