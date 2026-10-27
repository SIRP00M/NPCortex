# NPCortex

**An open-source, local-AI companion framework for Minecraft Java 1.20.1 (Forge).**  
Create in-game player-like NPCs with their own personalities, interact with a local AI backend, and experiment with navigation, following and carefully guarded tasks.

[Changelog](CHANGELOG.md) · [What's new](docs/patch-notes/v0.2.5-alpha.1.md) · [Character Profiles](docs/character-profiles.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development version: `v0.2.5-alpha.1` (27 October 2026).** This is an untagged **alpha source milestone**, not a stable release. Features and tests described below have different levels of verification. No automated block-breaking/pickup system, unrestricted navigation or production-ready multi-NPC behavior is claimed.

## What you can do today

| Capability | What is available | Maturity |
|---|---|---|
| **Create a player-like NPC** | Forge-side player-based NPC prototype with spawn, identity, controlled movement, lifecycle and equipment | Experimental; single-player scenarios tested; multiplayer not certified |
| **Create a unique personality** | **ESC → NPCortex Manager** to create an NPC with a custom display name, structured personality or raw prompt; edit and save versioned profiles | Owner-confirmed Windows single-player GUI smoke tests passed for the tested creation/profile workflow |
| **Local AI conversation** | Python backend plus Ollama / Qwen 3 8B, with persistent actor dialogue and experimental social turns | Requires separately running the local backend/model |
| **Follow and navigate** | Terrain-aware bounded navigation, follow behavior, waypoint recovery and **operator-issued long-range navigation** on already loaded terrain | Experimental; not global pathfinding, no chunk forcing or cross-world routes |
| **Guarded task workflow** | Explicit movement requests, state tracking, cancellation, terminal reconciliation and read-only world observations | Not a general-purpose autonomous block interaction engine |
| **Testing tools** | Java/Python regressions, Windows PowerShell scripts and optional Minecraft acceptance datapacks | Tooling PASS is not a gameplay certification |

## Requirements

- Minecraft **1.20.1**, Forge **47.4.10**, and **JDK 17** for the Forge development environment.
- **Python 3.10+** for the backend and Windows PowerShell for the included `.ps1` scripts.
- [Ollama](https://ollama.com/) with `qwen3:8b` for local model-based dialogue. No paid cloud AI API key is required for the default setup.

## Quick start (Windows)

From the repository root in PowerShell:

```powershell
ollama pull qwen3:8b
.\scripts\dev-backend.ps1
```

In another terminal, build or run the Forge development client:

```powershell
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The backend normally listens on **`127.0.0.1:8765`**. The `/health` endpoint checks backend availability, **not** whether Ollama is ready. You can use `/npcortex status` in Minecraft; the broader operator command set requires appropriate permission. See [development setup](docs/development.md), [Forge setup](docs/phase-3-forge.md) and [API reference](docs/api.md).

## Character Profiles and the in-game Manager

The **Character Profile** is the authoritative per-NPC personality record in SQLite. The Manager is reached from the Minecraft **ESC/Pause** menu; it can list NPCs, create new ones and save personality edits. Names **no longer need to match a Character Pack directory** for new profile-based NPCs. Existing Yui/Guide Character Packs remain supported for legacy actors and optional presets.

New profiles use **SQLite schema v5** with immutable revisions and revision-conflict checks. **An existing v1–v4 database does not upgrade automatically**: stop the backend, back up your data, rehearse with a copy, then deliberately set `NPCORTEX_ALLOW_SCHEMA_V5=1` for the upgrade. A fresh database starts at v5. Keep private API tokens, database files and backups out of Git. The new profile endpoints require the local token even when the legacy API token requirement is disabled.

See [Character Profile setup, API, migration and tests](docs/character-profiles.md). A [PowerShell registration fallback](scripts/register-npc-profile.ps1) is provided for operators.

> **Acceptance boundary:** The project owner confirmed tested Windows **single-player GUI smoke scenarios** (including creating Reika through the Manager) passed. This does not establish dedicated-server operation, real existing-database migration, security fault injection, GUI multi-client behavior or full 1/3/5/10 NPC performance. An internal `GameProfile`-style nametag may still appear instead of the intended display name.

## Bounded long-range navigation

The experimental operator command is:

```text
/npcortex c2 navlong <npc> <x> <feetY> <z>
```

- Attempts multi-leg movement **only across loaded terrain**, within an implementation cap of **160 blocks**. It uses bounded planning, progress/timeout controls and FIFO planning slots.
- Maintains one logical C2 goal/action for internal legs; it does **not** load chunks, save an enduring route or automatically retry uncertain outcomes.
- Obstacles, unloaded terrain and insufficient progress can result in a conservative failure. Existing `navigate`, `move`, Follow and guarded task entrypoints remain distinct.

See [long-range navigation design and manual checks](docs/c2-long-range-option-d.md) and [G5 test-harness guide](docs/g5-test-harness/README.md). Full G5 acceptance, many-NPC load, shutdown/fault recovery and complex path quality are **not** certified by this alpha milestone.

## Repository layout

```text
backend/           Python local AI HTTP backend, SQLite persistence and tests
forge-mod/         Forge 1.20.1 Java mod and independent JVM regression tests
character-packs/   Optional JSON presets and legacy characters
integrations/      Experimental integrations and opt-in test datapacks
scripts/           Development, verification and operator tooling
docs/              Public architecture, API, setup and test guides
.github/workflows/ Python and Forge build CI configuration
```

## Security and limitations

- Keep the backend bound to **loopback**. Do not expose it directly to the internet. Optional local API authentication does not replace a public-service security review.
- Actor/world IDs, server-side permission checks, and token handling remain authoritative on the server. GUI forms must not be treated as proof of authorization.
- There is **no certified automatic mining, pickup or general world mutation**. M04 contracts/previews and test harnesses are not an autonomous executor.
- The alpha milestone has not passed full multi-client, dedicated-server, multi-mod, database-upgrade or fault-injected Quality Gates.

NPCortex is distributed under the [MIT License](LICENSE) for original project code. Minecraft/Forge, models and external assets have their own licenses. See [CONTRIBUTING.md](CONTRIBUTING.md) for developer guidelines.
