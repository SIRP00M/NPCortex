# NPCortex

**An open-source, modular framework for local-AI companions in Minecraft Java 1.20.1 (Forge 47.4.10).**

NPCortex brings together custom player-based NPCs, local **Ollama + Qwen** dialogue, terrain-aware movement, Follow Player, and an experimental system for recording and coordinating NPC tasks. It is built for developers and modders who want to expand NPC behavior without depending on a paid cloud AI API.

[Changelog](CHANGELOG.md) · [Latest patch notes](docs/patch-notes/v0.2.4-alpha.2.md) · [Architecture](docs/architecture.md) · [API](docs/api.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development preview: `v0.2.4-alpha.2`.** This is source code for testing and collaboration, **not a stable release**. M04's read-only safety checks are **not** permission to mine, collect items or run autonomous world-changing tasks. Please test on disposable/copy worlds.

## Latest update — security and lifecycle reliability

This update introduces **optional token protection for legacy backend routes** (`NPCORTEX_REQUIRE_TOKEN=1`). It is **off by default** to preserve existing local chat/API clients; `/health` remains available without a token. When enabled, legacy API requests require the shared local credential used by the C+ bridge. The Forge chat and C2 clients now attach that credential when available.

It also tightens cleanup after a failed player-avatar spawn so an unrelated/replacement NPC is not deactivated accidentally, and removes a redundant movement-stop call from the server-shutdown path after the lifecycle coordinator has already cleared C1 motion. These are targeted hardening changes, **not a guarantee of comprehensive shutdown or multiplayer correctness**.

The backend remains intended for **loopback-only use**. Enabling optional legacy authentication does not make it safe to expose publicly. If you enable it, legacy PowerShell or other external API clients need the same local credential; older scripts that do not send it may receive HTTP 403. See the [patch notes](docs/patch-notes/v0.2.4-alpha.2.md).

## What works today

| Area | Present in source | Important boundary |
| --- | --- | --- |
| Local AI conversation | Forge chat bridge, JSON Character Packs, Python backend, Ollama/Qwen | Local model and running backend required |
| Player-based NPC | Player-avatar identity, spawning, equipment/inventory, lifecycle and combat experiments | Multiplayer and mod compatibility are not fully certified |
| Navigation and Follow | Bounded A* / region planning, terrain-aware movement, obstacle replanning, player following | Loaded-world constraints and runtime safety guards apply |
| Persistent task records | Read-only task graph, task queue/leases, guarded movement admission and terminal diagnostics | Never assume an uncertain action completed; some recovery scenarios still require operator review |
| M03 reliability tools | Transport/outbox diagnostics, controlled fault tests and opt-in Minecraft acceptance harnesses | Development/operator tooling, not everyday gameplay |
| M04 foundation | Typed sequential work contracts, durable metadata and read-only task/target previews, inventory unequip handling | **No automatic mining, block mutation or item-pickup executor** |

**Verification is scope-specific:** on October 26, 2026, the project owner confirmed four Windows single-player G4 scenarios (spawn/motion, save/rejoin, shutdown while a C2 goal was active, Follow start/stop). These do **not** establish dedicated-server, multiplayer, wrong-thread fault-injection, comprehensive recovery or 1/3/5/10 NPC performance acceptance.

## Requirements

- Minecraft **1.20.1**, Forge **47.4.10**, **JDK 17** for Forge
- **Python 3.10+** (see `backend/pyproject.toml`)
- [Ollama](https://ollama.com/) with local `qwen3:8b` for model-powered dialogue
- Windows PowerShell for included developer scripts; the Gradle wrapper is under `forge-mod/`

## Getting started (Windows PowerShell)

```powershell
ollama pull qwen3:8b
.\scripts\dev-backend.ps1
```

In another PowerShell terminal, build and launch the mod:

```powershell
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

With the backend running and a developer world open:

```text
/npcortex status
/npcortex ask yui Hello!
```

The backend usually listens at `http://127.0.0.1:8765`. Keep it bound to loopback; **do not expose it to the public internet**. See [development setup](docs/development.md), [Forge setup](docs/phase-3-forge.md) and [API reference](docs/api.md).

## Experimental M03 / M04 checks

M03 and M04 commands are for an operator in a **copied test world**, not for unattended use. M04 currently exposes safe read-only inspection:

```text
/npcortex m04 status
/npcortex m04 preview <npc> <x> <y> <z>
```

The preview checks an existing NPC and target but **does not mine a block or authorize any world mutation**. For the automated source/contract/build verification tool on Windows:

```powershell
.\scripts\accept-m04.ps1
```

The tool's PASS means the specified automated checks passed, **not** that Minecraft gameplay, real mining or pickup is certified. Review [M04 guide](docs/m04/README.md), [M04 automated acceptance](docs/m04/AUTO_ACCEPTANCE.md) and [M03 developer validation](docs/m03-final/README.md) before running it. The M04 acceptance reviewer and several runtime/fault/performance gates are still not fully confirmed.

## Project structure

```text
backend/            Python API, persistence, task contracts and backend tests
forge-mod/          Forge player-based NPC, navigation, commands and Java tests
character-packs/    JSON NPC persona templates
docs/               Public architecture, API guides and technical documentation
integrations/       Experimental adapters and test datapacks
scripts/            Developer setup, tests and optional acceptance commands
.github/workflows/  CI configuration
```

Source files and automated tests remain in the repository. Machine-local data, game saves, build products, code-graph caches, AI-assistant configuration and disposable test outputs are intentionally excluded from public source distributions.

## Contributing and license

This project is under the [MIT License](LICENSE). Feedback, test results and well-scoped pull requests are welcome; please read [CONTRIBUTING.md](CONTRIBUTING.md).

Minecraft, Forge, Ollama models and third-party mods are subject to their own licenses. Experimental M04 world actions must not be presented as production-ready features.
