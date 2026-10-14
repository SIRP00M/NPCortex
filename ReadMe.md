# NPCortex

**An open-source, modular AI NPC framework for Minecraft.**

> Bring intelligence, personality, and life to Minecraft NPCs.

NPCortex connects Minecraft Forge 1.20.1 to a local LLM backend, with room for future provider adapters. The first development milestone uses **Ollama + Qwen 3 8B**, runs entirely on your computer and uses **English dialogue**. Custom character packs are plain JSON.

**Project status: `v0.1.1-alpha` B1 World & Actor Identity Foundation candidate (pending Minecraft/Forge gates).** The Python HTTP backend, bounded and expiring legacy chat sessions, SQLite-backed B1 actor conversations, example character packs and a Forge `/npcortex status` and `/npcortex ask` developer command bridge are implemented as starter source. The A2 legacy Forge bridge was accepted in a user-reported Minecraft smoke test; **new B1 actor identity/persistence has not yet been tested inside Minecraft**. There is **no spawned NPC entity, NPC UI, pathfinding, combat, TaCZ integration, semantic memory, NPC-to-NPC dialogue, or multiplayer authentication yet**.

## Repository layout

```text
NPCortex/
├── backend/                # Python server; model, legacy sessions, SQLite world/actor registry
│   ├── src/npcortex_backend/
│   └── tests/
├── forge-mod/              # Forge 1.20.1 mod (Java 17)
│   └── src/main/
├── character-packs/        # Copyable JSON-based NPC definitions
│   ├── guide/
│   └── yui/
├── integrations/           # Optional integrations; nothing is active yet
├── docs/                   # Architecture, API contract, character packs, roadmap
├── scripts/                # Development commands and Forge wrapper bootstrap
├── .github/workflows/      # Python and Forge CI (requires network)
├── LICENSE                 # MIT license for original code
└── CONTRIBUTING.md
```

## Prerequisites

- Minecraft **1.20.1**, Minecraft Forge **47.4.10** (recommended release), **JDK 17** for Forge development.
- Python **3.10+** on the computer hosting the AI backend.
- [Ollama](https://ollama.com/) running locally with `qwen3:8b` downloaded:

```powershell
ollama pull qwen3:8b
```

## Quick start — Python backend (Windows PowerShell)

From the **repository root**:

```powershell
.\scripts\dev-backend.ps1
```

No Python third-party packages are required. You should see `NPCortex backend listening at http://127.0.0.1:8765`.

In a *second* PowerShell window, test health and chat:

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

The backend communicates with Ollama at `http://127.0.0.1:11434/api/chat`. Qwen responses are configured with `think=false` and `stream=false`. Edit `backend/.env.example` to see environment variables. **Character packs do not need an Ollama key or OpenAI subscription.** Running an LLM still consumes your own computer resources.

## Quick start — Forge mod (developer setup)

The Forge sources and Gradle build files are included. The official Gradle wrapper files **are included** in `forge-mod/`. Only if they are missing, `scripts/setup-forge-wrapper.ps1` can retrieve them from the Forge MDK (SHA-1 checked). **Use JDK 17 (not Java 21)**:

```powershell
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

For PowerShell script restrictions, run `Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned` once (if permitted). See [Phase 3 Windows guide](docs/phase-3-forge.md).

You need **JDK 17** selected for Gradle. Downloading Gradle and Minecraft dependencies requires internet access. The first launch can take a while. If the Forge wrapper setup cannot download from the official URL, download the MDK for `1.20.1-47.4.10` manually from [Minecraft Forge downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html) and copy only the Gradle wrapper files into `forge-mod`.

Start the Python backend and create a **single-player test world with Allow Cheats ON** (developer commands require operator permission) before using:

```text
/npcortex status
/npcortex ask yui Hey Yui! Want to explore the forest?
```

The server sends the chat request to the local backend asynchronously and sends the reply back to the player's chat. **This command is a developer integration test, not an actual NPC dialogue UI.** The Forge command bridge source is included, but a **new ForgeGradle build and B1-specific real-world integration test remain unverified in this delivery**. The A2 automated Python tests do not replace those gates.

## B1 — World & Actor Identity Foundation

B1 adds `POST /v1/worlds`, `POST /v1/actors`, `POST /v1/actor-chat`,
`GET /v1/actors/<uuid>`, and `GET /v1/conversations/<uuid>` for localhost
headless tests. Two Yui actors can have separate stable UUIDs and independently
persisted conversation transcripts in `backend/data/npcortex.sqlite3` across
backend restarts. The original Forge `/npcortex ask` still uses legacy `/v1/chat`
(in-memory only) and **does not yet refer to an Actor UUID**. B1 implements no
NPC-to-NPC communication, Forge entities, spatial world simulation or behavior.

[Full API contract](docs/api.md) · [B1 Windows verification](docs/b1-verification.md)
· [Future C roadmap — not implemented](docs/future-autonomy-roadmap.md).

## Baseline stabilization (A2)

A2 keeps `GET /health` and `POST /v1/chat` success responses unchanged. Same-session requests are ordered, with bounded waiting and explicit HTTP busy errors; histories are limited to six turns, expire after idle time, and can be evicted when the configured maximum session count is reached. Backend/model resources have configurable limits. See [A2 Changelog](CHANGELOG.md), [API](docs/api.md), and [Windows verification](docs/a2-verification.md).

Run all Python tests from repository root:

```powershell
python -m unittest discover -s backend/tests -v
```

**No authentication was added**: keep the backend on loopback, and do not forward port 8765.

## How to customize

Copy `character-packs/guide/` to `character-packs/my_npc/`; change the JSON fields and send `character_id: "my_npc"` to `/v1/chat`. See [Character Pack Guide](docs/character-packs.md). Do not change core code just to add a new character.

## Roadmap

- **A2:** baseline stability; accepted Minecraft conversation smoke test.
- **B1 (candidate):** persisted world/actor identity, player-to-actor transcripts.
- **B2/B3:** NPC-NPC conversation engine, headless multi-NPC social verification.
- **After B:** Forge NPC entity, UI and optional game integrations.
- **Future C (not implemented):** autonomous world behavior/goals/action planning.

See [Roadmap](docs/roadmap.md) and [Architecture](docs/architecture.md).

## Security and compatibility

The backend binds to `127.0.0.1` by default and has no authentication; **do not expose it directly to the public internet**. `127.0.0.1` from a dedicated Minecraft server means the server machine, not each connected player's computer. TaCZ and Recruits are not bundled or required; test optional integrations in a separate Forge profile.

## License

MIT for the original starter code; dependencies and bundled character assets may have separate licenses. Minecraft, Forge, Ollama and Qwen are owned by their respective authors; no model weights are included.
