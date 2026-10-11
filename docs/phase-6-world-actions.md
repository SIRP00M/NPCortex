# Phase 6 — World Awareness & Safe Actions (v0.4.0-alpha)

## Purpose

NPCs perceive a **server-generated snapshot** of their surroundings when a player talks to them via their right-click Dialogue UI. Qwen 3 8B uses that context to reply in character and propose one safe action. The **Minecraft server** is the only component authorized to execute actions. This is a request-driven prototype, **not** continuous autonomous AI.

## Architecture

```text
Right-click NPC → Dialogue UI → client packet
        → Minecraft server (validates NPC and distance)
        → WorldObservation.capture() on game thread
        → POST /v1/agent {character_id,player_id,session_id,message,world}
        → Python validates/filters world → Ollama Qwen JSON-schema mode
        → Python validates reply + action enum
        → Minecraft game thread validates owner, distance, entity UUID, enum
        → WorldActions.execute() → UI reply + authoritative action status
```

World observations are always a bounded one-shot snapshot and include: dimension, biome, time (day/night), weather, player held item/health/distance, NPC health/follow status, three nearby blocks, up to 12 living entities in approximately 12 blocks (type, threat category, distance). **No world-coordinate mapping, no inventory search, no vision from camera, and no permanent world scanning.** Living-entity metadata uses registry IDs, never arbitrary player names.

Allowed actions (all are allowlisted in Python **and** in Forge):

| Action | Trigger | Server operation |
|---|---|---|
| `none` | normal conversation | no world change |
| `follow` | player explicitly requests follow | `setFollowing(true)` (existing Phase 4 goal) |
| `stay` | player explicitly requests stay/stop | `setFollowing(false)` |
| `look_at_player` | player explicitly requests look | set temporary 100-tick look control (~5 s) |

**Security:** Actions only run for the NPC owner within 8 blocks in the same dimension, against the exact original NPC UUID. Unknown actions are rejected. The model cannot command Minecraft to execute arbitrary code, give items, delete blocks, teleport, attack, mine or craft. If an action is rejected or the NPC despawns before the reply, the UI reports it instead of falsely claiming success. A successful action yields `[Action confirmed: ...]`. A model's narrative reply remains AI-generated and **is not proof that unrelated events occurred**.

The dialogue packet's existing per-player in-flight limit and cooldown continue to apply. Backend remains local unauthenticated HTTP; bind only on loopback / a trusted host. Game thread never waits for AI HTTP. While Ollama is thinking, the captured world snapshot might change; the server re-checks action preconditions when the response arrives.

## Installation in Windows PowerShell

Back up/commit your tested Phase 5 tree first, and close Minecraft and Python Backend:

```powershell
cd F:\AI\NPCortex
git status
# Download NPCortex_Phase6_Update_Only.zip to Downloads, then:
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_Phase6_Update_Only.zip" -DestinationPath 'F:\AI\NPCortex' -Force
```

If PowerShell blocks scripts, **only if you trust the checked-in script**, use once for your user account:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

With the backend in one terminal:

```powershell
cd F:\AI\NPCortex
.\scripts\dev-backend.ps1
```

In another terminal:

```powershell
cd F:\AI\NPCortex
.\scripts\phase6-check.ps1
.\scripts\test-agent.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The `test-agent.ps1` script uses **fake test-world data** to validate the API only; it does not operate Minecraft. To test actual observations and actions, open a **fresh test world (Allow Cheats ON)**, then:

```text
/npcortex spawn yui
/npcortex observe
/npcortex actions
```

Right-click Yui and try the following **separately**:

- `What can you see nearby?` — should describe actual nearby objects, selecting `none`.
- `Please stay right here.` — should show `[Action confirmed: staying here.]` and stop following.
- `Please follow me.` — should show `[Action confirmed: following owner.]` and follow if pathfinding works.
- `Please look at me.` — should orient head briefly and confirm it.
- `Can you mine diamonds?` — should admit mining is not implemented. Action is `none`.

Use an actual Creeper **safely in Creative mode**, or test at distance, to verify observation context. Note `/npcortex observe` lists the *five nearest* of up to *12 collected* living entities.

## API

Existing `POST /v1/chat` remains backwards-compatible (old command `/npcortex ask`).

New request for game entities (abbreviated):

```json
{
  "character_id": "yui", "player_id": "<uuid>", "session_id": "<npc-uuid>",
  "message": "Watch out, what's behind us?",
  "world": {
    "dimension": "minecraft:overworld", "biome": "minecraft:plains",
    "time": "night", "weather": "clear",
    "player": {"distance": 3, "health": 20, "holding": "minecraft:torch"},
    "npc": {"following": true, "health": 20},
    "blocks": {"below": "minecraft:grass_block", "feet": "minecraft:air", "ahead": "minecraft:air"},
    "nearby": [{"kind": "hostile", "type": "minecraft:creeper", "distance": 5}]
  }
}
```

The successful **proposal** returned by the backend has:

```json
{"character_id":"yui","character_name":"Yui","reply":"There's a Creeper nearby!","action":"none","model":"qwen3:8b"}
```

Backend **does not** execute the proposal: the Forge server does.

### Limitations / next steps

- Only chat-triggered one-shot awareness, no background sensory loop or autonomous threat warnings.
- Natural-language action selection is imperfect even with a JSON schema. Watch confirmed action messages; the model may sometimes choose `none`.
- No weapon/combat, picking up items, mining/crafting, advanced pathfinding, saveable long-term conversational memory or multi-machine authentication.
- Standard chat screen still works; `/npcortex ask` is legacy text-chat only and does not collect an NPC-world snapshot.
- Python tests pass locally; Java ForgeGradle build and actual Minecraft behavior need verification on the developer's Windows installation.
