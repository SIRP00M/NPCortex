> **B3-B HTTP Stability Candidate:** Adds bounded HTTP 413/404 request-body drain for Windows `WinError 10053`. Run `.\scripts\test-b3-http-lifecycle.ps1 -Repeat 3` and `.\scripts\test-b3-regression.ps1 -Repeat 3`. No B3 social behavior changes; Windows acceptance pending. See `docs/b3-http-lifecycle-hardening.md`.

> **B3 AI-Driven Headless Social Runtime — Implementation Candidate.** B2-C passed user-reported Windows quality gates. B3 adds Qwen-selected, bounded social steps over headless NPC-only dialogues. Back up SQLite **with Backend stopped** before schema v2→v3 migration. Use `./scripts/test-b3-regression.ps1` and `./scripts/test-b3-social.ps1` for B3 acceptance. There is **no in-game NPC or C world simulation**.

# NPCortex

**An open-source, modular AI NPC framework for Minecraft.**

> Bring intelligence, personality, and life to Minecraft NPCs.

NPCortex connects Minecraft Forge 1.20.1 to a local LLM backend, with room for future provider adapters. The first development milestone uses **Ollama + Qwen 3 8B**, runs entirely on your computer and uses **English dialogue**. Custom character packs are plain JSON.

**Project status: `v0.1.1-alpha` — B3 development candidate.** A2, B1, B2 and B2-C have user-reported acceptance for their tested scopes. B3 adds Qwen-planned turn selection and bounded dialogue runs in the Python backend; **real Qwen/Windows/Forge acceptance of B3 is pending**. Forge's `/npcortex ask` still uses the legacy A2 chat endpoint. No Minecraft NPC entity, event perception, autonomous world loop, dynamic relationship system, movement, mining, combat, or C-world simulation is implemented.

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

## B3 quick test (headless, real local Qwen)

B3 orchestrates dialogue between 2–8 *registered NPC actors*, controlled by explicit API steps. The model chooses which NPC speaks or to stop; the model is **not authorized to perform Minecraft actions**. SQLite schema v3 adds `social_runs` and `social_steps` while preserving old actor/dialogue/transcript tables.

```powershell
.\scripts\test-b3-regression.ps1 -Repeat 3
# In one PowerShell: .\scripts\dev-backend.ps1
# In another PowerShell with the backend running:
.\scripts\test-b3-social.ps1
```

Save the printed RUN UUID; after restarting Backend:

```powershell
.\scripts\test-b3-social.ps1 -VerifyRunId 'RUN-UUID'
```

To test resuming a run after restarting mid-dialogue, use `-PauseAfter 2` and then `-ResumeRunId 'RUN-UUID'`. See [B3 Headless Social Verification](docs/b3-headless-social.md) for API contract, idempotent retries, and current limitations.

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


## B2 — Multi-Actor Dialogue Engine (headless candidate)

Create a local dialogue with 2–8 participants (NPC actors plus optional player),
then ask Qwen for **one explicitly selected NPC's response** at a time. Turns are
saved with speaker IDs in SQLite v2, separate from B1's private actor transcripts.
Qwen does not select future speakers or automatically start conversations. No
NPCs spawn in Minecraft yet. See [B2 API](docs/api.md) and
[B2 Windows checklist](docs/b2-verification.md).

Run this from the repository root with the backend/Ollama already running:

```powershell
python -m unittest discover -s backend/tests -v
.\scripts\test-b2-dialogues.ps1
```

Existing B1 databases upgrade **v1 to v2** automatically at backend startup.
**Stop the backend and back up the SQLite DB before first B2 start.** If
unpacking B2 to a new folder, copy the B1 backup into the new `backend/data/`
folder before launch; old save data is not bundled in the ZIP. The B1
backend cannot open a v2 DB, so reverting code requires restoring the backup.
