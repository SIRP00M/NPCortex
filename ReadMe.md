# NPCortex

**An open-source, modular, local-AI NPC framework for Minecraft Java 1.20.1 (Forge).**  
*Character-driven companions powered by local Qwen, with an experimental true Player-based NPC, terrain-native navigation, persistent 3D Follow, and airborne-target-aware ground following.*

[Development branch](https://github.com/SIRP00M/NPCortex/tree/Development) · [Changelog](CHANGELOG.md) · [Patch Notes](docs/patch-notes/v0.2.1-alpha.1.md) · [Previous Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md) · [Outstanding tests](PENDING_TESTS.txt) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development source candidate: `v0.2.1-alpha.1` (2026-10-22).** Based on the uploaded **Airborne Follow Target Projection Fix** Full Repository ZIP, building on `v0.2.0-alpha.2` (Navigation & Persistent Follow). This is an untagged Development snapshot, **not a stable release**. This packaging step does not change Java/Python runtime logic or update any GitHub branch. The maintainer reported that the previously observed **Creative-flight-triggered Follow zigzag disappeared** during real Minecraft testing of this fix; that is a **scenario-limited gameplay PASS**, not a complete Forge Quality Gate, multiplayer, or 1/3/5/10 NPC performance certification.

## Development milestone versioning

The `Development` branch uses pre-1.0 versions to track **meaningful development milestones**. The prior `v0.2.0-alpha.2` milestone introduced Navigation & Persistent Follow; `v0.2.1-alpha.1` starts the separate **Follow Stability / Airborne Target Projection** milestone. An `alpha.N` increment within a milestone may be used for candidate fixes or verification iterations. These labels are **not stable releases** and do not mean all Quality Gates have passed or that `main` has been updated.

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
| B+ + C-S1–C-S4 | Fresh-graph safe Follow corridor continuation across rolling route windows, bounded persistent C1 ticket, turn-in-place preflight fix, safety diagnostics | Earlier BuildFix1 report required Windows recompilation; **the latest alpha.3 package is not newly Forge-build-certified by this packaging step** |
| C-3D + C-TN + Smooth Hardening C | Bounded mixed-stage C1 follow lease, terrain-native detour stability, predictive continuation and follow telemetry | Owner observed terrain recovery working after a brief safe pause in a tested fixture; full mixed 3D / hazardous-terrain / cancel / performance gates remain open |
| Stable Trajectory B + Airborne Follow Target Projection | Bounded lateral damping, intermediate-waypoint crossing, flying-state debounce, projected reachable ground-height target, horizontal stop distance for actual flying targets | **Owner-reported Minecraft PASS for elimination of flight-related zigzag in the tested scenario.** Other flight, elevation, combat, hazard, offline/rejoin and 1/3/5/10 NPC cases remain unverified |

**Architecture boundaries:** C1 owns real movement, collisions, knockback and physics; C2 owns verified plans and receipts. Follow never turns the NPC into a `PathfinderMob`, does not teleport to catch up, force-load chunks, claim a global path planner, or override combat/cancel priority. The `C2FollowJournal` is an audit journal and **does not automatically replay movement after restart**. Airborne target projection adjusts **Follow goal selection**, not the NPC's ability to fly; C1 remains a ground-bound player-physics controller.

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

Typical Follow behavior: stay near the player; wait within the stop-distance ring, resume when sufficiently far, plan only on observed loaded terrain, steer within verified corridors, and pause or retry within a bound when blocked. While the tracked player is actively flying, use horizontal XZ stop distance and a reachable ground projection (or a neutral reference height where no nearby safe support is found) instead of using midair player Y as a standing target. New persistent Follow extensions are limited to safe **flat WALK** corridors and preserve the existing C1 motion owner; vertical transitions still need typed verified motions.

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

Replace `YourMinecraftName` with the actual connected player name. The `followstatus` output can include `persistentAppends` and `rollingMisses`; the former should advance only when a certified continuation is actually appended. For long flat, turning, wall, hazard, stair/slab, combat and 1/3/5/10 NPC cases, follow the [C-S1–S4 Windows acceptance checklist](docs/c2-persistent-follow-session-c-windows-checklist.md). For Creative/elytra flights, compare `targetMode`, `targetProjection`, and `projectedY` against the [Airborne Windows checklist](docs/c2-airborne-target-projection-windows-checklist.md). A fresh Forge build of **this exact ZIP** is still an independent acceptance item.

## Automated regression entry points

```powershell
# Java dependency-free regression; requires an actual JDK 17 when run on Windows
.\scripts\test-c2-persistent-follow-c.ps1
.\scripts\test-c2-airborne-target-projection.ps1
.\scripts\test-c2-stable-trajectory-follow-b.ps1
.\scripts\test-c2-terrain-native-follow-c.ps1
.\scripts\test-c2-persistent-3d-c.ps1
.\scripts\test-c2-bplus-adaptive-steering.ps1
.\scripts\test-c2-generalized-traversal-c.ps1
.\scripts\test-terrain-s4-fix1.ps1

# Python backend: focused C2 cancellation/recovery tests
python -m unittest discover -s backend/tests -p 'test_c2_*.py' -q
```

**Validation and evidence:** Fresh tests on this ZIP passed **22/22 pure-Java suites (2,658 assertions)** and **16/16 focused Python C2 tests**, each with Exit Code 0. Java was compiled with JDK 21 `javac --release 17`, which does **not** verify Windows JDK17 Forge compilation. The maintainer separately reported flight-associated zigzag disappearing in real Minecraft, limited to that scenario. See the current [Changelog](CHANGELOG.md) and the full [PENDING_TESTS.txt](PENDING_TESTS.txt).

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
│   └── patch-notes/           # v0.2.0-alpha.2 and alpha.3
├── scripts/                  # Development and regression PowerShell scripts
├── integrations/             # Optional future integration plans
├── CONTRIBUTING.md
└── LICENSE
```

Character Packs define personalities and dialogue templates. To add one, start from [`character-packs/guide/`](character-packs/guide/) and consult the [Character Pack Guide](docs/character-packs.md). Character Packs do not themselves create a persisted NPC in the Minecraft world.

## Known limitations and next gates

- **Airborne Follow fix:** Owner confirmed the previous Creative-flight zigzag disappeared in the tested Minecraft scenario; **not** proof of all grounded/airborne transitions, far-away flight, every mixed terrain fixture, hazards or multiplayer. The new `followstatus` fields `targetMode`, `targetProjection`, and `projectedY` help diagnose remaining issues.
- **No flying NPC:** Projected target Y is a planning/scoring reference from the bounded reachable graph, not a new collision surface, force-loaded path, teleport, or unbounded terrain scan. Unknown/unloaded/unsafe paths remain fail-closed.
- **New terrain-native/persistent mixed 3D behavior:** Owner observed terrain detours recovering after brief pauses on tested terrain; acceptance for full mixed stages, STEP_DOWN −0.5, all slab/stair orientations, cancellation in midair and lifecycle remains open.
- **Current alpha.3 validation:** Real Windows JDK17 Forge build of this exact package, all FLY-01–FLY-14 checklist cases, combat/owner/cancel safety and Forge MSPT for **1/3/5/10 NPC** require explicit evidence. Older BuildFix1's reported compiler failure is historical and should not be interpreted as the current alpha.3 build result.
- Conservative risk tags for modded blocks (`npcortex:traversal_costly`, `npcortex:traversal_forbidden`) remain; modded collision/effects require real testing.
- The local backend is unauthenticated and must not be exposed to the public internet. TaCZ and broader survival/autonomy remain future scoped work, not capabilities included here.

See [PENDING_TESTS.txt](PENDING_TESTS.txt), [Roadmap](docs/roadmap.md), [Airborne Follow verification](docs/c2-airborne-target-projection-windows-checklist.md), [Patch Notes](docs/patch-notes/v0.2.1-alpha.1.md), and [previous Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md). This project uses scoped source audit, options, approval, tests and maintainer-run Windows acceptance. **Only the maintainer performs GitHub operations**; this ZIP does not commit, push, merge or tag anything.

## License

Project code is licensed under [MIT](LICENSE); external model weights, Forge/Minecraft and user-provided assets have separate terms.
