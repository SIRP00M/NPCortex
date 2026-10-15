# NPCortex

**An open-source, modular local-AI NPC framework for Minecraft.**  
*Bring intelligence, personality, and life to Minecraft NPCs.*

[Development branch](https://github.com/SIRP00M/NPCortex/tree/Development) · [Changelog](CHANGELOG.md) · [Architecture](docs/architecture.md) · [API](docs/api.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development version: `0.1.2-alpha.1` — C1-P1-B Transactional Inventory & Equipment (2026-10-15); not tagged or released**  
> This repository ZIP continues from the public `Development` branch baseline at [`f3a9450`](https://github.com/SIRP00M/NPCortex/commit/f3a9450496d955f26ff8b7cf8db5ac6594756761) (C0 headless AI runtime). **Forge and the in-package Python display version declare `0.1.2-alpha.1`; Python distribution metadata uses the equivalent PEP 440 version `0.1.2a1`.** C1-P0/C1-P1-A/C1-P1-B are *sprint milestones*, not published releases. This ZIP does not itself update GitHub.

**Branch/version policy:** `Development` carries pre-release builds and may advance independently of `main`. A `0.1.2-alpha.1` label identifies this development snapshot only; it is **not** a stable release or a guarantee that the branch can be promoted to `main` without reconciling their existing version lines and completing the required tests. Do not create a release tag just because the source version changed.

NPCortex targets **Minecraft 1.20.1 / Forge 47.4.10 / Java 17**. The Python backend uses **Ollama + Qwen 3 8B** and supports JSON character packs, durable actor identity, dialogue/social workflows and a headless agent contract. The experimental Forge C1 layer adds **visible player-based NPCs with operator-controlled movement, rotation and transactional Vanilla inventory/equipment**, while keeping the LLM backend and game-entity architecture separate. Development dialogue targets English first.

## What is implemented (and what has actually been verified)

| Component | Current implementation | Evidence / important boundary |
|---|---|---|
| **A2 — Forge chat bridge** | `/npcortex status`, `/npcortex ask` via Python `/v1/chat` | Developer command route, separate from C1 NPC actor control |
| **B1–B3 — Identity, dialogue and social runtime** | Durable worlds/actors, manually advanced multi-actor dialogue and Qwen-chosen social turns | Backend/HTTP workflows; no always-on autonomous society or Forge binding |
| **C0 / C0-B — Headless AI Agent** | Snapshots, bounded Goals, advertised SkillRegistry (`move_to`, `break_block`, `place_block`, `pickup_item`), Qwen decision validation and independent verification | Mock/trusted caller supplies world observations and action results; **not connected to C1's actual Minecraft actions**. Owner reported Windows C0-B acceptance success |
| **C1-P0 — True player-based prototype** | `C1PlayerAvatar extends ServerPlayer`, persistent World/Actor/Profile UUIDs, spawn/despawn, vanilla player rendering/tracking, viewer-scoped two-phase sync and bounded repair | Owner reported Windows initial spawn and world-rejoin repair **passed**; not the complete P0 Quality Gate |
| **C1-P1-A — Controlled movement** | Separate server-side intent controller, bounded `move`/`jump`/`turn`/`settle`/`stop`; state persisted to C1 SavedData | Owner reported Windows Minecraft core movement gameplay acceptance; **not autonomous navigation or full player lifecycle** |
| **C1-P1-A — Rotation Fix #1** | Persist independent yaw/pitch/headYaw/bodyYaw, apply per-viewer rotation snapshot after verified entity presence, acknowledge application | Owner confirmed head **and** body facing survive world exit/rejoin in Minecraft on Windows |
| **C1-P1-B — Inventory & equipment (architecture C)** | Player Inventory main/hotbar (36), armor (4), offhand (1), selected slot, command-driven give/equip/clear/copyhand; one SavedData snapshot + per-NPC transaction receipts, idempotent replay, event-driven equipment synchronization | Owner reported Windows single-player **diamond sword give/equip, transaction replay, and inventory/receipt persistence across Save & Quit/rejoin** passed. This is limited gameplay acceptance, **not** complete P1-B Quality Gate or crash-safe exactly-once durability |
| **Multi-NPC smoke test** | Separate named C1 avatars can coexist with independent identities and commands | Owner confirmed **Yui + Hina + Ai** 3-NPC spawn, independent movement/turns, world rejoin, independent despawn and re-tracking **before P1-B**. **P1-B inventory isolation across 3 NPCs is not yet confirmed**; neither is measured load/multiplayer |

**What is not implemented or not fully accepted:** autonomous Qwen-to-C1 world execution, complete C1-P1-B inventory durability/isolation/modded-item validation, survival/navigation, mining, building, crafting, TaCZ/Recruits combat, a complete Vanilla player lifecycle (next C1-P1-C scope), custom skin validation, LAN/multiplayer, and measured 5/10/20-NPC performance. No statement above proves those features exist. C1-P0 overall and broader C1 integration quality gates remain open.

The developer ZIP's individual `docs/c1-*.md` reports were written at the *candidate* stage. The owner-reported Windows gameplay acceptance recorded in this README and in [`CHANGELOG.md`](CHANGELOG.md) is **later evidence for only the tested behaviors**; it does not rewrite or retroactively certify every item in those candidate test plans.

## Architecture

```text
Minecraft 1.20.1 / Forge 47.4.10 (Java 17)
  |-- A2 legacy chat commands ----------> /v1/chat -----------+
  |                                                         |
  `-- C1 True Player-based NPC prototype                    |
        |-- C1PlayerAvatar : ServerPlayer                     |
        |-- World / Actor / GameProfile UUID SavedData        |
        |-- Viewer-specific network sync + bounded repair     |
        |-- Operator-controlled movement + rotation           |
        |-- P1-B Vanilla inventory/equipment                     |
        |    `-- SavedData snapshot + per-NPC transaction ledger |
        `-- Real world actions from Qwen: NOT WIRED          |
                                                            v
                                             Local Python backend
                                               A2: sessions
                                               B1: actor identity
                                               B2/B3: dialogues / social
                                               C0: snapshot -> Qwen decision
                                                   -> skill request -> verify
                                                            |
                                                            v
                                                   Ollama / qwen3:8b
```

The C1 body is a **Custom `ServerPlayer`-based experimental entity**, not a `PathfinderMob` with a player skin. The C0 backend cannot read Minecraft's world state or execute C1 actions without a future audited sensor/executor integration. Qwen outputs are proposals, never sufficient proof of in-world success. See [C0 protocol](docs/c0-headless-agent-runtime.md), [C0→C1 handoff](docs/c0-c1-handoff.md), [C1 feasibility](docs/c1-p0-player-feasibility.md), [P0 Network Fix #2](docs/c1-p0-network-sync-fix2.md), [P1-A movement](docs/c1-p1-a-controlled-player-physics.md), and [rotation sync](docs/c1-p1-a-rotation-fix1.md), and [P1-B transactional inventory](docs/c1-p1-b-transactional-inventory.md).

## Requirements and local setup (Windows)

- **JDK 17** on `PATH`, Minecraft **1.20.1**, Forge **47.4.10**.
- **Python 3.10+**, Windows PowerShell for `.ps1` scripts.
- [Ollama](https://ollama.com/) with `qwen3:8b` for AI-backed headless/chat tests; **C1 operator movement tests do not require Qwen**.
- Network access for the first Gradle dependency download; `forge-mod/gradlew.bat` and its wrapper files are included.

From the repository root:

```powershell
# Optional for backend/AI tests; requires a local Ollama service
ollama pull qwen3:8b
.\scripts\dev-backend.ps1

# Forge build and Minecraft developer client (JDK 17)
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

`dev-backend.ps1` normally serves `http://127.0.0.1:8765`; `/health` checks HTTP liveness only, not whether Qwen can answer. The local backend has **no authentication**: do not expose port 8765 to the internet. Consult `backend/.env.example` and [Development guide](docs/development.md) for environment variables.

### C1: controlled Minecraft acceptance commands

Use a backed-up **single-player** developer test world with cheats/operator commands enabled:

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c1 sync yui
/npcortex c1 rotation yui
/npcortex c1 settle yui
/npcortex c1 turn yui 90
/npcortex c1 move yui forward 3
/npcortex c1 motion yui
/npcortex c1 jump yui
/npcortex c1 stop yui
/npcortex c1 list
/npcortex c1 despawn yui
```

`/npcortex c1 move <name> <forward|back|left|right> <distance>` is limited to **0.5–8 blocks**, measured relative to the NPC's current facing. `turn` takes degrees. These commands are explicit test controls, **not commands generated by Qwen**. They do not cut trees, place blocks, pick up dropped items or plan a route. Inventory commands below explicitly modify an NPC inventory for manual testing; they are not AI/autonomous survival skills. For multi-NPC smoke testing, register `hina` and `ai` as well using the same `spawn`/control/despawn surface; don't confuse the three distinct C1 actor identities with character-pack templates or C0 registered actors.

Restart/rejoin checks must verify **persistent World/Actor/Profile UUIDs**, correct visible orientation, tracking recovery and lack of duplicate entities. `CLIENT_ENTITY_PRESENT` confirms the client's entity observation, not full visual or gameplay correctness. Diagnostic and acceptance details are in [C1-P0 sync](docs/c1-p0-network-sync-fix2.md), [C1-P1-A movement](docs/c1-p1-a-controlled-player-physics.md) and [rotation](docs/c1-p1-a-rotation-fix1.md).

### C1-P1-B: operator inventory/equipment tests

C1-P1-B uses the **Vanilla ServerPlayer inventory**, not the C0 backend's hypothetical `pickup_item` action. Operator commands require permission level 2 and operate on the named NPC:

```mcfunction
/npcortex c1 inventory yui
/npcortex c1 equipment yui
/npcortex c1 ledger yui
/npcortex c1 give yui minecraft:diamond_sword 1
/npcortex c1 equip yui 0 mainhand
/npcortex c1 give yui minecraft:bread 32
/npcortex c1 clear yui 10
/npcortex c1 copyhand yui 10
```

The optional final transaction UUID on `give`, `equip`, `clear` and `copyhand` enables replay tests: repeating **the same UUID with the same operation** returns `REPLAY`; reusing the UUID with a different operation returns `CONFLICT`. Omitting a transaction ID creates a new operation. `copyhand` **copies** an operator-held stack as a creative/debug fixture and does not remove it from the player's hand. Example replay test:

```mcfunction
/npcortex c1 give yui minecraft:bread 2 11111111-1111-4111-8111-111111111111
/npcortex c1 give yui minecraft:bread 2 11111111-1111-4111-8111-111111111111
/npcortex c1 ledger yui
/save-all flush
```

The ledger and inventory snapshot share an Overworld SavedData record. `APPLIED_PENDING_WORLD_SAVE` means a transaction was applied in memory; **it does not guarantee that it has been flushed to disk**. The journal has a fail-closed 512-receipt cap per NPC, and modded inventory mutations outside this runtime are not automatically journaled. For Windows validation, test saved-world reload, NBT/enchants, re-tracking, inventory isolation across Yui/Hina/Ai and replay/conflict handling using the [P1-B acceptance checklist](docs/c1-p1-b-transactional-inventory.md). Do not treat a pure-Java test as a durability or Forge verification.

### C1: dependency-free Java checks (not a Forge build)

```powershell
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
```

These check state-machine logic and isolated SavedData test doubles with JDK 17; they **do not** prove Forge class compatibility, rendering, Minecraft physics, multiplayer, skin behavior or real world persistence. Run the Forge build separately. Local packaging reports in the C1 notes include Java-only assertions and some interrupted backend test runs: do not substitute them for a confirmed exit-0 build or an owner gameplay acceptance record.

## Backend and headless workflow

Install/start Ollama as above, then run the Python backend in one PowerShell terminal. From a second terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health

$body = @{
    character_id = 'yui'
    player_id    = 'local-player'
    message      = 'Hey Yui!'
} | ConvertTo-Json
(Invoke-RestMethod -Uri http://127.0.0.1:8765/v1/chat `
    -Method Post -ContentType 'application/json' -Body $body).reply
```

The C0 backend stores durable agent goals in **SQLite schema v4**. For an existing SQLite file, **stop the backend and back it up before upgrading**; migrations are forward-only. C0 test observations simulate a Minecraft world, rather than connecting to the C1 player avatar:

```powershell
.\scripts\test-c0-planner.ps1 -Repeat 3
.\scripts\test-c0-regression.ps1 -Repeat 3
# In another terminal, start: .\scripts\dev-backend.ps1
.\scripts\test-c0-headless.ps1
# After restarting the backend with the same SQLite file:
.\scripts\test-c0-headless.ps1 -VerifyGoalId 'YOUR-GOAL-UUID'
```

Older backend workflows remain available: B2 `test-b2-dialogues.ps1`, B3 `test-b3-social.ps1` and Python regressions with `python -m unittest discover -s backend/tests -v`. Details in [C0-B Windows acceptance](docs/c0b-acceptance-windows.md), [API](docs/api.md) and [Changelog](CHANGELOG.md).

## Repository layout

```text
NPCortex-Development/
├── ReadMe.md                  # GitHub landing page, C1 milestone status
├── CHANGELOG.md               # One consolidated development history
├── backend/                   # Python HTTP + B1–B3/C0 runtime and tests
├── forge-mod/                 # Forge Java C1 player-based prototype + A2 bridge
├── character-packs/           # yui and guide JSON templates
├── docs/                      # Architecture, milestones and acceptance plans
├── scripts/                   # Backend/Forge test and launch scripts
├── integrations/              # Optional integrations, not yet operational
├── .github/workflows/         # Backend and Forge CI
├── CONTRIBUTING.md
└── LICENSE
```

To define a new backend character template, copy `character-packs/guide/` and edit `character.json`. This is **not** the same as spawning an autonomous C1 NPC. See the [character-pack guide](docs/character-packs.md).

## Next gates and roadmap

**Already observed by the owner in Windows Minecraft (limited scope):** C1-P0 viewer recovery on world rejoin; C1-P1-A controlled movement; Rotation Fix #1 orientation persistence; basic three-NPC (Yui, Hina, Ai) movement/identity smoke test; C1-P1-B give/equip/transaction replay and Save & Quit/rejoin persistence of inventory + replay in a single-player world.

**Still requires separate evidence:** full C1-P1-B Quality Gate (multi-NPC inventory isolation, crash-injection recovery, modded capabilities/NBT compatibility, load and multiplayer), complete P0 lifecycle/re-tracking/despawn matrix across conditions, Forge/CI reproducibility for this exact source, custom skin/model acceptance, LAN/multiplayer and realistic load measurements. **C1-P1-C Player Lifecycle is proposed work, not implemented in this ZIP.** Next work still follows **Audit → Options → Approval → Implement → Test** and the maintainer's **No Test, No Push** gate.

Some older pages under `docs/` are historical candidate-time reports rather than a current end-to-end feature inventory; use this README/CHANGELOG for the snapshot's consolidated status, and the source code for exact behavior.

## Security, license and contributions

The backend is intended for `127.0.0.1` single-machine development, not an unauthenticated remote server. TaCZ/Recruits are not working integrations in this ZIP. Original project source is [MIT-licensed](LICENSE); third-party software, model weights and assets may have separate licenses. Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
