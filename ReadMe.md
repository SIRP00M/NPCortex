# NPCortex

**An open-source, modular, local-AI NPC framework for Minecraft Java 1.20.1 (Forge).**  
*Character-driven companions powered by local Qwen, with an experimental true Player-based NPC, bounded physical navigation, and Follow Player.*

[Development branch](https://github.com/SIRP00M/NPCortex/tree/Development) · [Changelog](CHANGELOG.md) · [Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md) · [Outstanding tests](PENDING_TESTS.txt) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development source candidate: `v0.2.0-alpha.2` (2026-10-21).** Based on **C-S1–C-S4 Persistent Follow Motion Session C + BuildFix1 Option A**. The ZIP is not a published stable release and does not update GitHub automatically. **The latest patched `C2FollowRuntime.java` still requires a real Windows / JDK 17 Forge rebuild and gameplay verification** following an earlier `extended` variable compile error. Do not equate a successful pure-Java self-test with Forge compatibility.

## Current capabilities and maturity

| Subsystem | What this repository contains | Evidence / boundary |
|---|---|---|
| A2 + B1–B3 + C0 | Local Forge chat bridge, persistent Actor/World and dialogue state, headless AI social runtime, Qwen-selected headless Goal/Skill contracts with independently verified results | B/C0 workflows are API-driven; **headless results alone do not verify real Minecraft actions** |
| C1: true Player-based NPC | Custom `ServerPlayer` avatar, identity, viewer-scoped network repair, controlled Vanilla-player motion, equipment and inventory journal, lifecycle, death/respawn and combat | Several owner-reported Windows gameplay checks passed; multi-client/mod compatibility and comprehensive lifecycle regression remain open |
| C2-P0 / P1-A | Backend-to-NPC action integration, precision movement, 3D world-sensing and navigation grid | Basic live flat movement and terrain inspection owner-confirmed; not a global world map |
| C2-P1-B1/B2/B3 | Traversability Graph, bounded A* and hierarchical/region planning | Real code and isolated policy tests; graphs and planning are limited by loaded snapshot / budgets |
| C2-P1-C/D | Transactional waypoints, obstacle rerouting, cancellation/reconciliation and bounded dynamic replanning | User-reported obstacle detour and main Sprint 4 recovery behavior; complete cancellation/crash/unloaded tests remain open |
| Terrain 3D Sprints 1–4 | Block-shape support, step/jump/descend motion tickets, verified 3D navigation and replanning recovery | Prior owner-confirmed automatic JUMP_UP / SAFE_DESCEND; **STEP_DOWN −0.5 and mixed/edge fixtures remain unverified** |
| C2-F1/F2 | Follow Player by player identity; rolling goals, smoother yaw, path shortcuts, collision-parity, hazard-aware cost, local 360° steering and locomotion presentation | Basic F1 Follow and visible movement improvement owner-reported. Latest Follow revisions **not automatically gameplay accepted** |
| B+ + C-S1–C-S4 | Fresh-graph safe Follow corridor continuation across rolling route windows, bounded persistent C1 ticket, turn-in-place preflight fix, safety diagnostics | **Implementation candidate.** BuildFix1 has not passed Windows Forge rebuild in the attached report |

**Architecture boundaries:** C1 owns real movement, collisions, knockback and physics; C2 owns verified plans and receipts. Follow never turns the NPC into a `PathfinderMob`, does not teleport to catch up, force-load chunks, claim a global path planner, or override combat/cancel priority. The `C2FollowJournal` is an audit journal and **does not automatically replay movement after restart**.

## How the movement works

```text
Minecraft 1.20.1 / Forge (server-authoritative)
    Custom ServerPlayer avatar (C1)
    |  Vanilla player travel, knockback, inventory, lifecycle
    ^  verified motion ticket, owner token, physical receipts
    |
    C2 world sensor + risk-aware 3D graph
    -> bounded A* / hierarchical navigation -> transactional waypoint engine
    -> Follow target/ring -> bounded rolling local planning
    -> fresh certified flat corridor + adaptive local steering
    -> optional verified extension of current C1 Follow WALK token

Local Python Backend <-> Ollama / Qwen 3 8B
    A/B/C0: characters, persistent dialogue, headless AI contracts
    C2: action / verification bridge; no claim that Qwen autonomously controls
        every new C2 Follow/terrain/locomotion feature
```

Typical Follow behavior: stay near the player; wait within the stop-distance ring, resume when sufficiently far, plan only on observed loaded terrain, steer within verified corridors, and pause or retry within a bound when blocked. New persistent Follow extensions are limited to safe **flat WALK** corridors and preserve the existing C1 motion owner; vertical transitions still need typed verified motions.

## Requirements

- **Minecraft Java 1.20.1**, **Forge 47.4.10** (as declared by `forge-mod/gradle.properties`).
- **JDK 17** for the real Windows Forge/Gradle build and runtime acceptance. Pure-Java `--release 17` tests on a different JDK do not replace it.
- **Windows PowerShell** for the provided scripts and checklists.
- **Python 3.10+** for the backend; local **Ollama** with model **`qwen3:8b`** for Qwen-powered functions.
- No paid OpenAI API is required. First Forge build needs access to Gradle/Forge dependencies.

### Local startup (Windows PowerShell)

From the repository root, in separate terminals:

```powershell
# Model preparation (once)
ollama pull qwen3:8b

# Terminal A: Python backend
.\scripts\dev-backend.ps1

# Terminal B: Forge developer client (JDK 17)
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The backend defaults to `http://127.0.0.1:8765`, with its `/health` endpoint providing **backend liveness, not proof that Ollama/Qwen is ready**. `backend/.env.example` lists configurable environment variables; it is not automatically sourced. Stop the backend and back up `backend/data/npcortex.sqlite3` before migrating a previous database; SQLite upgrades are forward-only.

### In-game C1 / C2 smoke commands

```text
/npcortex status
/npcortex ask yui Hello!
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c1 motion yui
/npcortex c2 navscan yui 6
/npcortex c2 terraininspect yui 80 68 -62
/npcortex c2 navplan yui 80 69 -62
/npcortex c2 navigate yui 80 69 -62
/npcortex c2 status yui
/npcortex c2 cancel yui
```

Coordinates above are **examples**, not guaranteed safe target locations. Check world coordinates and the ground/support geometry first. Do not run manual C1 motion while the same NPC has an active C2 owner unless performing an intentional conflict test.

### Follow Player — Windows gameplay checklist

```text
/npcortex c2 follow yui YourMinecraftName
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c2 unfollow yui
```

Replace `YourMinecraftName` with the actual connected player name. The `followstatus` output can include `persistentAppends` and `rollingMisses`; the former should advance only when a certified continuation is actually appended. For long flat, turning, wall, hazard, stair/slab, combat and 1/3/5/10 NPC cases, follow the [C-S1–S4 Windows acceptance checklist](docs/c2-persistent-follow-session-c-windows-checklist.md). In particular, **BuildFix1 must pass the Forge compile first**.

## Automated regression entry points

```powershell
# Java dependency-free regression; requires an actual JDK 17 when run on Windows
.\scripts\test-c2-persistent-follow-c.ps1
.\scripts\test-c2-bplus-adaptive-steering.ps1
.\scripts\test-c2-generalized-traversal-c.ps1
.\scripts\test-terrain-s4-fix1.ps1

# Python backend: focused C2 cancellation/recovery tests
python -m unittest discover -s backend/tests -p 'test_c2_*.py' -q
```

**Packaging regression evidence:** 17 isolated Java suites / 2,490 assertions passed on JDK 21 with `javac --release 17`, and 16 focused backend C2 tests passed with exit code 0. These are not a native JDK 17 Forge build or gameplay results. Run the Windows scripts independently and record their actual exit codes. Source-carried test reports document earlier local outcomes: [Persistent Follow](docs/c2-persistent-follow-session-c-test-report.md), [B+](docs/c2-bplus-h1-h4-test-report.md), [3D Sprint 4 Fix #1](docs/terrain-sprint4-fix1-test-report.md). The full quality gate additionally requires real Minecraft and Forge metrics.

## Repository layout

```text
NPCortex-Development/
├── ReadMe.md                 # Developer entry point
├── CHANGELOG.md              # Single consolidated chronological history
├── PENDING_TESTS.txt         # Open acceptance and regression gates
├── backend/                  # Python local Qwen/actor/agent backend + tests
├── forge-mod/                # Forge custom ServerPlayer NPC / C1-C2 code + tests
├── character-packs/          # Character definitions
├── docs/                     # Detailed architecture / Windows checklists / patch notes
│   └── patch-notes/v0.2.0-alpha.2.md
├── scripts/                  # Development and regression PowerShell scripts
├── integrations/             # Optional future integration plans
├── CONTRIBUTING.md
└── LICENSE
```

Character Packs define personalities and dialogue templates. To add one, start from [`character-packs/guide/`](character-packs/guide/) and consult the [Character Pack Guide](docs/character-packs.md). Character Packs do not themselves create a persisted NPC in the Minecraft world.

## Known limitations and next gates

- **Latest candidate build:** previous Windows JDK 17 `compileJava` failed on an out-of-scope `extended` variable in `C2FollowRuntime.tick`; BuildFix1 changes two lines, **but the rebuilt patched candidate has not been owner-verified**. Check first with `dev-forge.ps1 -Task build`.
- **Current C-S1–S4:** real continuous 50–100-block Follow, zero-forward pre-turn, route-append diagnostics, moving target redirection, wall/3D, Save & Quit, cancel/combat, modded hazards and unloaded chunks remain scoped test obligations.
- **Past carryovers:** STEP_DOWN −0.5, all slab/stair orientations, dynamic safety regressions, offline/rejoin/crash, and true Forge MSPT/packet-rate for **1/3/5/10 NPC** are not globally certified.
- Conservative risk tags for modded blocks (`npcortex:traversal_costly`, `npcortex:traversal_forbidden`) are provided; unknown modded collision/effects can require additional configuration and real testing.
- Local backend is unauthenticated and should not be exposed to the public internet. TaCZ and broad survival/autonomy remain future scoped work, not features claimed by this snapshot.

See [PENDING_TESTS.txt](PENDING_TESTS.txt), [Roadmap](docs/roadmap.md), and [Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md). This project follows scoped source audit, options, approval, tests, and maintainer-run Windows acceptance. **The maintainer performs GitHub operations**; this ZIP does not commit, push, merge, or tag anything.

## License

Project code is licensed under [MIT](LICENSE); external model weights, Forge/Minecraft and user-provided assets have separate terms.
