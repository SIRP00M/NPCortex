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

History is stored in Python process RAM by `(player_id, character_id)`, keeping at most 12 messages (six turns). Restarting the backend erases history. This is *not* persistent memory or target re-identification. A production server needs expiry, bounded session count, persistent storage and concurrency controls.

## Security

The local API has no auth and is bound to `127.0.0.1`. It is a development prototype, not ready for public hosting. Add authentication, authorization, rate limiting, abuse protection and structured action validation before supporting remote clients. Never accept unaudited actions from LLM text.
