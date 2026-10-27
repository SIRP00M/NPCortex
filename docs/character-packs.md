# Character packs

> **Update (schema v5):** new NPCs no longer need a Character Pack. Each NPC owns a *Character Profile* stored in the backend database (see `character-profiles.md`). Packs remain supported for legacy actors (Yui, Guide) and as an optional `preset` that is copied once. Nothing below changed for legacy behavior.

Each NPC has a folder `character-packs/<id>/character.json`. IDs must be 1–40 characters, lowercase a–z starting first, followed by a–z, digits, `_` or `-`.

Example:

```json
{
  "schema_version": 1,
  "id": "guide",
  "name": "Guide",
  "language": "en",
  "system_prompt": "You are Guide, a helpful Minecraft companion. Reply in English."
}
```

To add a character:

1. Copy `character-packs/guide` to `character-packs/my_guide`.
2. Change `id` to `my_guide`, then edit `name`, `language` and `system_prompt`.
3. Restart is *not* needed for JSON edits in this scaffold; packs are loaded on each chat request.
4. POST `/v1/chat` using `character_id: "my_guide"`, or in Minecraft type `/npcortex ask my_guide Hello!` once the Forge mod is installed.

The backend currently supports only a name, language label and system prompt; adding JSON fields alone does not create movement, inventory, skin, relationships or abilities. Those features require explicit implementation and validation.

Future format: metadata, portraits/skins, language assets, behavior capabilities, optional event hooks, import/export checks and documented schema migrations.
