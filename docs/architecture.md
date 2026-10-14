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

Future B2: NPC-NPC dialogue orchestration (NOT IMPLEMENTED)
Future C: Goals, perception, planning, actions (ROADMAP ONLY)
```

Conversations have a stable UUID derived from world ID, actor ID and player ID.
Character packs are templates, not NPC identities. SQLite connections are scoped
to individual operations, with WAL mode and foreign keys enabled. Backend is a
**single local process**, so A2 turn locks protect one process only. DB schema
version 1 is explicitly checked and unsupported versions fail closed. Transcript
retention is configurable and bounded; no secret/auth protection exists.
See [B1 API](api.md) and [Future C roadmap](future-autonomy-roadmap.md).
