# Custom character starter

Copy this folder to `character-packs/<your_id>` (lowercase ID).
Rename `character.example.json` to `character.json`, change its `id`, `name`,
`system_prompt`, and `appearance.skin` to match the ID. Add `skin.png` (64×64 RGBA).

From the repository root, install:

```powershell
.\scripts\install-character.ps1 -Id your_id
```

Then run `/npcortex list` and `/npcortex spawn your_id` in the game.
