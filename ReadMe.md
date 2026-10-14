> **Development snapshot: Architecture Stabilization Sprint 1** — Registry v2, lifecycle, failure memory and experimental 3D access. See [Thai notes and validation limits](docs/runtime-sprint1/README_TH.md). Forge build and in-game Fancy Oak acceptance are still pending.

> **Vanilla Trees Foundation 1 (development patch):** Added Vanilla 1.20.1 overworld wood registry, bounded branched/2x2 tree recognition, and species-matched leaf observations. **Not all trees are harvestable yet:** high branches, huge trees, Nether fungi, and complex navigation still need testing and new access skills. See [release experiment](docs/releases/v0.8.2-alpha-general-trees-foundation1.md).

<div align="center">

# NPCortex

### An Open-Source AI NPC Framework for Minecraft

**Bring intelligence, personality, memory, and interaction to your Minecraft companions.**

**Minecraft 1.20.1** · **Forge 47.4.10** · **Java 17** · **Python 3.10+** · **Ollama / Qwen 3 8B**

**v0.8.2-alpha — Autonomous World Interaction & Whole-Tree Harvesting (Experimental)**

[Features](#features) · [Quick Start](#quick-start) · [Custom NPCs](#create-your-own-npc) · [Actions](#supported-actions) · [Architecture](#architecture) · [Documentation](#documentation)

</div>

> [!IMPORTANT]
> **Alpha software.** NPCortex is an experimental framework, not a finished autonomous player. Back up Minecraft worlds and NPC memory before upgrading. The current development workflow is Windows PowerShell and a local AI backend.

## What is NPCortex?

NPCortex is a modular, open-source Minecraft Forge mod plus a Python AI backend. It lets you create companions with their own **character profiles, local skins, dialogue, persistent memories, and server-validated actions**.

The goal is to give modpack creators and developers a reusable foundation for custom NPCs, rather than locking characters or AI models behind a particular hosted service. The default setup uses **Ollama with Qwen 3 8B locally**, with **no separate cloud API subscription required**. Your computer supplies the processing power.

Character packs define *who an NPC is*. The **Qwen AI Brain** can decide *which validated skill to use next* for a bounded autonomous gathering task. The Minecraft server retains authority over **ownership, reach, block/item safety, navigation, inventory, and completion verification**. The existing deterministic 8-step action runner and other companion functions remain available.

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
| **Bounded world interactions (experimental)** | Singleplayer-with-cheats mining/placement of safe blocks and bag-only crafting of four supported recipes; steps work with the existing 8-step plan runner |
| **Agentic AI Brain (v0.8.2-alpha pilot)** | Qwen chooses grounded skills for bounded `minecraft:oak_log` tasks using server observations, tool feedback, and replanning budgets |
| **Legacy gathering & task control** | Existing heuristic task flows remain compatible; task status, cancel/resume, and item storage use server-owned state |

A companion can respond to a request such as:

> **Player:** Yui, pick up that iron sword, equip it, and follow me.
>
> **NPCortex:** Validates and attempts **pickup → equip → follow** in order. Each step is checked by Minecraft; failed steps stop the plan. Yui can then respond in her own voice based on the actual results.

This does **not** mean the NPC can automatically fight with that sword. Combat and more advanced activities need separate behavior implementations.

**Agent Recovery Fix 2 (experimental):** Current-ray leaf obstruction and per-skill grounded target lists reduce wasteful `clear_leaf` requests when remaining logs are already visible or require a different approach. No Forge/In-Game verification has been completed for this patch; see [Leaf Recovery Fix 2](docs/releases/v0.8.2-alpha-leaf-recovery-fix2.md).

## v0.8.2-alpha — Whole-Tree Harvesting Pilot (Experimental)

> **Not yet Minecraft-verified.** This release changes the pilot from "stop when
> enough logs are mined" to "finish each natural oak tree already started,
> collect all mined log drops, and leave the harvested logs in the NPC bag."
> Real in-game pathfinding, foliage recovery and full-tree harvesting must be
> tested before calling this feature reliable.

- **Requested count is a minimum.** If asked for 6 logs, the agent can harvest
  all 8 logs of a committed oak tree. If the first full tree yielded 4, it can
  select another natural tree and harvest all of that one too. Never transfer
  items to the player without a separate order.
- The server records all logs of a single conservative **natural-oak candidate**
  *before* breaking its first log, and rejects ambiguous, very large, joined,
  partly unloaded, or player-build-looking clusters. Minecraft offers no
  infallible natural-vs-placed block provenance.
- Qwen still chooses which tree/log/skill to attempt, rather than a scripted
  cut/approach sequence. It can select **approach**, **mine**, **clear_leaf**,
  limited **clear_soft** (one dirt/grass obstruction on a ray), **climb**,
  **approach_drop**, **restore**, **descend**, **wait**, or **pause**.
- Minecraft-side close-range pickup automatically inserts matching mined
  oak log drops into the NPC bag (no per-item model call). Other items are not
  auto-picked. This version does not add a visual suction animation.
- A task cannot finish until a verified tree is complete, the required minimum
  is reached, *all mined logs are accounted for in the NPC bag*, and temporary
  scaffolds / recorded soil edits have been cleaned up. Paused/incomplete tasks
  must never be described as successful.
- Maximum 32 logs per committed tree, 64 mined logs across up to 4 trees,
  2 recorded reversible dirt edits, and 80 total Qwen decisions per task.
  These are bounded safety caps, **not** guarantees of completion.
- **Test with an actual natural Oak tree.** A single manually placed Oak Log,
  decorated log beam, or wood in a building is not accepted as a verified tree.
  Use the legacy world-action features if you wish to manipulate such blocks.

Run `./scripts/test-v082.ps1` for Python and standalone Java tests; then
`./scripts/build-agentic-v085.ps1 -SkipTests` for Forge (old script names retained
for compatibility). See [v0.8.2 release notes](docs/releases/v0.8.2-alpha.md).

## Historical v0.8.1-alpha — Agentic AI Brain (Oak Log Pilot)

This release integrates the development snapshots previously labeled **v0.8.4–v0.8.5.5** into the public **v0.8.1-alpha** milestone, building on the repository's v0.8.0 codebase. Historical prototype documentation retains its original filenames; those are **not** separate published releases.

**Design:** `Observe → Qwen reason/choose skill → Java validate → Execute → Verify → Replan`. The model makes bounded *decisions*, not per-tick movement, raw console commands, arbitrary Java execution, or unchecked changes to Minecraft worlds.

| Responsibility | Component |
| --- | --- |
| Reasoning / skill selection | Local **Ollama + Qwen 3 8B**, through `/v1/brain/step` |
| Grounded observations | Server snapshots of candidate blocks, drops, reachability, inventory, and previous action feedback |
| Movement & physical actions | Java-side pathfinding and validated `approach`, `mine`, `clear_leaf`, `climb`, `approach_drop`, `pickup`, `descend`, `wait`, `pause`, `finish` |
| Authority & safety | Minecraft server verifies ownership, game permissions, world edits, collision, reach, resources, and action results |
| Recovery | Request budgets, failure / no-progress watchdogs, bounded retries, task pause / resume / cancel |
| Completion | **Counted oak logs must be verified in the NPC bag**; no automatic transfer to player |

### Quick agentic test (experimental)

Use a **backed-up singleplayer test world**, enable cheats and permitted world editing, and place one accessible Oak Log near an owned NPC. Keep the player far enough from the drop to avoid collecting it themselves.

```mcfunction
/npcortex spawn yui
/npcortex task gather minecraft:oak_log 1
/npcortex task status
/npcortex inventory
```

For a paused task, use `/npcortex task resume`; cancel with `/npcortex task cancel`. **Historical v0.8.1 testing used one standalone Oak Log; the v0.8.2 whole-tree pilot now requires an actual recognizable natural Oak tree.** The `task status` output includes `mined`, `stored`, `chosen`, `last`, `stalls`, `observedDrops`, and `pickupReady` for diagnosing a failed run. The historical pilot accepted **1–16 oak logs**. At v0.8.1, `pickup` was explicit and automatic item pickup was not implemented; **v0.8.2 introduces close-range automatic inventory collection** without a visual magnet animation.

**Observed during manual in-game testing:** a reachable oak log was mined and **1/1** was verified in Yui's NPC bag. **Known failure:** nearby oak leaves / terrain can prevent a safe visible standing position; multi-log tasks may pause after mining a block without collecting or navigating to subsequent targets. Removing obstructing foliage allowed the same area to complete. We have **not** proved whether the obstruction arises from line-of-sight, collision, or both. Do not describe navigation, multi-tree harvesting, automatic leaf clearance, automatic pickup, or generalized autonomy as complete.

**Local test commands** (PowerShell, repository root):

```powershell
.\scripts\test-agentic-v085.ps1
.\scripts\build-agentic-v085.ps1
.\scripts\dev-backend.ps1
# In another terminal:
.\scripts\dev-forge.ps1 -Task runClient
```

The `v085` names are retained for compatibility with the development tooling; **published v0.8.2-alpha metadata uses 0.8.2-alpha**. Unit tests, Forge builds, live Qwen inference, and in-game validation are **different checks**. A successful HTTP 200 is not proof of good tool choice or successful execution.

For source-level technical notes see [Agentic Brain pilot](docs/agentic-brain-v0.8.5.md) and [Navigation / Pickup affordances](docs/agentic-brain-v0.8.5.5.md). For the public milestone summary see [v0.8.1 release notes](docs/releases/v0.8.1-alpha.md).

> **Development history:** The following v0.8.1–v0.8.5 notes describe earlier local prototype stages, not the public release numbering used above.

## Historical Prototype — Smart Tree Harvest & Store-First Gathering

Counted gathering **keeps items in the NPC bag**, never auto-delivers to the owner — even when a task was saved in the older `return` state. Give/drop remains a **separate, explicit request**: `Yui, give me all the oak logs.` Woodcutting now chooses **exact visible log positions**, navigates to safer angles around the tree (including small elevation differences), ranks feasible bottom-up / top-down order, and can clear at most **8 directly obstructing natural oak leaves**. It cannot climb/scaffold or guarantee tall-tree completion. Use a backed-up test world.

Guide: [Smart Tree Harvest v0.8.3](docs/smart-harvest-v0.8.3.md). PowerShell: `./scripts/smart-harvest-check.ps1`, then `./scripts/dev-forge.ps1 -Task build`.

## Historical Prototype — Quantity & Full-Stack Interaction

**New:** Dialogue requests such as `Yui, collect 6 sand blocks and bring them back to me` now use the persistent quantity-controlled Gather Task rather than an AI-proposed **single** mining action. `give me all`, `drop all`, `give me all sand`, and `give me 6 sand blocks` use server-authoritative bulk transfers from the NPC's bag **and main hand**. Feedback shows the actual count and flags partial transfers. The new-config `enable_world_edits` default is `true` for Singleplayer testing; existing TOML is never overwritten. **Back up important worlds before enabling it.**

See [v0.8.2 Quantity Hotfix](docs/quantity-and-stack-hotfix-v0.8.2.md).

## Historical Prototype — Heuristic Intelligent Task Execution

**New:** Give an owned NPC a bounded long-running task, such as **"Yui, collect 10 oak logs and bring them back to me"**. The Minecraft server validates the resource and count, searches nearby *loaded* blocks, navigates using vanilla pathfinding, mines, picks up drops, and returns to the owner to deliver the collected quota. Tasks save their stage and progress with the NPC's world data. Cancelled work stops safely; already acquired items stay in the NPC bag.

```mcfunction
/npcortex task gather minecraft:oak_log 10
/npcortex task gather minecraft:oak_log 10 yui
/npcortex task status yui
/npcortex task cancel yui
/npcortex task resume yui
```

Only five self-dropping building resources are eligible for this prototype. Count 1–16, owner initially within 8 blocks, and owner stays within 32 blocks while the task runs. Tasks require singleplayer+cheats and enabled world edits, do not force-load chunks or teleport, and may pause on unreachable terrain or full inventories. No arbitrary AI-generated coordinates. **Run the Minecraft tests before declaring this feature verified.** See [Intelligent Task Execution Guide](docs/intelligent-task-execution-v0.8.1.md).

## v0.8.0-alpha — Advanced World Interaction

**Reach update:** Minecraft-side `mine_nearby`, `pickup_nearby`, and world observations now use vanilla-like **4.5-block Survival / 5-block Creative** range, based on the owner's game mode. `place_block` chooses the farthest safe, unobstructed position straight ahead within that range. See [Player Reach Hotfix](docs/player-reach-hotfix-v0.8.0.md).

This version introduces **three server-authoritative actions** — `mine_nearby`, `craft_item`, and `place_block` — that can be chained with existing actions such as `pickup_nearby`. Example: mine an oak log → collect its drop → craft oak planks → place a block directly in front of the NPC. The server checks ownership, distance, inventory, resource IDs, and execution results before continuing the plan.

**Historical note (v0.8.0):** breaking and placing blocks were **disabled by default**. As of v0.8.2, the *new-config* default is `true`, but Singleplayer + Cheats + Owner restrictions and the block allowlist remain. Existing TOML values are preserved. Crafting currently supports only oak planks, sticks, crafting tables and torches. This is **not** arbitrary mining, modded crafting or intelligent house construction. See the [v0.8.0 world interaction guide](docs/world-interactions-v0.8.md) for exact recipes, safety constraints, commands and setup.

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

### Historical Prototype — Temporary Scaffold / Agentic Brain (development snapshots)

### v0.8.4-alpha — Tree commitment & temporary scaffold (experimental)

Oak-log gathering now locks one straight 1x1 trunk before moving to another. When logs are too high to reach, NPCortex can attempt a six-block-high *temporary* jump-and-place pillar made from safe full blocks in the NPC's own inventory, then dismantle it top down before finishing or changing trees. Requests to chop/collect **do not automatically give items to the owner**. Unsupported or unsafe conditions pause the task. See [docs/tree-scaffold-v0.8.4.md](docs/tree-scaffold-v0.8.4.md). Minecraft live testing is required.

### v0.8.5-alpha — Agentic AI Brain (oak-log pilot)

**Observe → Qwen reason / select skill → Java validate → Minecraft execute → verify → replan.**
Counted **oak-log** gather orders now use the opt-in-world-edits, server-authoritative,
persistent `AgenticTaskController` instead of the hardcoded gathering heuristic.
Qwen chooses one action at a time; feedback and the fresh world snapshot determine the
next decision. A task is complete only after the **new** required logs are confirmed
in the NPC bag and any task-owned scaffold has been safely removed. Other actions,
character packs, NPC UI and legacy gather flows remain available unchanged.

Full architecture, safety limits, commands, tests and caveats:
[Agentic Brain v0.8.5](docs/agentic-brain-v0.8.5.md).

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

NPCortex supports these **legacy server-side action types** (separate from the bounded AI skill catalog):

| Group | Action IDs |
| --- | --- |
| Companion control | `follow`, `stay`, `look_at_player` |
| Movement | `move_to_player`, `wander`, `jump` |
| Inventory & equipment | `pickup_nearby`, `take_offered_item`, `give_item_to_owner`, `equip_item`, `drop_held_item`, `drop_stored_item` |
| Validated world actions | `mine_nearby`, `craft_item`, `place_block` (singleplayer safeguards apply) |

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
| `/npcortex task gather minecraft:oak_log 1` | Start bounded oak-log agentic pilot |
| `/npcortex task status` | Inspect progress, chosen skill, failures, and item pickup diagnostics |
| `/npcortex task cancel` / `/npcortex task resume` | Manage the active task |
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
└── ReadMe.md
```

## Configuration & Privacy

The backend's default local endpoints are:

- NPCortex: `http://127.0.0.1:8765`
- Ollama: `http://127.0.0.1:11434`

See [`backend/.env.example`](backend/.env.example) for environment variable names, including `NPCORTEX_MODEL`, `NPCORTEX_OLLAMA_URL`, and `NPCORTEX_DATA_DIR`. **The example file is documentation, not an automatically loaded `.env` file**: set the appropriate OS environment variables yourself.

> [!CAUTION]
> The alpha backend **has no HTTP authentication**. It must remain bound to loopback (`127.0.0.1`), not exposed publicly or port-forwarded. On a dedicated server, `localhost` refers to that server host, not the player's computer. Do not upload `backend/data`, game saves, personal notes, or API secrets to Git.

## Current Limitations & Roadmap

**Not implemented as complete player-like capabilities:** robust generalized autonomous mining, reliable navigation through dense leaves/terrain, arbitrary crafting/building, player-like automatic item collection, combat AI, attacking with equipped swords, opening containers, unrestricted world exploration, TaCZ integration, or production-ready multiplayer authentication. **Bounded experimental** mining, placement, simple crafting, and the oak-log agent pilot exist but are not general capabilities.

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

- [v0.8.1-alpha Agentic Brain release notes](docs/releases/v0.8.1-alpha.md)
- [Agentic Brain design and limitations](docs/agentic-brain-v0.8.5.5.md)
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

### Experimental general access/material sprint

This development snapshot includes AI-chosen material acquisition, reversible temporary construction using freshly mined goal logs, and 3D retrieval of stranded drops. These are reusable Skill primitives; ore-mining and structure-building goals still require separate task adapters. The matching Backend and Forge source must be used together. Forge gameplay acceptance remains unverified.
