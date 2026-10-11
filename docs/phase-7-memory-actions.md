# NPCortex Phase 7 — Persistent Memory, Friendship & NPC Actions

**Version:** v0.5.0-alpha (Forge 1.20.1 / 47.4.10, JDK 17, Python 3.10+).

## What's real in this build

- SQLite stores the most recent **200 exchanges per player/NPC pairing** and **up to 30 explicit memory notes** (each 300 chars). Pairing keys are `(player UUID, character ID + NPC UUID)`; moving an NPC to another world/server/backend does **not** automatically transfer memory.
- Memory and chat history survive restarting the Python server. The database is under `backend/data/npcortex.sqlite3` by default; set `NPCORTEX_DATA_DIR` to change the location. Database and backups are `.gitignore`d; never commit real user memories.
- Friendship is a simple activity score **+1 per successful exchange, capped at 100**. `chats` is the count since the last relationship reset. It is **not** an AI emotional model, and does not imply consent or romance.
- ESC > NPCortex Manager > select nearby NPC > Memory Manager. Read, add, delete one note, clear all notes (two taps), or reset friendship. Backend notes are included as untrusted *data* in the character's prompt. The client UI uses a server-validated packet; it cannot supply the owner UUID directly.
- `/npcortex inventory` displays the selected/nearest **owned** NPC's 9-slot storage. Equipped hand item is separate. Inventory and held item are written into the Minecraft World Save.
- Existing skin manager, dialogue, world observation, and follow/stay are retained.

## Action allowlist

| Action | Effects and limits |
|---|---|
| `follow`, `stay`, `look_at_player` | Existing actions |
| `move_to_player` | One-shot pathfinding request to owner; does not guarantee arrival |
| `wander` | Attempts a short nearby safe destination, may fail |
| `jump` | Requests a ground jump |
| `pickup_nearby` | Picks up **one dropped item entity** within 3 blocks into 9 inventory slots; does not open containers |
| `take_offered_item` | Owner voluntarily gives **one** item from main hand into NPC bag, if space |
| `give_item_to_owner` | Transfers **one** item from NPC bag to owner if space |
| `equip_item` | Equips first stored item into main hand, stows previous held item |
| `drop_held_item` | Drops currently held item into game world |
| `none` | Dialogue only |

**All actions** require explicit user request. Server verifies player ownership, living NPC, game dimension, and max 8-block distance at execution time. The AI never directly executes commands, writes files, moves arbitrary coordinates, attacks, mines, crafts, opens chests, or changes server permissions. For movement, `Action started` is not arrival confirmation.

## Windows PowerShell

```powershell
cd F:\AI\NPCortex
# Recommend committing the previous known working version before replacing files.
# If PowerShell denies execution, once per user (if system policy allows):
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

# Terminal A: stop the OLD backend, then start the upgraded one:
.\scripts\dev-backend.ps1

# Terminal B:
.\scripts\phase7-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

In the test world, spawn or select an owned NPC. Dialogue test cases:

```text
Yui, take one of the items I'm holding.
Yui, please pick up that dropped item.
Yui, equip something from your bag.
Yui, drop what you're holding.
Yui, give me one item from your bag.
Yui, walk over to me.
Yui, wander around for a bit.
Yui, jump!
```

Then run `/npcortex inventory`. To verify DB persistence, save a note, close the Python Backend, restart it and open the Memory Manager again. To make an online-safe SQLite backup:

```powershell
.\scripts\backup-memory.ps1
```

## HTTP memory API — local development only

- `GET /v1/memory?player_id=<uuid>&character_id=<id>&session_id=<npc-uuid>` reads notes, score, and recent chat snippets.
- `POST /v1/memory` accepts JSON `player_id`, `character_id`, `session_id`, `operation` (`add`/`delete`/`clear`/`reset_relationship`), plus `text` for `add` or `note_id` for `delete`.

These endpoints have **no backend HTTP authentication**, like the existing alpha API, and therefore must **not** be exposed beyond a trusted loopback backend. The Minecraft server checks ownership, but direct local HTTP calls bypass that in-game check. Never port-forward the Python HTTP service to the public internet. For multiplayer hosting, add authentication and server-to-server trust before opening access.

## Development caveats

- Forge Java source has not been compiled in the provided build environment (ForgeGradle network dependencies unavailable). Run `-Task build` on Windows before using a real save. Python tests are verified separately. If Build fails, capture `* What went wrong:` and Java `error:` lines.
- Use a test world or take a world backup first. World Save contains NPC entities and items; SQLite contains memories separately. Both are required to restore the entire experience.
- Neither permanent autonomous AI nor fully human-player abilities are implemented. Multi-instance concurrency, automatic summaries, sentiment model, combat and TaCZ remain future features.
- All character packs are English-first; Thai language support can be added later.
