# NPCortex

**An open-source, modular AI NPC framework for Minecraft.**

> Bring intelligence, personality, and life to Minecraft NPCs.

NPCortex connects Minecraft Forge 1.20.1 to a local LLM backend, with room for future provider adapters. The first development milestone uses **Ollama + Qwen 3 8B**, runs entirely on your computer and uses **English dialogue**. Custom character packs are plain JSON.

**Project status: `v0.1.1-alpha` Phase 3 scaffold.** The Python HTTP backend, in-memory chat sessions, example character packs and a Forge `/npcortex status` and `/npcortex ask` developer command bridge are implemented as starter source. They have *not* been tested together inside Minecraft. There is **no spawned NPC entity, NPC UI, pathfinding, combat, TaCZ integration, persistent memory, or multiplayer authentication yet**.

## Repository layout

```text
NPCortex/
├── backend/                # Python server; model adapter, sessions, character loader
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

The Forge sources and Gradle build files are included. The official Forge MDK Gradle wrapper binary is deliberately **not** shipped inside this scaffold. Our launcher bootstraps it from the recommended MDK (SHA-1 checked). **Use a JDK 17 (not Java 21)**:

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

The server sends the chat request to the local backend asynchronously and sends the reply back to the player's chat. **This command is a developer integration test, not an actual NPC dialogue UI.** The Forge command bridge is currently source-only and **not verified through a complete ForgeGradle build or game launch**.

## How to customize

Copy `character-packs/guide/` to `character-packs/my_npc/`; change the JSON fields and send `character_id: "my_npc"` to `/v1/chat`. See [Character Pack Guide](docs/character-packs.md). Do not change core code just to add a new character.

## Roadmap

- **v0.1:** Local English chat, character packs, minimal Forge command (starter implementation).
- **v0.2:** Real NPC entity and in-game conversation UI.
- **v0.3:** Persistent NPC memory, relationship state and player profiles.
- **v0.4:** Safe validated action execution, follow/stay/pathfinding.
- **v0.5:** Optional TaCZ / other mod integrations, combat compatibility testing.
- **Later:** Thai localization, richer behaviors, more LLM providers.

See [Roadmap](docs/roadmap.md) and [Architecture](docs/architecture.md).

## Security and compatibility

The backend binds to `127.0.0.1` by default and has no authentication; **do not expose it directly to the public internet**. `127.0.0.1` from a dedicated Minecraft server means the server machine, not each connected player's computer. TaCZ and Recruits are not bundled or required; test optional integrations in a separate Forge profile.

## License

MIT for the original starter code; dependencies and bundled character assets may have separate licenses. Minecraft, Forge, Ollama and Qwen are owned by their respective authors; no model weights are included.
