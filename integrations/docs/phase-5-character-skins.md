# Phase 5 — Character Packs & Local Minecraft Skins (v0.3.0-alpha)

## The design

- The **Python backend** owns the character's AI profile, name, language, and `appearance` fields.
- The **Forge server** asks `GET /v1/characters/<id>` in the background to sync name/skin type onto a spawned NPC; NPCs can still spawn with a fallback ID if the backend is offline.
- The **Minecraft client** loads `config/npcortex/skins/<skin>.png` via a `DynamicTexture` and renders the NPC using the wide/Steve or slim/Alex player model. If a skin is missing, it uses Minecraft's default player skin.
- Minecraft does **not** load the NPC's system prompt or access the Ollama API directly. Character prompts stay in the local backend.
- World saves include owner UUID, character ID, skin ID, arm model, following state and custom name. They survive world reloads.
- Chat session history is isolated by `(player_id, character_id, npc_uuid)`. **It remains in Python RAM and resets after backend restart**. Persistent AI memories are a future feature.

## 1. Choose a custom Character Pack

Duplicate the files from `character-packs/template` to `character-packs/forest_guard`:

```
character-packs/forest_guard/
  character.json
  skin.png                 # 64x64 Minecraft Java Edition skin
```

Example `character.json`:

```json
{
  "schema_version": 2,
  "id": "forest_guard",
  "name": "Forest Guard",
  "language": "en",
  "system_prompt": "You are a patient, protective forest explorer. Speak English. Don't pretend in-game actions occurred.",
  "appearance": {"skin": "forest_guard", "model": "slim"}
}
```

- `id` and `appearance.skin`: only lowercase a–z, digits, underscore or hyphen, start with a letter, max 40 characters.
- `name`: 1–80 characters.
- `system_prompt`: 1–5000 characters.
- `appearance.model`: `wide` (4-pixel arms) or `slim` (3-pixel arms).
- `skin.png`: standard 64×64 Minecraft Java Edition skin, max 1 MiB. Legacy 64×32 skins are not accepted.
- Only use skins and character content you have permission to redistribute.

The Yui and Guide skins included here are simple **original placeholder test skins**. Replace them with your desired skins.

## 2. Install skin into your Minecraft game directory

For the development client (`forge-mod/run`):

```powershell
cd F:\AI\NPCortex
.\scripts\install-character.ps1 -Id yui
.\scripts\install-character.ps1 -Id guide
```

The script copies the PNG to `forge-mod/run/config/npcortex/skins/<id>.png`. The backend reads `character-packs/<id>/character.json` directly on each request, so JSON updates do not require rebuilding the mod.

For a CurseForge profile, explicitly point at the *instance root* (containing `mods` and `config`):

```powershell
.\scripts\install-character.ps1 -Id yui -GameDir "F:\CurseForge\Instances\MyModpack"
```

**Important:** Minecraft client-side PNG skins must be installed on every player's client. Other clients will show default skins when a PNG is absent. Do not point a client at untrusted arbitrary skin files.

## 3. Spawn, sync, and talk

1. Restart the Python backend to enable newly added Phase 5 API routes if upgrading from Phase 4.
2. Start the Forge development client after you build the updated mod.
3. In a cheats-enabled test world run:

```
/npcortex list
/npcortex spawn yui
/npcortex spawn guide
/npcortex sync
/npcortex follow
/npcortex stay
```

Right-click a spawned NPC to chat. `/npcortex sync` refreshes the nearest *owned* NPC within 12 blocks after editing its `character.json` (name/model/skin selection). When replacing the **contents** of a PNG with the same ID, restart the Minecraft client to clear the texture cache.

Existing Phase 4 NPCs remain compatible; they default to their character ID as skin ID until `/npcortex sync` updates metadata.

## Troubleshooting

| Problem | Check |
|---|---|
| `NPCortex: Cannot load character` | Backend running? `GET /v1/characters/<id>` returns 200? Valid `character.json`? |
| NPC shows vanilla skin | Wrong instance folder, missing PNG, not 64×64, over 1 MiB, or client was not restarted? |
| `/npcortex list` missing | Stop the old backend and run the updated `dev-backend.ps1` again. |
| NPC name not updated | Stand within 12 blocks, `/npcortex sync`. |
| NPC with same character shares memory | Phase 5 isolates in-game NPC UUIDs; `/npcortex ask` command still uses legacy per-character session. |
| PowerShell scripts blocked | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`; if organizational policies override this, use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-character.ps1 -Id yui` only for trusted local scripts. |

### Security and current limitations

Backend binds `127.0.0.1` by default and has no authentication. Never expose its port publicly. The skin system is local to each client (no file uploads over the network). Skin caching needs a client restart after PNG edits. There is no editor GUI, remote skin CDN, AI persistent memory, inventory or combat in this release.
