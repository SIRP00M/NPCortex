# NPCortex

**An open-source, modular local-AI NPC framework for Minecraft.**  
*Bring intelligence, personality, and life to Minecraft NPCs.*

[Development branch](https://github.com/SIRP00M/NPCortex/tree/Development) · [Changelog](CHANGELOG.md) · [Architecture](docs/architecture.md) · [API](docs/api.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development snapshot: `v0.2.0-alpha.1` — C2-P0 Embodied AI + C2-P1-A World Representation (2026-10-16)**  
> This source ZIP preserves the previous `v0.1.2-alpha.2` Development history (GitHub base `591f3adb`) and adds the C2-P0/P1-A implementation shipped in the attached candidate ZIP. **Forge and Python runtime display `0.2.0-alpha.1`; Python distribution metadata is `0.2.0a1` (PEP 440).** This snapshot is **not** a tagged release and does not modify GitHub on its own. See [open test gates](PENDING_TESTS.txt).

**Branch/version policy:** `Development` carries pre-release builds and may advance independently of `main`. A `0.2.0-alpha.1` label identifies this development snapshot only; it is **not** a stable release or a guarantee that the branch can be promoted to `main` without reconciling their existing version lines and completing the required tests. Do not create a release tag just because the source version changed.

NPCortex targets **Minecraft 1.20.1 / Forge 47.4.10 / Java 17**. The Python backend uses **Ollama + Qwen 3 8B** and supports JSON character packs, durable actor identity, dialogue/social workflows and a headless agent contract. The Forge **C1** layer adds visible Custom-`ServerPlayer` NPCs, operator movement/rotation, transactional Vanilla inventory, selective lifecycle/death presentation and combat-motion work. The **C2-P0** layer bridges bounded, operator-requested `move_to` actions through the backend to real NPC motion and requires independent post-action verification; C2 precision movement supports a configurable arrival radius. **C2-P1-A** adds a read-only, bounded 3D server terrain scan (`C2NavGrid`/`C2NavigationSensor`); it does **not** implement A*, multi-step waypoints, dynamic replanning or autonomous harvesting. Development dialogue targets English first.

## What is implemented (and what has actually been verified)

| Component | Current implementation | Evidence / important boundary |
|---|---|---|
| **A2 — Forge chat bridge** | `/npcortex status`, `/npcortex ask` via Python `/v1/chat` | Developer command route, separate from C1 NPC actor control |
| **B1–B3 — Identity, dialogue and social runtime** | Durable worlds/actors, manually advanced multi-actor dialogue and Qwen-chosen social turns | Backend/HTTP workflows; no always-on autonomous society or Forge binding |
| **C0 / C0-B — Headless AI Agent** | Snapshots, bounded Goals, advertised SkillRegistry (`move_to`, `break_block`, `place_block`, `pickup_item`), Qwen decision validation and independent verification | C0 alone is headless; C2-P0 now binds **only bounded `move_to`** to the Forge NPC. Other headless skill contracts are not Minecraft executors. Owner reported Windows C0-B acceptance success |
| **C1-P0 — True player-based prototype** | `C1PlayerAvatar extends ServerPlayer`, persistent World/Actor/Profile UUIDs, spawn/despawn, vanilla player rendering/tracking, viewer-scoped two-phase sync and bounded repair | Owner reported Windows initial spawn and world-rejoin repair **passed**; not the complete P0 Quality Gate |
| **C1-P1-A — Controlled movement** | Separate server-side intent controller, bounded `move`/`jump`/`turn`/`settle`/`stop`; state persisted to C1 SavedData | Owner reported Windows Minecraft core movement gameplay acceptance; **not autonomous navigation or full player lifecycle** |
| **C1-P1-A — Rotation Fix #1** | Persist independent yaw/pitch/headYaw/bodyYaw, apply per-viewer rotation snapshot after verified entity presence, acknowledge application | Owner confirmed head **and** body facing survive world exit/rejoin in Minecraft on Windows |
| **C1-P1-B — Inventory & equipment (architecture C)** | Player Inventory main/hotbar (36), armor (4), offhand (1), selected slot, command-driven give/equip/clear/copyhand; one SavedData snapshot + per-NPC transaction receipts, idempotent replay, event-driven equipment synchronization | Owner reported Windows single-player **diamond sword give/equip, transaction replay, and inventory/receipt persistence across Save & Quit/rejoin** passed. This is limited gameplay acceptance, **not** complete P1-B Quality Gate or crash-safe exactly-once durability |
| **C1-P1-C — Selective Vanilla lifecycle** | Persisted ALIVE/DEAD/TRANSITION, controlled death/respawn/transfer; `ServerPlayer.tick()` world phase restores spawn-invulnerability countdown, idle floor support and Vanilla fall checking | **Implementation candidate.** `ServerPlayer.doTick()` remains deliberately disabled; no full PlayerList/login parity. Windows Forge/Gameplay for this source not certified |
| **C1-R0 — Lifecycle foundation** | `C1VanillaLifecycleCoordinator` and phase ownership policy consolidate P0 → motion → death tick/shutdown ownership without changing identity, networking protocol or SavedData schema | Dependency-free local policy checks are not a Forge gameplay pass |
| **C1-R1 / R1-A — Death presentation** | Controlled 26-tick Vanilla player death pose, per-viewer death acknowledgment, one-shot `POOF`/`CLOUD` burst before detach | Candidate source. Presentation timing, real client visibility and keepInventory/respawn regression require Windows gameplay evidence |
| **C1-R2 — Combat physics + Fixes #1/#2** | Vanilla damage/knockback capture (`super.knockback`), bounded COMBAT > COMMANDED > SETTLING ownership, single authoritative travel path and collision-only `Entity.move` fallback; Fix #2 retains a transient Vanilla velocity snapshot across attack/world/tick boundary | **Not yet accepted for real player Knockback II.** Latest `BuildFix1` only fixes a Java call-site (`captureVanilla`); Forge compilation and in-game displacement are still required |
| **C2-P0 — Event-driven AI-to-motion integration** | `C2AgentRuntime`, server observation, bounded operator `/npcortex c2 move`, async backend calls, action journal and position/result verification. `move_to` is a single same-height segment within 7.5 blocks; C2 precision tolerance defaults to **0.35** (configurable **0.20–0.75**). | Owner reported real Windows Minecraft basic move-to-target and short-range precision movement **working**. These are scoped gameplay results, not proven Qwen trace, multiplayer, crash safety or pathfinding |
| **C2-P1-A — Read-only World Representation** | `C2NavGrid` and `C2NavigationSensor` observe up to 845 candidate terrain cells within radius 1–6 (default 4), with collision/support/hazard/unloaded classifications; `/npcortex c2 navscan <name> [radius]` is an operator diagnostic. | Maintainer-reported **Windows Forge Build PASS**, flat/dynamic/hazard/lifecycle and **five-NPC sequential** gameplay checks. **Unloaded-chunk gameplay** and **per-coordinate partial-block accuracy remain NOT YET TESTED**; concurrent performance/MSPT open. See [pending tests](PENDING_TESTS.txt) |
| **Multi-NPC smoke test** | Separate named C1 avatars can coexist with independent identities and commands | Owner confirmed **Yui + Hina + Ai** 3-NPC spawn, independent movement/turns, world rejoin, independent despawn and re-tracking **before P1-B**. **P1-B inventory isolation across 3 NPCs is not yet confirmed**; neither is measured load/multiplayer |

**What is not implemented or not fully accepted:** C2-P1-B 3D A*, C2-P1-C waypoint execution, C2-P1-D dynamic replanning, obstacle-routing autonomy, generalized block break/place/pickup Minecraft skills, survival/tree harvesting/mining/building/crafting, TaCZ/Recruits, and comprehensive crash-recovery/multiplayer performance. C1-P0/C1-P1-B and other broader regression/compatibility gates remain open. In particular, P1-A unloaded-chunk Minecraft gameplay and detailed partial-block semantic accuracy are **NOT YET TESTED**. A positive `navscan` result describes standing candidates, **not** a reachable route. See [PENDING_TESTS.txt](PENDING_TESTS.txt).

The developer ZIP's individual `docs/c1-*.md` reports were written at the *candidate* stage. The owner-reported Windows gameplay acceptance recorded in this README and in [`CHANGELOG.md`](CHANGELOG.md) is **later evidence for only the tested behaviors**; it does not rewrite or retroactively certify every item in those candidate test plans.

## Architecture

```text
Minecraft 1.20.1 / Forge 47.4.10 (Java 17)
  |-- A2 legacy /npcortex ask --------> Python /v1/chat
  |-- C1: Custom ServerPlayer avatar
  |     |-- identity + viewer-scoped sync + lifecycle/physics
  |     `-- controlled movement + inventory/equipment
  |-- C2-P0: operator /npcortex c2 move [tolerance]
  |     |-- server sensor -> async Python C0 -> Ollama/Qwen
  |     |-- bounded single-segment move_to -> C1 movement
  |     `-- fresh world observation + postcondition verification
  `-- C2-P1-A: operator /npcortex c2 navscan [radius]
        `-- immutable read-only C2NavGrid / C2NavigationSensor
            (support, collision, hazards, unknown/unloaded)

Python backend: B1–B3 identity/social + C0 skills/goals/verification
NEXT (not implemented): P1-B A* -> P1-C waypoints -> P1-D replanning
```

Qwen can participate in a bounded C2-P0 `move_to` choice, but model output **never** establishes success by itself. C2-P1-A terrain snapshots are for diagnosis/future planning and are **not** fed directly as a large grid to the C0 API. Forge does not force-load chunks for navscan; uncertain/unloaded surfaces must not be considered safe. Source details: [C2-P0 architecture](docs/c2-p0-event-driven-embodied-agent.md), [precision movement](docs/c2-p0-precision-movement-c.md), [C2-P1-A world representation](docs/c2-p1a-world-representation.md), [C0 protocol](docs/c0-headless-agent-runtime.md), [C1 feasibility](docs/c1-p0-player-feasibility.md), and [inventory](docs/c1-p1-b-transactional-inventory.md).

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

### C2-P0 and C2-P1-A: embodied movement and diagnostic world scans

C2-P0 requires the local backend and Ollama for AI-driven move decisions. P1-A `navscan` is read-only, permission-level-2, server-thread diagnostics; it does not create a goal or move an NPC. In **Windows PowerShell** with **Temurin JDK 17**:

```powershell
.\scripts\test-c2-p1a.ps1      # isolated Java + backend regression; requires javac 17
.\scripts\test-c2-p0.ps1
.\scripts\dev-forge.ps1 -Task build
# Backend and Forge client run in separate terminals:
.\scripts\dev-backend.ps1
.\scripts\dev-forge.ps1 -Task runClient
```

In a backed-up single-player development world with cheats enabled:

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 navscan yui
/npcortex c2 navscan yui 1
/npcortex c2 navscan yui 6
/npcortex c2 status yui
/npcortex c1 info yui
```

For C2-P0 movement, choose actual loaded same-height coordinates near the NPC; do **not** type the following sample target without replacing it:

```mcfunction
/npcortex c2 move yui 100 64 100 0.35
/npcortex c2 status yui
/npcortex c2 cancel yui
```

C2-P0 requires an in-range destination; `reset` requires investigation of any pending backend goal. Scan radius defaults to 4, maximum 6, scanning at most 845 candidate cells. Classifications include `WALKABLE`, `OBSTRUCTED`, `PARTIAL_SUPPORT`, `UNSUPPORTED`, `HAZARDOUS`, and `UNLOADED`; `WALKABLE` **does not** imply a route exists. Detailed tests: [C2-P1-A checklist](docs/c2-p1a-world-representation.md) and [precision test guide](docs/c2-p0-precision-movement-c.md).

**Acceptance distinction:** the owner reported Windows Forge Build PASS and successful flat-terrain, dynamic place/break/rescan, hazard/lifecycle and five-NPC *sequential* navscan. That is not proof of loaded-chunk boundary safety or per-coordinate slab/stair/fence accuracy; **both remain NOT YET TESTED**. Concurrent MSPT/1/3/5/10-NPC load and other comprehensive gates remain open; see [`PENDING_TESTS.txt`](PENDING_TESTS.txt).

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

`/npcortex c1 move <name> <forward|back|left|right> <distance>` is limited to **0.5–8 blocks**, measured relative to the NPC's current facing. `turn` takes degrees. These **C1 commands** are explicit operator test controls, not direct Qwen commands. Separately, **C2-P0** can use Qwen to select a *bounded single-segment `move_to`* through the C1 motion runtime. Neither route cuts trees, places blocks, collects drops or plans a multi-waypoint route. Inventory commands below explicitly modify an NPC inventory for manual testing; they are not AI/autonomous survival skills. For multi-NPC smoke testing, register `hina` and `ai` as well using the same `spawn`/control/despawn surface; don't confuse the three distinct C1 actor identities with character-pack templates or C0 registered actors.

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

### C1-P1-C / R0 / R1 / R2: lifecycle and combat acceptance (candidate)

Use a **backup test world**. Confirm the Forge build finishes without a compile error before attempting combat testing. The newer source implements an operator-controlled death/respawn path and a Vanilla-derived knockback bridge; they are **not AI-driven combat or full Vanilla PlayerList emulation**.

```powershell
# Dependencies and source compatibility must be tested on your Windows JDK 17 / Forge install
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 lifecycle yui
/npcortex c1 motion yui
/npcortex c1 combat yui
/npcortex c1 damage yui 3
/npcortex c1 lifecycle yui
/npcortex c1 knockback yui 0.5
/npcortex c1 motion yui
/npcortex c1 combat yui
/npcortex c1 kill yui
/npcortex c1 lifecycle yui
/npcortex c1 respawn yui
/npcortex c1 inventory yui
```

Wait for the R1 death presentation before respawning. For actual R2 acceptance, hit the NPC with a **real player's Knockback II weapon on open level ground**, then compare the first-three-frame `C1-R2-FIX2 COMBAT_STEP` logs (`ownership`, `incoming`, `actualDelta`, collision state) and `/npcortex c1 motion` coordinates. Repeat against walls, with Zombie hits, death/respawn, inventory replay, world rejoin and multi-NPC tracking. **An impulse log alone is not proof of movement.** The current BuildFix1 changes the called method name, not knockback behavior. See [R2 Fix #2 test plan](docs/c1-r2-fix2-movement-lifecycle-refactor.md), [R2 combat](docs/c1-r2-vanilla-combat-physics.md), [R1 death](docs/c1-r1-verified-vanilla-death.md), and [R0 ownership](docs/c1-r0-vanilla-lifecycle-foundation.md).

### C1: dependency-free Java checks (not a Forge build)

```powershell
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
.\scripts\test-c1-p1-lifecycle.ps1
.\scripts\test-c1-p1c-vanilla-tick.ps1
.\scripts\test-c1-r0-foundation.ps1
.\scripts\test-c1-r1-death.ps1
.\scripts\test-c1-r2-combat.ps1
.\scripts\test-c1-r2-fix2.ps1
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

The C0 backend stores durable agent goals in **SQLite schema v4**. For an existing SQLite file, **stop the backend and back it up before upgrading**; migrations are forward-only. C0 acceptance tests still simulate a Minecraft world; **C2-P0 separately** provides a real-world sensor/executor bridge for bounded `move_to`:

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
├── ReadMe.md                  # GitHub landing page, C1 + C2 milestone status
├── CHANGELOG.md               # One consolidated development history
├── PENDING_TESTS.txt          # P1-A open gameplay/performance acceptance gates
├── backend/                   # Python HTTP + B1–B3/C0 runtime and tests
├── forge-mod/                 # Forge C1 NPC + C2 sensor/agent/motion bridge
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

**Previously accepted by maintainer on Windows Minecraft (limited scope):** C1-P0 viewer recovery on world rejoin; C1-P1-A controlled movement and persisted rotation; 3-NPC (Yui/Hina/Ai) movement/identity smoke; C1-P1-B inventory give/equip/transaction replay and Save & Quit/rejoin persistence. C2-P0 single same-height target movement and subsequent short-range precision movement were also observed working. C2-P1-A Windows Forge Build and scoped flat/dynamic/hazard/lifecycle/five-NPC sequential navscan gameplay were reported passed on 2026-10-16.

**Still open:** C2-P1-A live unloaded-chunk boundary gameplay (**NOT YET TESTED**); per-coordinate Partial Block/Terrain Accuracy (**NOT YET TESTED**); *concurrent* 1/3/5/10-NPC MSPT/performance, broader regression and multiplayer/mod compatibility. Earlier C1 acceptance gaps and R2 combat testing are not implicitly closed by C2 evidence. Open checks and verification source distinctions are tracked in [PENDING_TESTS.txt](PENDING_TESTS.txt).

Future C2 stages are **P1-B 3D A***, **P1-C waypoint execution** and **P1-D bounded dynamic replanning** — planned only, **not implemented**. Follow **Audit → Options → Approval → Implement → Test**, do not advance based solely on a version bump, and keep all GitHub operations with the maintainer. Historical candidate notes under `docs/` are not automatically promoted to PASS by later results.

## Security, license and contributions

The backend is intended for `127.0.0.1` single-machine development, not an unauthenticated remote server. TaCZ/Recruits are not working integrations in this ZIP. Original project source is [MIT-licensed](LICENSE); third-party software, model weights and assets may have separate licenses. Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
