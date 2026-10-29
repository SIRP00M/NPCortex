# NPCortex

**Open-source, local-AI NPC companions for Minecraft Java 1.20.1 (Forge).** Create character profiles, chat through an optional local Ollama/Qwen backend, and experiment with increasingly player-like NPCs.

[What's new](docs/patch-notes/v0.3.0-alpha.1.md) · [Changelog](CHANGELOG.md) · [Character Profiles](docs/character-profiles.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development milestone: `v0.3.0-alpha.1` (29 October 2026).** This is experimental source, not a certified multiplayer release. Features below range from real Forge GameTests to operations still awaiting hands-on Minecraft acceptance.

## What NPCortex currently offers

| Area | Current capabilities | Status |
|---|---|---|
| **NPC identity and characters** | Player-based Forge avatars, inventory/lifecycle and per-NPC Character Profiles; in-game **ESC → NPCortex Manager** for creating and editing profiles | Experimental; Windows single-player smoke tests reported for selected flows |
| **Local AI conversations** | Optional Python backend with Ollama + Qwen 3 8B and durable actor/conversation data | Runs locally; requires separate backend and model |
| **Movement and following** | Loaded-terrain bounded path planning, waypoint recovery, long-range legs and persistent player following | Partial; no unrestricted pathfinding across unknown chunks |
| **Vanilla-style presence and survival** | Survival ticks, damage, hunger, touch pickup, player selection/registry, chunk tracking, statistics and advancements | New in 0.3 alpha; GameTest evidence, Owner real-game parity pending |
| **Player actions** | Operator-triggered attack, item use, sleep and limited container interactions | Not AI/ledger dispatched yet |
| **Guarded world effects** | Explicit native block breaking/pickup and restricted block placement/use with preview, confirmation and durable receipts | Experimental; not automatic mining/crafting/collecting |

## Requirements

- Minecraft **1.20.1** and Forge **47.4.10**.
- **Java 17** for Forge development and **Python 3.10+** for the backend.
- For local chat: [Ollama](https://ollama.com/) with `qwen3:8b`. The default setup does **not** require a paid cloud AI API.
- Windows PowerShell for the included developer and acceptance scripts.

## Quick start on Windows

From the repository root:

```powershell
ollama pull qwen3:8b
.\scripts\dev-backend.ps1
```

In a second terminal:

```powershell
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The backend normally listens on `127.0.0.1:8765`; `/health` confirms backend availability only, not the Ollama model. Create and edit profiles in the Minecraft Pause/ESC menu under **NPCortex Manager**. See [development setup](docs/development.md), [API reference](docs/api.md), and [Character Profiles](docs/character-profiles.md).

### Existing database migration

The Character Profile backend uses SQLite schema v5. An existing v1–v4 database requires a verified backup and explicit `NPCORTEX_ALLOW_SCHEMA_V5=1` opt-in; do not perform upgrades directly on irreplaceable data. Never commit credentials, worlds or SQLite player data.

## New in v0.3 — Vanilla-inspired player behavior

Instead of only simulating appearance and navigation, experimental NPC avatars now execute more native player rules: survival/environmental effects, item consumption, inventory pickup, statistics/advancements and controlled PlayerList presence with section-based chunk tracking. Server player selectors such as `@a` may now include NPCs. This also means registered NPCs can affect `max-players`, third-party login hooks and simulation costs.

A server-side operator command can exercise a bounded subset of attacks, item use, sleeping and containers. These operations are **not yet autonomous AI actions** or fully integrated with durable effect journals.

Guarded Place/Use experiments are also present, alongside the earlier Break/Pickup workflows. Placement is constrained by validated permissions, targets and item usage; automated walking toward arbitrary tasks and unrestricted world modification are not implemented.

## Testing and compatibility

- [Player-parity gameplay campaign](docs/parity/PARITY_OWNER_GUIDE.md) — follow the T01–T25 checks in a **disposable** world.
- [Player-action acceptance](docs/pap/PAP_ACCEPTANCE_WINDOWS.md) — verify restricted placement/use, inventory and claim behavior.
- [Native block break/pickup](docs/stage4/NATIVE_BREAK_PICKUP.md) — explicit preview and confirm, not free-roaming automatic collection.
- [G5 long-range navigation](docs/g5-test-harness/README.md) — functional and failure/recovery tests; performance acceptance separate.

Run backend tests:

```powershell
$env:PYTHONPATH = 'backend/src'
python -m unittest discover -s backend/tests -v
```

Linux CI runs the backend suite, skipping only Windows PowerShell wrapper tests. Separate Windows CI runs those safety tests. Forge `build` executes Java self-tests, but **neither offline CI nor GameTests alone proves live human-player parity**.

## Known limitations

Portals, parts of death/respawn and advancement listener lifecycles are not fully player-equivalent; real Save/Quit and dedicated-server cases still need more evidence. TAB names may expose internal profile names. Player-style chunk rings may be expensive for widely separated NPCs. Task-to-approach walking, item-level claim granularity, cross-mod compatibility and exactly-once crash-window world effects are not yet certified.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). This alpha source is open for review, testing and bug reports. Do not use development datapacks on valuable worlds; acceptance datapacks may modify or clear nearby entities and blocks.
