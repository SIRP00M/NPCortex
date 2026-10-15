# NPCortex

**An open-source, modular local-AI NPC framework for Minecraft.**  
*Bring intelligence, personality, and life to Minecraft NPCs.*

[Development branch](https://github.com/SIRP00M/NPCortex/tree/Development) · [Changelog](CHANGELOG.md) · [Architecture](docs/architecture.md) · [API](docs/api.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development snapshot — `v0.1.1-alpha`**  
> This README documents the **source tree in this repository copy**, including the C0-B headless backend changes. The public `Development` branch may not yet contain those changes until the maintainer commits and pushes this snapshot. `0.1.1-alpha` is the version declared by Forge and the Python package; sprint names are **not** new published releases.

NPCortex connects a Minecraft **1.20.1 / Forge 47.4.10** development bridge to a local Python backend powered by **Ollama + Qwen 3 8B**. It supports JSON **Character Packs** and headless, API-driven experiments with identity, persistent dialogue, multi-NPC social turns, and an evidence-checked agent protocol. English is the primary supported dialogue language in this development stage.

## Current implementation status

| Area | In this source tree | Boundary |
|---|---|---|
| **Forge conversation bridge (A2)** | `/npcortex status` and `/npcortex ask` through `/v1/chat` | Chat command, **not** a spawned NPC or dialogue UI |
| **Actor identity (B1)** | Persisted World/Actor UUIDs and player–actor transcripts | Backend API only; Forge command remains on legacy chat route |
| **Multi-actor dialogue (B2)** | Explicit 2–8 participant turns, SQLite persistence | Caller selects each speaker; no self-running conversations |
| **Headless social runtime (B3)** | Qwen-selected NPC speaker/stop, bounded manually advanced social runs | No background scheduler, relationship simulation, or Minecraft entities |
| **Headless agent contracts (C0)** | Persistent Goals, snapshots, advertised Skills, Qwen decisions, action requests, postcondition verification | World observations and results are supplied by a **mock/trusted caller**; no real Minecraft actions |
| **C0-B hardening** | Structured Qwen decision format, validation diagnostics, Windows `-VerifyGoalId` assertion fix | Does not change C0 skill execution or Forge source |
| **C1 True Player-based NPC** | **Architectural target; not in this ZIP** | Spawn, networking, rendering, lifecycle and compatibility require separate feasibility proof |

The maintainer reported successful Windows acceptance of the **C0-B acceptance-script fix**, including its `-VerifyGoalId` path, and a real-Qwen **headless test using a mock Forge sensor**. This is **not** proof of Forge gameplay, in-world movement, mining, player-based NPC spawning, or the full C1 Quality Gate. Reports for earlier phases are preserved under [`docs/`](docs/). Do not infer additional tests passed from this status summary.

## Architecture at a glance

```text
Minecraft 1.20.1 + Forge 47.4.10 (Java 17)
  /npcortex ask                 [existing developer chat bridge]
        |
        v  /v1/chat
Python Backend <----> Ollama / Qwen 3 8B (local)
  |-- A2: bounded legacy chat sessions
  |-- B1: World/Actor UUID + persisted transcripts
  |-- B2: caller-driven multi-actor dialogue
  |-- B3: caller-stepped AI social turns
  `-- C0: Goals -> Snapshot -> Qwen decision -> Action request
                    ^                           |
                    `-- independently observed result / verify
                         (mock or trusted external caller ONLY)

C1 Forge player-based NPC + actual world sensor/executor: FUTURE INTEGRATION
```

**Qwen is the decision-making model**, but the backend validates its proposed skill arguments against observed data and does not treat the model's own success claim as world evidence. C0 has no automatic tick/background world loop. See [C0 protocol](docs/c0-headless-agent-runtime.md) and [C1 handoff](docs/c0-c1-handoff.md).

## Requirements

- **Windows PowerShell** for the included `.ps1` development and acceptance scripts.
- **Python 3.10+** for the backend; the baseline has no required third-party Python runtime packages.
- [Ollama](https://ollama.com/) running locally with the `qwen3:8b` model.
- **JDK 17** for Forge development, Minecraft **1.20.1**, Forge **47.4.10** (matching `forge-mod/gradle.properties`).
- Internet access on first Forge build for Gradle/Forge dependencies. No paid OpenAI API key is required.

Install the local model:

```powershell
ollama pull qwen3:8b
```

## Quick start — Backend

From the repository root in Windows PowerShell:

```powershell
.\scripts\dev-backend.ps1
```

The development server normally listens on `http://127.0.0.1:8765`. In a second terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health

$body = @{
  character_id = 'yui'
  player_id    = 'local-player'
  message      = 'Hey Yui! Want to explore the forest?'
} | ConvertTo-Json

(Invoke-RestMethod -Uri http://127.0.0.1:8765/v1/chat `
  -Method Post -ContentType 'application/json' -Body $body).reply
```

`/health` checks backend liveness, **not Ollama/Qwen readiness**. `backend/.env.example` lists optional OS environment variables; the file is **not loaded automatically**. Ollama defaults to `http://127.0.0.1:11434/api/chat`. See [Development guide](docs/development.md) and [API](docs/api.md).

## Quick start — Forge developer bridge

Use JDK 17, run from the repository root:

```powershell
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Gradle wrapper files are already present under `forge-mod/`. `scripts/setup-forge-wrapper.ps1` is only needed if those files are missing. Start the Python backend, launch a single-player developer world with cheats/operator permissions enabled, and use:

```text
/npcortex status
/npcortex ask yui Hey Yui! Want to explore the forest?
```

This is a **chat-command bridge**, not an in-world NPC. It does not invoke the B1–C0 Actor/Agent APIs. See [Forge/Windows setup](docs/phase-3-forge.md). A separate owner-run Forge build and Minecraft test are required for every Forge integration change.

## C0 headless AI-Agent verification

C0 requires no actual Minecraft world. The test script posts **mock Forge world observations**, asks the real local Qwen to choose an advertised `break_block` skill, deliberately submits an untrue success once, and checks that the backend rejects it until a later snapshot shows the requested postcondition.

First stop the backend and **back up `backend/data/npcortex.sqlite3`** before upgrading an existing database; the migrations are forward-only through **SQLite schema v4**. Then:

```powershell
# Automated Python regressions (backend does not need to run)
.\scripts\test-c0-planner.ps1 -Repeat 3
.\scripts\test-c0-regression.ps1 -Repeat 3

# Start backend in terminal A
.\scripts\dev-backend.ps1

# Run real-Qwen / mock-world acceptance in terminal B
.\scripts\test-c0-headless.ps1
```

Save the `GOAL` UUID printed by the script. **Restart the backend** (with the same SQLite file) before checking persistence:

```powershell
.\scripts\test-c0-headless.ps1 -VerifyGoalId 'YOUR-GOAL-UUID'
```

The `-VerifyGoalId` check does not call Qwen. Its PowerShell array/count assertions were corrected in C0-B. See [C0 acceptance checklist](docs/c0b-acceptance-windows.md), [C0 protocol](docs/c0-headless-agent-runtime.md), [C0-B planner](docs/c0-planner-reliability.md), and [Changelog](CHANGELOG.md).

## Other headless workflows

**B2 explicitly selected dialogue turns:** run `python -m unittest discover -s backend/tests -v` then, with backend/Ollama running, ` .\scripts\test-b2-dialogues.ps1`. See [B2 verification](docs/b2-verification.md).

**B3 Qwen-directed social runs:** with backend/Ollama running, use `.\scripts\test-b3-social.ps1`; regression checks: `.\scripts\test-b3-regression.ps1 -Repeat 3`. The script supports `-PauseAfter`, `-ResumeRunId`, and `-VerifyRunId` for interrupted/persisted runs. See [B3 verification](docs/b3-headless-social.md).

All live Ollama checks require that the model is available and can take longer than mock-provider unit tests. Neither B2 nor B3 creates an NPC inside Minecraft.

## Repository structure

```text
NPCortex-Development/
├── ReadMe.md                 # GitHub landing page
├── CHANGELOG.md              # Single consolidated development history
├── backend/                  # Local Python HTTP + agent/social runtime + tests
├── forge-mod/                # Forge 1.20.1 Java chat-command bridge
├── character-packs/          # JSON character definitions: yui, guide
├── docs/                     # Contracts, test reports, Windows verification
├── scripts/                  # PowerShell development and test entrypoints
├── integrations/             # Optional integration plans (not active)
├── .github/workflows/        # Backend and Forge CI workflows
├── CONTRIBUTING.md
└── LICENSE
```

To create a character pack, copy `character-packs/guide/` to `character-packs/my_npc/` and edit `character.json`. Existing `/v1/chat` accepts the new `character_id`. **Character Packs are templates**, not persistent Actor identities. See [Character Pack Guide](docs/character-packs.md).

## Direction: C1 Player-based NPC

The planned **C1 True Player-based NPC** (Custom `ServerPlayer`/`FakePlayer`-based feasibility target, not `PathfinderMob` as the primary NPC architecture) must first prove **identity, spawn, networking, rendering and player lifecycle**. It is not safe to assume Forge's stock `FakePlayer` is a fully functioning visible player character. Movement, inventory, block interaction, mod compatibility and C0 sensor/executor binding belong to later, separately tested stages; autonomous navigation, survival, mining, building and TaCZ combat are **not** claimed here.

See [Roadmap](docs/roadmap.md), [C0 → C1 integration contract](docs/c0-c1-handoff.md), and the [Development branch](https://github.com/SIRP00M/NPCortex/tree/Development). No code changes are pushed automatically from a ZIP; the maintainer manages all GitHub commits and pushes.

## Security, compatibility and license

The Python server is bound to **`127.0.0.1`** by default and has **no authentication**. Do not expose port 8765 on the public internet; `127.0.0.1` on a dedicated game server is the server host, not an individual player's PC. Server-side validation and access controls will be necessary before any real world-action bridge. TaCZ/Recruits are **not bundled or functional integrations** in this snapshot.

Original project code is under the [MIT License](LICENSE). External model weights, Minecraft/Forge dependencies and character assets may carry their own terms. Contributions: [CONTRIBUTING.md](CONTRIBUTING.md).
