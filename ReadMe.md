# NPCortex

**An open-source, modular, local-AI NPC framework for Minecraft Java 1.20.1 (Forge).**  
*Local-Qwen companions with a true Player-based NPC, verified terrain navigation, grounded Follow, and a modular C+ action-contract foundation.*

[GitHub main](https://github.com/SIRP00M/NPCortex) · [Changelog](CHANGELOG.md) · [Latest Patch Notes](docs/patch-notes/v0.2.3-alpha.1.md) · [Previous Patch Notes](docs/patch-notes/v0.2.2-alpha.1.md) · [Follow Stability notes](docs/patch-notes/v0.2.1-alpha.1.md) · [Navigation notes](docs/patch-notes/v0.2.0-alpha.2.md) · [Outstanding tests](PENDING_TESTS.txt) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Current development candidate: `v0.2.3-alpha.1` (2026-10-23), updated E3-D G1 build.** Compared with GitHub `main` at commit `0618d95b` (`v0.2.2-alpha.1`), this source includes C-F1 recovery diagnostics, C-F2 read-only world perception, C-F3 E-P0 durable task preview, E1/E2 operator-driven read-only task coordination and **E3-D G1 typed movement previews**. The movement preview is **dry-run only**: it never starts NPC movement, runs an automatic task graph or changes blocks/inventory. Forge/Minecraft verification of this exact ZIP remains pending.

## What's new — safe NPC task tracking and movement preview

This `v0.2.3-alpha.1` candidate is an **expanded source snapshot of the same milestone**, not a new tagged release. It adds the following operator-only development experiments:

- **C-F1 recovery diagnostics:** durable diagnostic journal, explicit sync/audit, loopback authentication and fail-closed handling of uncertain operations. These logs do not prove Minecraft world mutations or exactly-once effects.
- **C-F2 world inspection:** bounded read-only 3×3×3 observation of loaded blocks around an actual C1 NPC, with nearby entities/items, strict identity and conservative `UNKNOWN`/`DENY` mutation permission outcomes.
- **E-P0 durable read-only graph:** `/npcortex debug cplus epreview yui` records and previews a small three-node read-only dependency graph with task history persisted in SQLite. `READ_ONLY_VERIFIED` verifies observations, not mining or building.
- **E1/E2 operator task coordination (read-only):** `/npcortex debug cplus tasksubmit yui`, `taskqueue`, `taskdispatch`, `taskverify` and `taskcancel` (with arguments per the Windows checklist). These exercise FIFO task admission, bounded `READ_CELL` leases and separately verified observation receipts. They **do not** give the NPC control of navigation, tools or world effects.
- **E3-D G1 typed movement preview:** `/npcortex debug cplus taskmovepreview yui <x> <y> <z>` accepts *integer* target block coordinates. Forge checks the live NPC identity/dimension, grounded state, C1/C2/Follow ownership, uncertain navigation journal, a loaded target and local distance bounds; the authenticated Python backend validates a typed request/snapshot and saves an idempotent `PREVIEW_ONLY` record. Backend explicitly responds with `DRY_RUN`, `can_execute=false`, `motion_started=false`. **The NPC must not move and no E1 task is enqueued.**

The movement preview is intentionally **local only**, not a multi-chunk navigation system: backend limits horizontal displacement to six blocks in each direction and vertical displacement to three blocks relative to the observation center. E3-D **G2 real C2 movement** and **G3 verification/cancel/recovery**, E3-E automatic movement DAG, world mutation, mining and Qwen auto-dispatch are **not implemented by this G1 increment**.

Read the [current Patch Notes](docs/patch-notes/v0.2.3-alpha.1.md), [E3-D G1 Windows gameplay checklist](docs/cplus-cf3-e3-d-g1-windows-gameplay-checklist.md), [E1/E2 Gate-2 checklist](docs/cplus-cf3-e1-e-gate2-windows-gameplay-checklist.md), [gated implementation plan](docs/architecture/CF3_E_GATED_IMPLEMENTATION.md) and [pending checks](PENDING_TESTS.txt). Previously reported Windows tests apply only to their particular source candidates, **not** this ZIP automatically.

## Development milestone versioning

The active development line now resides on GitHub **`main`** (`legacy-main` preserves the earlier architecture). Versions are numbered by meaningful milestones: `v0.2.0-alpha.2` introduced Navigation & Persistent Follow; `v0.2.1-alpha.1` introduced Follow Stability; **`v0.2.2-alpha.1` introduced the C+ C-F0 shared contracts; `v0.2.3-alpha.1` includes C-F1/F2 diagnostics and C-F3 E-P0, E1/E2 read-only coordination and E3-D G1 dry-run movement preview**. `alpha.N` is reserved for candidate iteration within the same milestone. Pre-release milestones are not stable releases and are not evidence of a full Quality Gate.

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
| B+ + C-S1–C-S4 | Fresh-graph safe Follow corridor continuation across rolling route windows, bounded persistent C1 ticket, turn-in-place preflight fix, safety diagnostics | Earlier BuildFix1 report required Windows recompilation; **standalone contract tests do not recertify the earlier follow-specific Forge gameplay matrix** |
| C-3D + C-TN + Smooth Hardening C | Bounded mixed-stage C1 follow lease, terrain-native detour stability, predictive continuation and follow telemetry | Owner observed terrain recovery working after a brief safe pause in a tested fixture; full mixed 3D / hazardous-terrain / cancel / performance gates remain open |
| Stable Trajectory B + Airborne Follow Target Projection | Bounded lateral damping, intermediate-waypoint crossing, flying-state debounce, projected reachable ground-height target, horizontal stop distance for actual flying targets | **Owner-reported Minecraft PASS for elimination of flight-related zigzag in the tested scenario.** Other flight, elevation, combat, hazard, offline/rejoin and 1/3/5/10 NPC cases remain unverified |
| **C+ C-F1 / C-F2 / E-P0** | Durable diagnostic sync/audit, live bounded read-only observations, authenticated E-P0 durable task preview | No world effects; previously reported H1–H5 diagnostic gameplay is scenario-limited |
| **C+ C-F3 E1/E2 — read-only coordinator** | Operator task submit/queue/cancel plus guarded READ_CELL dispatch/verification, durable FIFO and bounded read leases | No automatic execution; real Windows E2 cancellation/restart/parallel-NPC regression remains pending |
| **C+ C-F3 E3-D G1 — typed movement preview** | Operator-only local `move_to` input validation and durable PREVIEW_ONLY audit; never calls C2 navigation | **DRY_RUN only:** no NPC motion, no task enqueue, no world mutation; exact ZIP Forge/Gameplay G1 gates pending |
| C+ C-F0 — contracts and ownership | **New:** isolated v1 Java/Python action envelope, identity and target validation, proof-guarded state transitions, deterministic resource/FIFO-ticket contracts, C0/C1/C2/C+ ownership rules, proposed public commands and legacy routing map | **Contract-only.** No active action engine, scheduler, registered unified Brigadier commands, real FIFO locks, persistence migration, new skill execution or gameplay interaction; [contract details](docs/architecture/ACTION_CONTRACTS.md) |

**Architecture boundaries:** C1 owns real movement, collisions, knockback and physics; C2 owns verified plans and receipts. Follow never turns the NPC into a `PathfinderMob`, does not teleport to catch up, force-load chunks, claim a global path planner, or override combat/cancel priority. The `C2FollowJournal` is an audit journal and **does not automatically replay movement after restart**. Airborne target projection adjusts **Follow goal selection**, not the NPC's ability to fly; C1 remains a ground-bound player-physics controller.

## Historical C+ C-F0 — contract foundation (no live unified commands)

C-F0 lays out the contracts needed to add a **general-purpose action engine later** while keeping **C1 (player physics/inventory), C2 (navigation/follow) and C+ (action coordination)** as separate internal modules. The backend has strict `cplus_contracts.py` envelope/transition validators; Forge has independent pure-Java `cplus` identity, action, lifecycle, ownership, resource and *proposed* public route definitions. These are **not wired to the live backend/Forge command tree**.

- **Contract identity:** versioned action envelope with world/dimension/actor/goal/task/action/attempt IDs, typed target and expected result; proof required before a terminal completed action.
- **Ownership and cancellation:** responsibilities and resource ordering defined without transferring C1 physics or C2 navigation authority; uncertain world mutations must not be blindly retried.
- **Unified API proposal only:** future `/npcortex spawn`, `/npcortex follow`, `/npcortex stop`, `/npcortex status` and operator `/npcortex debug ...`; **these commands are not available yet.** Keep existing `/npcortex c1 ...` and `/npcortex c2 ...` commands and exact argument semantics.
- **Milestone evolution:** C-F1 diagnostic journal and C-F2 read-only observation are present; E-P0, E1/E2 read-only queue/dispatch/verify and E3-D G1 typed dry-run preview exist. E3-D G2/G3, automatic movement DAG and world mutation remain separate future gates.

See [architecture](docs/architecture/ACTION_ENGINE.md), [action contracts](docs/architecture/ACTION_CONTRACTS.md), [unified command design](docs/architecture/UNIFIED_COMMAND_API.md), [F0 Windows checklist](docs/cplus-cf0-windows-checklist.md) and [source report](docs/cplus-cf0-test-report.md).

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

Replace `YourMinecraftName` with the actual connected player name. The `followstatus` output can include `persistentAppends` and `rollingMisses`; the former should advance only when a certified continuation is actually appended. For long flat, turning, wall, hazard, stair/slab, combat and 1/3/5/10 NPC cases, follow the [C-S1–S4 Windows acceptance checklist](docs/c2-persistent-follow-session-c-windows-checklist.md). For Creative/elytra flights, compare `targetMode`, `targetProjection`, and `projectedY` against the [Airborne Windows checklist](docs/c2-airborne-target-projection-windows-checklist.md). Real Windows Forge/build and game regression evidence must be tracked separately from this package's local test runs.

## Automated regression entry points

For C-F0 contracts on **Windows Temurin JDK 17** (does not register or execute new in-game C+ actions):

```powershell
.\scripts\test-cplus-f0-contracts.ps1
.\scripts\dev-forge.ps1 -Task build
```



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

**Validation and evidence:** This packaged ZIP passed **71/71 isolated Java assertions** (JDK 21, `--release 17`), **15/15 C-F0 Python tests** and **16/16 focused C2 Python tests**, with Exit Code 0 for each test command. These are not Windows Forge build or gameplay results. The earlier C2 implementation remains unchanged in this incoming source. Historical C2 Java results (**22 suites, 2,658 assertions**) and Python C2 (**16 tests**) came from the prior `v0.2.1-alpha.1` packaging session; they are not represented as a Forge build of this ZIP. The maintainer separately confirmed Creative-flight zigzag resolution in a tested Minecraft scenario. Owner-reported C-F0 Windows gameplay/build acceptance is recorded as such in [PENDING_TESTS.txt](PENDING_TESTS.txt), not inferred from the local test runner. See [C-F0 report](docs/cplus-cf0-test-report.md) and [Changelog](CHANGELOG.md).

## Repository layout

```text
NPCortex/
├── ReadMe.md                 # Developer entry point
├── CHANGELOG.md              # Single consolidated chronological history
├── PENDING_TESTS.txt         # Open acceptance and regression gates
├── backend/                  # Python backend; includes standalone C+ schema validators
├── docs/architecture/        # C+ action/ownership/public command contracts
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
- **Historical follow-alpha validation:** Real Windows JDK17 Forge build of this exact package, all FLY-01–FLY-14 checklist cases, combat/owner/cancel safety and Forge MSPT for **1/3/5/10 NPC** require explicit evidence. Older BuildFix1's reported compiler failure is historical and should not be interpreted as the current alpha.3 build result.
- Conservative risk tags for modded blocks (`npcortex:traversal_costly`, `npcortex:traversal_forbidden`) remain; modded collision/effects require real testing.
- Legacy local backend routes remain unauthenticated and must not be exposed publicly. The C+ E-preview endpoint uses scoped local authentication and loopback checks. TaCZ and broader survival/autonomy remain future scoped work, not capabilities included here.

See [PENDING_TESTS.txt](PENDING_TESTS.txt), [Roadmap](docs/roadmap.md), [Airborne Follow verification](docs/c2-airborne-target-projection-windows-checklist.md), [Patch Notes](docs/patch-notes/v0.2.1-alpha.1.md), and [previous Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md). This project uses scoped source audit, options, approval, tests and maintainer-run Windows acceptance. **Only the maintainer performs GitHub operations**; this ZIP does not commit, push, merge or tag anything.

## License

Project code is licensed under [MIT](LICENSE); external model weights, Forge/Minecraft and user-provided assets have separate terms.
