# NPCortex Local HTTP API (v0.5.7-alpha)

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
| POST | `/v1/action-reply` | NPC speech grounded in an authoritative result; **no new game actions** |
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


## v0.5.3-alpha multi-step agent response

`POST /v1/agent` retains `action` and `target_item` for older integrations and adds `steps`: an ordered array of at most two `{ "action": "...", "target_item": "..." }` objects. The only allowed two-step plans are `pickup_nearby` followed by `give_item_to_owner` or `drop_stored_item`, with matching non-empty `target_item`. The Minecraft server independently validates and executes each step, aborting on first unconfirmed action. See [Multi-Step Actions](multi-step-actions-v0.5.3.md).

### Player-written memory notes (v0.5.3)
Memory entries added by `/v1/memory` are attributed to the **player** in the
system context. First-person claims in these entries (for example,
`My name is Kenji`) refer to the player. The NPC retains its character-pack
identity. The API shape and SQLite schema have not changed.


## Action dialogue (v0.5.7-alpha)

After Minecraft handles an action, the Forge server optionally posts:

```json
{
  "character_id": "yui",
  "player_id": "9bd61bf2-bf7b-4c9d-a6cd-480a591a72a1",
  "session_id": "ea7f22ba-8ea2-40a8-ae1d-f72969a70422",
  "message": "Yui, wait for me here",
  "action": "stay",
  "result": "[Action confirmed: staying here.]"
}
```

Response includes `reply` (English sentence), `character_id`, `character_name`,
and `model`. No action/steps are returned or executed through this endpoint.
`action` must belong to the allowlisted action vocabulary or equal `plan`. The
`result` string must be non-empty, bounded, and begin with `[`; it is considered
read-only status data. If Ollama is unavailable, the game action still executes
and its factual status remains visible. **This is a local prototype API with
no authentication; do not expose it to other machines.**

The legacy v0.5.3 section above documents the historical two-step restriction.
Current versions allow up to **eight validated steps** via the generic planner.
