# NPCortex Local HTTP API (v0.5.0-alpha)

**Development-only, no authentication. Bind to `127.0.0.1`.** This is a
trusted local-service contract, not suitable for remote access or port forwarding.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Backend process liveness only (not an Ollama health check) |
| GET | `/v1/characters` | List public Character Pack metadata |
| GET | `/v1/characters/<id>` | Resolve a public Character Pack |
| POST | `/v1/chat` | English NPC dialogue |
| POST | `/v1/agent` | Dialogue + server-provided world snapshot -> action proposal |
| GET | `/v1/memory?player_id=<uuid>&session_id=<npc-uuid>&character_id=<id>` | Notes, activity score, three recent chat snippets |
| POST | `/v1/memory` | Add, delete, clear notes or reset relationship activity score |

For regular chat, POST JSON `character_id`, `player_id`, `message` and optionally
`session_id`. Agent requests must also include `world` (see [Phase 6
schema](phase-6-world-actions.md)). Messages are limited to 2000 chars and
request bodies to 8192 bytes. Invalid AI actions are refused server-side.

### Memory API

POST JSON:

```json
{
  "character_id": "yui",
  "player_id": "9bd61bf2-bf7b-4c9d-a6cd-480a591a72a1",
  "session_id": "ea7f22ba-8ea2-40a8-ae1d-f72969a70422",
  "operation": "add",
  "text": "The player likes red flowers"
}
```

`operation` can be `add`, `delete` (requires integer `note_id`), `clear`,
or `reset_relationship`. Notes <=300 characters, max 30 per player/NPC.
Response includes `notes`, `chats`, `affinity`, and `recent`.

The Minecraft Mod constructs these IDs on the **server** after verifying
NPC ownership and distance, but direct HTTP API clients can supply arbitrary
IDs because the local Python server is **unauthenticated**. Never expose
port `8765` outside the local host without first adding authentication.

The SQLite file is under `backend/data/npcortex.sqlite3` (or
`NPCORTEX_DATA_DIR`). Treat it as private player-generated data; backup and
store separately from Git. Memory is durable across Python restarts, not
synchronized between independent backend installations.
