<div align="center">

# NPCortex

### An Open-Source AI NPC Framework for Minecraft

**Bring intelligence, personality, memory, and interaction to your Minecraft companions.**

**Minecraft 1.20.1** · **Forge 47.4.10** · **Java 17** · **Python 3.10+** · **Ollama / Qwen 3 8B**

**v0.8.0-alpha — Advanced World Interaction (Experimental)**

[Features](#features) · [Quick Start](#quick-start) · [Custom NPCs](#create-your-own-npc) · [Actions](#supported-actions) · [Architecture](#architecture) · [Documentation](#documentation)

</div>

> [!IMPORTANT]
> **Alpha software.** NPCortex is an experimental framework, not a finished autonomous player. Back up Minecraft worlds and NPC memory before upgrading. The current development workflow is Windows PowerShell and a local AI backend.

## What is NPCortex?

NPCortex is a modular, open-source Minecraft Forge mod plus a Python AI backend. It lets you create companions with their own **character profiles, local skins, dialogue, persistent memories, and server-validated actions**.

The goal is to give modpack creators and developers a reusable foundation for custom NPCs, rather than locking characters or AI models behind a particular hosted service. The default setup uses **Ollama with Qwen 3 8B locally**, with **no separate cloud API subscription required**. Your computer supplies the processing power.

Character packs define *who the NPC is*. The AI helps interpret *what the player wants*. Minecraft server code decides *what the NPC is actually allowed to do*.

## Features

| System | Current capability |
| --- | --- |
| **AI conversations** | English-first, personality-driven dialogue through Ollama + Qwen 3 8B |
| **Spawnable companions** | NPC entities with click-to-chat, Follow / Stay, and basic navigation |
| **Custom character packs** | JSON names, prompts, languages, and wide/slim skin configuration |
| **In-game manager** | ESC menu for selecting NPCs, importing/reloading PNG skins, and managing memory |
| **World awareness** | Dialogue-time observations of nearby entities, blocks, player status, and environment |
| **Natural-language commands** | Common direct intents plus AI interpretation of other allowed requests |
| **Multi-step action plans** | Up to **8 sequential, validated steps** per request, with failure handling |
| **Inventory & equipment** | Small NPC inventory, item pickup/transfer, equip, and drop operations |
| **Persistent memory** | SQLite conversation history and player-written memory notes, scoped to player and NPC |
| **Relationship activity** | Simple friendship/activity score, not a simulated emotional relationship |
| **Action dialogue** | Character-specific reactions generated after Minecraft reports action outcomes |
| **Autonomous awareness** | Opt-in threat detection, bounded retreat while following, and a single Qwen in-character warning (system fallback only if AI unavailable) |
| **Per-NPC autonomy management** | Enable/disable by Character ID or UUID, or choose owned NPCs through ESC → NPCortex Manager; server checks ownership |
| **Bounded world interactions (experimental)** | Opt-in singleplayer mining/placement of safe blocks and bag-only crafting of four supported recipes; steps work with the existing 8-step plan runner |

A companion can respond to a request such as:

> **Player:** Yui, pick up that iron sword, equip it, and follow me.
>
> **NPCortex:** Validates and attempts **pickup → equip → follow** in order. Each step is checked by Minecraft; failed steps stop the plan. Yui can then respond in her own voice based on the actual results.

This does **not** mean the NPC can automatically fight with that sword. Combat and more advanced activities need separate behavior implementations.

## v0.8.0-alpha — Advanced World Interaction

This version introduces **three server-authoritative actions** — `mine_nearby`, `craft_item`, and `place_block` — that can be chained with existing actions such as `pickup_nearby`. Example: mine an oak log → collect its drop → craft oak planks → place a block directly in front of the NPC. The server checks ownership, distance, inventory, resource IDs, and execution results before continuing the plan.

**Important:** breaking and placing blocks are **disabled by default**, require an explicit TOML opt-in, singleplayer with cheats, and a small block allowlist. Crafting currently supports only oak planks, sticks, crafting tables and torches. This is **not** arbitrary mining, modded crafting or intelligent house construction. See the [v0.8.0 world interaction guide](docs/world-interactions-v0.8.md) for exact recipes, safety constraints, commands and setup.

Use `/npcortex world status`, `/npcortex world mine minecraft:oak_log`, `/npcortex world craft minecraft:oak_planks`, and `/npcortex world place minecraft:oak_planks` to test mechanics without Qwen first.

## Phase 8 — Autonomous Behaviors

Autonomous sensing is **off by default**. From a cheats-enabled test world, stand within 12 blocks of your owned NPC and use:

```mcfunction
/npcortex auto on
/npcortex auto status
/npcortex auto off
```

Specify any loaded owned NPC without standing next to it:

```mcfunction
/npcortex auto list
/npcortex auto on yui
/npcortex auto off ai
/npcortex auto status yui
```

For multiple NPCs with the same ID, use the full UUID shown by `auto list`. Alternatively, open **ESC → NPCortex Manager → Manage NPC Autonomy** and choose the intended NPC. The GUI lists **loaded owned NPCs** and checks permission server-side. See [Autonomy Manager](docs/phase-8-autonomy-manager.md).

When enabled, an NPC near its online owner periodically checks for nearby visible hostile mobs, generates one in-character owner warning via Qwen (with optional system fallback if AI unavailable), and may *attempt* to retreat if it is following and a monster gets too close. If the NPC has been told to **Stay**, it can still warn but will not initiate autonomous movement. Explicit multi-step plans are not interrupted. Exactly one owner-facing warning is shown for each event: normally the Qwen reply; the plain system text appears only if AI speech is disabled/unavailable and the fallback setting is enabled. No duplicate messages are shown. AI inference is rate-limited, not run every tick.

See the [Phase 8 autonomous behavior guide](docs/phase-8-autonomous-behaviors.md) for testing, settings, and current limitations. This is **reactive safety behavior** — not general autonomous mining, combat, building, or AI-initiated task planning.

## Quick Start

### Requirements

- **Minecraft Java Edition 1.20.1** and **Minecraft Forge 47.4.10**.
- **JDK 17** for building/running the Forge development client.
- **Python 3.10+**.
- **Ollama**, with sufficient system RAM and GPU/CPU resources to run the selected model.
- Windows PowerShell for the included development scripts. First-time Forge dependency downloads require internet access.

### 1. Download the model

Install Ollama from [ollama.com](https://ollama.com/download) and run:

```powershell
ollama pull qwen3:8b
ollama list
```

If you keep AI models on another drive, configure Ollama's `OLLAMA_MODELS` environment variable separately. The program install directory and model-storage directory are different settings.

### 2. Start the NPCortex backend

Clone this repository or open your existing checkout, then run from the **repository root**:

```powershell
.\scripts\dev-backend.ps1
```

Keep this terminal open. In another terminal, check the backend:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
```

An HTTP 200 means the backend is reachable; it does **not** by itself prove the Ollama model can generate a response.

> [!NOTE]
> If Windows blocks trusted `.ps1` scripts, you may set `Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned`. Review scripts before running them. A higher-priority organizational policy may override this setting.

### 3. Build and launch Forge

Open **another PowerShell window** in the repository root:

```powershell
java -version
javac -version
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The build script checks Java 17 and bootstraps the Forge MDK Gradle wrapper when missing. The development client has its own game directory under `forge-mod/run`.

For the first in-game test, create a separate **Singleplayer world with Allow Cheats enabled** and keep the Python backend running.

### 4. Spawn and talk to your NPC

```text
/npcortex status
/npcortex list
/npcortex spawn yui
```

**Right-click Yui** to open the conversation screen. Try:

```text
Yui, follow me!
Yui, wait for me here.
Yui, pick up that emerald and give it to me.
```

Actions depend on the NPC's proximity, game state, available items, and server-side validation. Requests may fail safely rather than pretend an action succeeded.

## In-Game NPC Manager

Open **ESC → NPCortex Manager** in the development client.

- Choose a nearby owned NPC.
- Import a **64×64 PNG Minecraft skin** (wide or slim), or open the active game-instance skin folder.
- Reload a skin after replacing its PNG file; apply the skin ID/model to an NPC.
- Open **Memory Management** to view, add, delete, or clear memory notes and inspect the friendship activity score.
- Clear an NPC's chat history if previous dialogue keeps reinforcing a mistaken identity. This does not delete saved notes or reset friendship.

Character JSON editing is currently done in the character-pack files, **not** in the manager's UI. Skin files are local to each Minecraft client installation.

## Create Your Own NPC

NPCortex is designed so you do **not** need to write Java just to add another character.

1. Copy `character-packs/template/` (or an example such as `character-packs/yui/`) into `character-packs/my_npc/`.
2. Add/edit `character.json` and provide `skin.png` if you want a custom skin.
3. Restart the backend if needed for your changes; in the development client, install or import the skin and use `/npcortex sync` to refresh existing NPC metadata.
4. Spawn the new character with `/npcortex spawn my_npc`.

Example `character-packs/my_npc/character.json`:

```json
{
  "schema_version": 2,
  "id": "my_npc",
  "name": "Forest Scout",
  "language": "en",
  "system_prompt": "You are Forest Scout, a friendly Minecraft explorer. Speak natural English. Stay in character. Never claim an in-game action succeeded unless Minecraft confirms it.",
  "appearance": {
    "skin": "my_npc",
    "model": "slim"
  }
}
```

To copy the pack's PNG into the **development client**:

```powershell
.\scripts\install-character.ps1 -Id my_npc
```

For a different Minecraft instance (for example a CurseForge profile), specify its game directory:

```powershell
.\scripts\install-character.ps1 -Id my_npc -GameDir "F:\Minecraft\Instances\MyModpack"
```

The in-game manager can also import skin PNGs. **Adding a JSON field does not automatically create a new gameplay ability.** For the exact file schema and loading behavior, read [Character Packs](docs/phase-5-character-skins.md).

## Supported Actions

NPCortex currently supports these **server-side action types**:

| Group | Action IDs |
| --- | --- |
| Companion control | `follow`, `stay`, `look_at_player` |
| Movement | `move_to_player`, `wander`, `jump` |
| Inventory & equipment | `pickup_nearby`, `take_offered_item`, `give_item_to_owner`, `equip_item`, `drop_held_item`, `drop_stored_item` |

`none` is a no-action response for normal dialogue. The exact allowlist is implemented by `ActionCatalog.java` and the backend action schema.

### How multi-step execution works

```text
Player request
    |
    v
Natural-language intent / Qwen action plan
    |
    v
Validated action list (maximum 8 steps)
    |
    v
Minecraft server executes step 1
    |
    +-- failed / blocked / timed out --> STOP + report failure
    |
    v
Verify result --> step 2 --> ... --> complete
    |
    v
NPC reaction grounded in confirmed game results
```

Direct commands such as **“Wait for me here”** are interpreted without waiting for the LLM when recognized. More complex requests use the AI planner, but **Minecraft validates each action**. Movement uses vanilla pathfinding and can fail. No arbitrary Java, Minecraft console commands, or filesystem operations can be executed by the model.

To extend the framework with a new action, update the backend's action schema, register it in `ActionCatalog.java`, implement the server-side executor in `WorldActions.java`, and add completion handling in `ActionPlanRunner.java` for long-running actions. See [Generic Multi-Step Planner](docs/generic-action-planner-v0.5.4.md).

## Persistent Memory & Identity

The backend stores chat history and explicit notes in **SQLite**, separated by **player identity, NPC session UUID, and character ID**. The default database is:

```text
backend/data/npcortex.sqlite3
```

Example **player-written** note:

> My name is Kenji.

This note is attributed to the **player**, not to Yui. The NPC's own identity comes from its Character Pack. The prompt includes attribution guidance, but an LLM can still misunderstand context; the manager's **Clear Chat** option can help remove reinforcing mistakes without wiping notes.

Friendship is currently an **interaction-based activity score**, not a full emotional or relationship simulation. Memory is local to the configured backend; it is not automatically synchronized across computers.

Back up SQLite safely with:

```powershell
.\scripts\backup-memory.ps1
```

Save your Minecraft world separately: NPC entity inventories and world state are **not** stored inside the SQLite memory database.

## Developer Commands

All `/npcortex` developer commands currently require operator permission / cheats enabled.

| Command | Purpose |
| --- | --- |
| `/npcortex status` | Check backend reachability |
| `/npcortex list` | List available Character Packs |
| `/npcortex spawn yui` | Spawn an owned companion |
| `/npcortex sync` | Refresh nearby owned NPC metadata |
| `/npcortex follow` / `/npcortex stay` | Direct companion behavior controls |
| `/npcortex observe` | Inspect the NPC's world snapshot |
| `/npcortex actions` | Show server-supported action IDs |
| `/npcortex inventory` | Inspect stored and held items |
| `/npcortex item pickup minecraft:emerald` | Debug nearby item pickup |
| `/npcortex item drop minecraft:emerald` | Debug item drop from NPC storage |
| `/npcortex item give minecraft:emerald` | Debug transfer to owner |
| `/npcortex ask yui Hello!` | Text-only AI chat test |

Commands act on the relevant nearby **owned** companion when applicable. Debug commands are intended for development rather than end-user gameplay UI.

## Architecture

```text
Minecraft Forge 1.20.1 (Java)
  |-- Companion Entity & Navigation
  |-- World Observation & Action Validation
  |-- In-game Manager / Dialogue UI
  |-- Inventory / Save Data
  |
  +-- Local HTTP --> Python Backend
                       |-- Character Pack Loader
                       |-- AI Intent & Action Planning
                       |-- SQLite Memory & Activity Score
                       |
                       +-- Ollama API --> Qwen 3 8B
```

The game server is authoritative about **world state and action success**. The model supplies dialogue and **proposals**, not unrestricted gameplay control. In the default setup, all AI processing is local.

## Repository Structure

```text
NPCortex/
├── backend/              # Python HTTP service, LLM adapter, agent, memory, tests
├── forge-mod/            # Forge Java mod, NPC entities, UI, actions, network
├── character-packs/      # JSON profiles and optional example PNG skins
├── integrations/         # Reserved extension points; no active TaCZ integration
├── docs/                 # Architecture, APIs, guides, roadmap, history
├── scripts/              # Windows setup, tests, build, backups
├── tests/                # Standalone Java checks
├── .github/workflows/    # CI workflows
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

## Configuration & Privacy

The backend's default local endpoints are:

- NPCortex: `http://127.0.0.1:8765`
- Ollama: `http://127.0.0.1:11434`

See [`backend/.env.example`](backend/.env.example) for environment variable names, including `NPCORTEX_MODEL`, `NPCORTEX_OLLAMA_URL`, and `NPCORTEX_DATA_DIR`. **The example file is documentation, not an automatically loaded `.env` file**: set the appropriate OS environment variables yourself.

> [!CAUTION]
> The alpha backend **has no HTTP authentication**. It must remain bound to loopback (`127.0.0.1`), not exposed publicly or port-forwarded. On a dedicated server, `localhost` refers to that server host, not the player's computer. Do not upload `backend/data`, game saves, personal notes, or API secrets to Git.

## Current Limitations & Roadmap

**Not implemented as complete player-like capabilities:** combat AI, attacking with equipped swords, mining, crafting, building, opening containers, autonomous background planning, target-specific long-distance exploration, TaCZ weapon integration, and production-ready multiplayer authentication.

**Planned directions:** autonomous event-driven awareness, more validated action executors, equipment/combat behaviors, optional mod adapters, better pathfinding and recovery, multi-provider LLM support, and Thai dialogue/localization.

Read [Roadmap](docs/roadmap.md) and [Architecture](docs/architecture.md). This is an evolving **alpha release**, so behavior and configuration may change.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Backend unreachable | Run `dev-backend.ps1`; check `http://127.0.0.1:8765/health` |
| Backend online, no AI reply | Check `ollama list`, Ollama logs, and that `qwen3:8b` is available |
| NPC skin not showing | Import a 64×64 PNG into the **active** game instance via ESC Manager, then reload it |
| NPC says an item is missing | Use `/npcortex inventory`; item in main hand is separate from stored bag items |
| NPC won't follow or stay | Try the direct `/npcortex follow` / `/npcortex stay` command, then inspect `/npcortex observe` |
| Plan stops | Check the first failed step, item availability, NPC proximity, and pathfinding |
| Memory appears missing | Verify the same backend database, player identity, and NPC session are being used |

For debugging, use a test world, include the relevant backend error and Forge log output, and avoid sharing private memory data.

## Documentation

- [Development & Setup](docs/development.md)
- [API Reference](docs/api.md)
- [Character Packs & Skins](docs/phase-5-character-skins.md)
- [In-Game Manager](docs/in-game-manager.md)
- [Persistent Memory & Inventory](docs/phase-7-memory-actions.md)
- [Generic Multi-Step Actions](docs/generic-action-planner-v0.5.4.md)
- [Natural Language Intents](docs/natural-intent-hotfix-v0.5.6.md)
- [Action Dialogue](docs/action-dialogue-v0.5.7.md)
- [Contributing](CONTRIBUTING.md)

## Contributing

Contributions, fixes, character pack examples, and documentation improvements are welcome. Please open an issue or pull request with clear reproduction steps and mention the Minecraft/Forge version used. For new actions, include both backend validation and Minecraft server-side checks.

## License

Original NPCortex code is provided under the [MIT License](LICENSE). Third-party libraries, models, Minecraft assets, and independently contributed character skins may have separate licenses; review them before redistributing.

NPCortex is a community-created project and is not affiliated with Mojang, Microsoft, Ollama, or the Qwen developers.
