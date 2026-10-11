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
