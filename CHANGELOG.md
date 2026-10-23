# Changelog

NPCortex maintains **one consolidated development changelog** for GitHub [`main`](https://github.com/SIRP00M/NPCortex), including historical C1/C2/C0 and C+ implementation milestones. Sprint labels describe development, not independent stable releases. **This candidate is `v0.2.3-alpha.1`** (Forge) / `0.2.3a1` (Python package). Earlier versions are intentionally preserved as historical records.

> This is a **source candidate**, not a tagged stable release or confirmation of full Quality Gates. GitHub was not modified by this packaging process. The `READ_ONLY_VERIFIED` task-graph result refers to read-only evidence, **never completed world mutation**.

## Unreleased — `v0.2.3-alpha.1` source update: read-only task coordinator and typed movement preview (2026-10-23)

**GitHub base:** `main` commit `0618d95` (`v0.2.2-alpha.1`). This is an **expanded candidate within the same `v0.2.3-alpha.1` milestone**, superseding the E-P0-only package in the previous local archive; it is not a separate `alpha.2` release or GitHub tag.

### Added since the earlier `v0.2.3-alpha.1` E-P0-only candidate

- **E1 operator-driven read-only task coordinator:** durable FIFO admission, queue inspection, cancellation and guardrails for in-game requests from the server-side Forge identity; no automatically started task or movement.
- **E2 read-only dispatch/verification:** backend SQLite attempt/lease/receipt records, bounded single-resource `READ_CELL` reservations, backend restart fencing and fail-closed HOLD handling. Requires explicit operator dispatch and verification with fresh C-F2 observations. `READ_ONLY_VERIFIED` does not establish permission to break/place/use blocks.
- **E3-D G1 typed `move_to` preview:** `/npcortex debug cplus taskmovepreview <npc> <x> <y> <z>` on the Forge server checks live C1 actor identity/dimension, motion ownership, Follow/C2 state, uncertain journal, ground contact and loaded/local target. It samples C-F2 world observation and calls the authenticated `/v1/cplus/e3/preview` backend route. The Python validator requires bound IDs, exact typed fields, bounded coordinates, finite tolerance and a fresh, consistent snapshot. SQLite stores deduplicated `PREVIEW_ONLY` intent separately from task FIFO/leases. Returns `DRY_RUN`, `can_execute=false`, `motion_started=false` and `world_effects=false` without navigating or modifying the world.
- New focused tests, scripts and Windows checklists for E1/E2/E3-D G1. Existing C1/C2 source and runtime ownership boundaries are preserved as supplied.

### Version and documentation (this packaging step only)

- Corrected the supplied source's stale Forge/Python version metadata (`0.1.1-alpha`) to **Forge `0.2.3-alpha.1`** and **Python package `0.2.3a1`**, keeping the established milestone number unchanged.
- Continued consolidated historical `CHANGELOG.md` from the previous `v0.2.3-alpha.1` ZIP; refreshed README, `PENDING_TESTS.txt` and the same milestone's Patch Notes. Restored all earlier version-specific Patch Notes in `docs/patch-notes/`.
- **No Gameplay/Python runtime/Forge Java/test source edited by packaging.** The incoming ZIP's implementations are retained byte-for-byte; no GitHub commit/push/tag performed.

### Tests, evidence and safety boundary

- Source candidate's E3 report records **83 focused Python tests passed**, but it also records **full backend suite timeout** and **Windows Forge build + in-game E3 G1 acceptance pending**. Packaging verification results should be considered separately below, not a substitute for Windows tests.
- **This packaging rerun:** 90/90 focused Python E3/E2/E1/E-P0/C-F0/C-F1/C-F2/local-auth tests PASS with Exit Code 0; isolated Java C-F2 read-only authorization policy PASS 16 checks under JDK 21 using `javac --release 17`. Exact-version ZIP CRC and 44 internal links checked. **Forge Gradle compilation and Windows Minecraft gameplay not run here.**
- Pending: JDK 17 Forge compilation, exact command registration and Java↔Python response parity, preview no-motion/no-queue in an actual world, Follow/C2 busy and unloaded/out-of-range rejections, E2 cancel/restart resource-fencing in gameplay, full backend suite termination/exit, 1/3/5/10 NPC MSPT and dedicated/modded compatibility.
- **Not implemented:** E3-D G2 guarded real navigation; E3-D G3 terminal verification/cancel/recovery; E3-E automatic movement DAG; E4/E5 world mutation, mining or autonomous Qwen world-action dispatch. No G1 preview is movement authority.

## Unreleased — Milestone `v0.2.3-alpha.1`: Durable read-only task preview (C-F1 / C-F2 / C-F3 E-P0; 2026-10-23)

**Compared against GitHub `main` HEAD `0618d95`** (`feat: add foundation for NPC task system`, `v0.2.2-alpha.1`). This is an **untagged alpha development source candidate**, not a public release. The packaging step changes only Forge/Python version metadata and docs; the incoming GWI source is preserved byte-for-byte.

### Highlights newly present since `v0.2.2-alpha.1`

- **Reliable diagnostic history (C-F1):** durable journal and explicit operator-driven sync/audit, local authentication and fail-closed recovery checks. These receipts confirm diagnostic transport, **not** Minecraft world mutations, and uncertain operations are never silently replayed.
- **Trusted read-only world observation (C-F2):** bounded, loaded-chunk-only 3×3×3 sampling of actual NPC surroundings, item drops and entities; strict snapshot identity, permissions and unknown/unloaded handling. Modification permissions remain `UNKNOWN`/`DENY` until separately established.
- **Durable read-only task graph (C-F3 Option E, E-P0):** operator `/npcortex debug cplus epreview <npc>` captures a C-F2 snapshot, sends it over an authenticated loopback bridge and writes a bounded, FIFO-ordered three-node dependency graph to SQLite. The backend can report `READ_ONLY_VERIFIED` for observations and permission checks, but **does not break/place blocks, move the NPC, grant mutation permission or run autonomous tasks**.
- Source includes focused Java/Python tests, architecture notes, operator-facing PowerShell runner and Windows gameplay checklists. **E-P1–E-P5 are planned gates, not implemented here.**

### Version and history packaging

- Align Gradle/Forge `mods.toml`/Python `__version__` with **`0.2.3-alpha.1`**, Python distribution with **`0.2.3a1`**.
- Refresh root `ReadMe.md`, update `PENDING_TESTS.txt`, add this milestone's Patch Notes and restore prior version-specific Patch Notes from packaged `main` history.
- Preserve earlier GitHub `main` changelog from `v0.2.2-alpha.1` onward, and insert original incoming C-F1/C-F2 historical notes below. No runtime code or test code modified in this version/documentation step.

### Local packaging verification (2026-10-23)

- ZIP integrity, version consistency and internal Markdown links passed.
- Focused Python tests: **57/57 PASS** (E-P0: 10, C-F2: 16, C-F1 bridge: 9, C-F0: 15, Local Auth: 7), all with reported successful unittest process termination.
- Full Python backend run printed **Ran 284 tests ... OK**, but orchestration timed out and did not capture a reliable exit code; **full-suite process exit remains unverified**.
- C-F2 Java read-only policy self-test: **16 checks PASS**, OpenJDK 21 `javac --release 17` (not Forge compilation).
- No Windows Forge or Minecraft E-P0 test performed in this packaging session.

### Evidence boundary

- The incoming source contains **user-reported scenario-limited** Windows C-F1 H1–H5 acceptance and synthetic diagnostic sync; C-F2 and E-P0 exact-candidate Windows Forge/Minecraft outcomes are not established by this packaging.
- Python/standalone Java tests in this package do not prove Forge Build or real-world GWI action execution. Native 1/3/5/10 NPC MSPT, dedicated server, crash/power-loss and third-party claim-mod permission checks remain pending.


## Detailed C-F1 / C-F2 source milestone history (carried forward from supplied C-F3 candidate)

## Unreleased — GWI C+ C-F2 D: Trusted Perception + Permissions (P1–P5 Candidate)

- Added read-only, opt-in v2 observation from live authoritative C1 ServerPlayer (`/npcortex debug cplus world <name>`), loaded-only 27-cell local block inspection with properties, bounded nearby drops/entities and explicit unknown/coverage.
- Added Java read-only permission decision contract, strict Python v2 validator, world/dimension/actor/session/tick/observation identity and bounded payload fail-closed policies. Observed/unknown, stale/fresh and permission UNKNOWN remain distinct.
- Preserved legacy C0/C2 snapshot and skill advertisements; no world-mutation executor, Saga, Qwen change or new unauthenticated v2 HTTP endpoint.
- Added focused Java/Python tests, PowerShell runner, Windows Gameplay Checklist, architecture and Test Report. Forge gameplay/full backend suite/true MSPT remain pending, not reported as PASS. No GitHub changes.

## 2026-10-22 — GWI C+ C-F1 D+ Final Quality Gate Cleanup (Test/Documentation Candidate)

- Fixed Windows Python 3.13 `test-cplus-f1-auto-local-auth.ps1` quoting failure by calling a standalone isolated Python bootstrap script instead of forwarding nested quotes via PowerShell `python -c`. No manual token setup required.
- Added 3 Python regression tests for script invocation, bootstrap/no-secret-in-logs and invalid-credential fail-closed. Extended existing standalone Java recovery test from 18 to **36 checks**, covering corrupt cursor/hash/sequence and orphan `.tmp` evidence preservation in temporary directories.
- Added combined `scripts/test-cplus-f1-final-gates.ps1` and Windows checklist with real 1/3/5/10 NPC MSPT observation template and disposable-world fault-injection instructions.
- Preserved **ALL production runtime source**, Forge commands, C1 Physics, C2 Follow/Nav, Inventory, SQLite schema and Auto Local Auth behavior byte-for-byte from H1–H5 baseline. No World Mutation, Saga, automatic action replay or GitHub operations.
- Previous operator-confirmed Windows H1–H5 Gameplay A–D and Forge Build remain valid for the original runtime; repaired Windows PowerShell runner, copied-world cursor fault, real MSPT and unexpected power-loss still require separate verification on new candidate. See `docs/cplus-cf1-final-quality-gate-test-report.md`.

## 2026-10-22 — GWI C+ C-F1 D+ Recovery Hardening H1–H5 (Implementation Candidate)

- Separated C-F1 durable ledger IO (bounded worker, queue 32) from manual HTTP/cursor transport (bounded worker, queue 8); slow backend no longer blocks synthetic journal probe/inspect; bounded shutdown and explicit hold on incomplete transport.
- Added immutable ledger delivery snapshot; manual sync still bounded 8 events, no background resend or world action replay.
- Added durable local `syncstatus` reading cursor off-thread, world-wide scope documented; new operator-only `/npcortex debug cplus audit <npc>` checks remote world ID, journal sequence/hash, cursor; READY/PENDING/IN_DOUBT/HOLD are **transport/recovery diagnostics**, NOT real-world verification.
- Preserved orphaned cursor `.tmp` on failure and fail-closed, rather than silently deleting evidence. Kept existing ledger hash/sequence/epoch checks, Automatic Local Auth and SQLite schema unchanged.
- Added Java worker-stall/cursor/reconcile tests, backend process-termination/SQLite-fault/restart tests, synthetic 1/3/5/10 actor isolation, Windows runner/checklist/architecture/test report.
- Preserved C1 Physics, C2 Follow/Navigation, Inventory Ledger and existing commands; **no World Mutation, automatic action replay, Saga, GitHub operations**.
- Pending Windows Forge Build/Gameplay/MSPT, full backend suite, physical power-loss and real world-action crash recovery; see `PENDING_TESTS.txt`.

## C-F1 D+ — Automatic Local Authentication (Candidate, 2026-10-22)

- Automatically generate a private 256-bit C-F1 diagnostic bridge secret at Python backend startup; Forge reads the same per-user file. No clipboard/PowerShell token setup required for local single-player.
- Preserve explicit `NPCORTEX_CPLUS_F1_TOKEN` and optional absolute secret-file override; reject missing/malformed/insecure secrets without silently rotating.
- Keep the C-F1 bridge loopback-only and diagnostic-only; no C1/C2 changes, new mutation actions, SQLite schema migration, replay or GitHub operations.
- Add Python and Java regression tests, parity runner and Windows command checklist. Windows Forge, ACL and gameplay validation remain pending.

## Unreleased — C-F1 D+ D1–D5 Transaction Bridge + Read-Only Recovery (Candidate)

- Added cross-language durable ledger frame protocol with SHA-256 hash-chain parity (Java Modified UTF v1), per-world sequence, actor epoch and event identity validation.
- Added additive SQLite inbox/checkpoint/receipt outbox, localhost-authenticated C+ HTTP routes, idempotent after-commit delivery ACK + independent receipt confirmation. Existing v4 / C2 / Inventory schema unchanged.
- Added bounded operator-triggered `/npcortex debug cplus sync|syncstatus <name>` on pre-existing async worker (8 records per call) with durable local delivery cursor, no automatic world-effect replay. Backend remains authority for **delivery receipt only**, never world-success verification.
- Added read-only recovery status classification and strict conflict handling for gaps/stale epochs/wrong actor/duplicate identifiers/malformed hashes/unsafe stages. No game-world observation or Saga execution.
- Added Java/Python parity, SQLite/HTTP/IO-offline regression tests, Windows runner and dedicated architecture/checklist/report. No GitHub changes.
- **Pending:** Windows Forge build/gameplay, true process-kill/power loss, disk full, end-to-end real world mutation, true 1/3/5/10 NPC MSPT. See `PENDING_TESTS.txt`.

## Unreleased — C-F1 D+ Fix #1: Windows-Safe SQLite Test Hardening (Candidate)

- **Scope-only Windows/Python 3.13 test fix:** corrected three test fixture `with sqlite3.connect(...)` usages in `backend/tests/test_cplus_f1_dplus.py` to reuse existing `sqlite_test_connection()` which commits/rolls back **and explicitly closes**.
- Added two SQLite lifecycle regression tests for successful commit/close and exception rollback/close before temporary-directory deletion; existing C-F1 synthetic/projection assertions unchanged.
- Added focused PowerShell runner and Windows checklist/test report. On Linux CPython 3.13.5: C-F1 15/15, C-F0 15/15, C2 16/16 and persistence 6/6 PASS with `ResourceWarning` elevated to error; unchanged pure-Java suites pass.
- No production SQLite/schema, Forge gameplay, C1/C2, Journal or GitHub changes. **Windows Python 3.13 verification and Forge/gameplay checks remain pending.**

## Unreleased — C-F1 D+ Live Diagnostic Bridge (Candidate; synthetic only)

- Added operator-only `/npcortex debug cplus inspect|probe <name>` to exercise the C-F1 journal asynchronously in a live Forge world without moving NPCs or changing blocks/inventory.
- Added a bounded single-thread worker for durable journal fsync ACKs, restart fencing, world-bound log identity, fail-closed error handling and shutdown lock cleanup.
- Added independent pure-Java worker/restart/corruption regressions and Windows in-game acceptance checklist.
- No automatic world-effect recovery, C1/C2 ownership changes, or active Saga Engine. Forge Windows acceptance remains pending.

## C+ C-F1 D+ — Staged Crash Recovery & E-ready Contracts (2026-10-22)

- Added pure-Java `CPlusDurableLedger` synthetic prototype: bounded append-only checksum/hash-chained records, fsync-confirmed intent, actor epoch fencing and fail-closed log corruption handling. NOT active in Minecraft runtime.
- Added D+ effect-aware recovery guards, partial/unknown/external-consumption outcome contract and future E-compatible event/parent/task identity.
- Added standalone Backend SQLite v4-additive event projection and outbox contract, idempotent ACK checks and Python effect policies. No live HTTP endpoints/migration.
- Added C-F1 synthetic Java and Python regression tests and Windows runner.
- Preserved all original C1/C2/Follow, Inventory, backend legacy execution and commands; no world mutation or GitHub operations.
- **Still pending:** live IO/transport/reconciliation integration, Forge build, real crash windows, real world effects, Windows gameplay and MSPT. This is a staged foundation candidate, not C-F1 final acceptance.

## Previously packaged milestone history (`v0.2.2-alpha.1` and earlier)

## Unreleased — Milestone `v0.2.2-alpha.1`: C+ C-F0 Core Contracts, Ownership & Unified API (2026-10-22)

**Compared against GitHub `main` HEAD `248128c` (`v0.2.1-alpha.1`), not the old C0 or `legacy-main` baseline.**

### Added in this milestone

- Added **standalone, Java-17-targeted C+ contract classes** (`forge-mod/.../npcortex/cplus/`): canonical IDs and action envelopes, target/result typing, guarded lifecycle transitions with authoritative verification receipts, role/ownership boundaries, deterministic resource acquisition and FIFO queue-ticket definitions, and proposed user-facing command mappings.
- Added **standalone Python `cplus_contracts.py`** validator and lifecycle guards with dedicated backend contract tests. Not called by the active C0/C2 service, and does not execute world actions.
- Added C-F0-A through F0-G architecture documentation: responsibility/conflict matrix, v1 protocol and state machine, proposed unified `/npcortex` public commands and backwards-compatible legacy command semantics, windows checklist/test report, independent Java self-test and PowerShell runner.
- Restored prior version-specific Patch Notes (`v0.2.0-alpha.2`, `v0.2.1-alpha.1`) omitted from the uploaded candidate ZIP, and added [`v0.2.2-alpha.1` Patch Notes](docs/patch-notes/v0.2.2-alpha.1.md).

### Version / documentation changes in this packaging only

- Aligned Forge Gradle version + `mods.toml` + Python distribution + Python runtime to **`0.2.2-alpha.1` / `0.2.2a1`**.
- Rebased `ReadMe.md` and `PENDING_TESTS.txt` on the previous packaged `main` milestone; replaced the incoming stale C2-P1-A README. Preserved the consolidated C0–C2/C1/Follow history, historical test reports and separately reviewed owner-reported Windows smoke tests.
- Updated README to point to `main` rather than the former `Development` branch. No prior source Java/Python gameplay runtime file, existing tests, Forge command tree, SQLite schema, dependency, or migrations were modified during packaging.

### Tests, claims and limitations

- **Current packaging rerun:** 71 isolated Java assertions (JDK 21 with `javac --release 17`), 15 Python C-F0 tests and 16 focused Python C2 tests all passed with Exit Code 0. Historical C-F0 report additionally records 22 earlier Follow Java suites. See [`docs/cplus-cf0-test-report.md`](docs/cplus-cf0-test-report.md); neither isolated Java/Python testing nor the historical report is a Forge Build result.
- **Maintainer-reported Windows acceptance:** spawn/identity, grounded Follow, flying-target Follow without the observed zigzag regression, unfollow/cancel, 3D navigation, inventory replay, Save/Rejoin and Forge Build + contract tests passed in the scenarios tested. Reported acceptance is separate from local-source inspection; do not infer 1/3/5/10 MSPT, multiplayer, crash-injection, live C+ commands or every hazardous-terrain fixture.
- **No live C+ engine yet:** all unified routes (including `/npcortex spawn`, `/npcortex stop`, `/npcortex debug ...`) remain **proposals**, not registered commands. No new C+ gameplay world writes, FIFO runtime scheduler, verified real-world action receipt pipeline, persistence migration or cross-process wire integration.
- C-F1–C-F6 remain separate future scope, requiring a fresh audit, choices and explicit user approval; update `PENDING_TESTS.txt` as real evidence arrives.

## Unreleased — Development milestone `v0.2.1-alpha.1`: Ground-projected Airborne Follow and terrain-native stability (2026-10-22)

### Changes in uploaded source since `v0.2.0-alpha.2`

- **Airborne Follow Target Projection:** distinguish confirmed Creative/elytra flight from ordinary jumping with debounced state transitions (2 ticks to enter, 4 to exit); project airborne player target onto reachable, loaded local ground support for Follow scoring; use horizontal XZ-only stop-distance when the player is genuinely flying. If a safe local support cannot be found, use a neutral reference height **without inventing a path**.
- Add target-projection diagnostics in `/npcortex c2 followstatus`: `targetMode`, `targetProjection`, `projectedY`. Invalidate speculative plans and unstarted routes on flight-mode transitions while retaining C1's exclusive ownership of active motions.
- **Stable Trajectory Follow B:** damp lateral overcorrection, permit physically verified intermediate-waypoint crossing and reduce unnecessary near-stop wobble without changing final C1/C2 receipt tolerances.
- **Terrain-Native / Smooth Follow Hardening C:** bounded local detour stabilization, adaptive corridor continuity, preparatory continuation hints and latency/hand-off telemetry; speculative route suggestions do not move the NPC before confirmation.
- **Persistent 3D Follow C:** bounded lease of mixed WALK / STEP_UP / STEP_DOWN / JUMP_UP / SAFE_DESCEND stages, preserving one C1 motion owner where each edge and subsequent physical receipt is verified; C2 Navigate remains separately controlled.
- This is an increment to the existing C1/C2 player-physics framework, **not flying NPC locomotion, autonomous tree harvesting, a global terrain map or guaranteed smoothness**.

### Versioning, documentation and integrity

- Bump four authoritative version declarations to Forge `0.2.1-alpha.1` and Python distribution `0.2.1a1`.
- Repair stale source README against the packaged `alpha.2` Development README and update with all newer C2 features, gameplay limits and Windows commands.
- Keep the complete prior consolidated C0 → C1 → C2 `alpha.1` / `alpha.2` chronological history; include all newly uploaded implementation milestone notes immediately below.
- Add [`v0.2.1-alpha.1` Patch Notes](docs/patch-notes/v0.2.1-alpha.1.md), preserve [`v0.2.0-alpha.2` Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md), and carry forward the comprehensive [PENDING_TESTS.txt](PENDING_TESTS.txt) with specific maintainer gameplay confirmation.

### Evidence and remaining gates

- **Owner-reported Minecraft PASS:** previously observed flying-target-related zigzag disappeared with the Airborne Follow Target Projection Fix. This is not a 14-case full FLY checklist PASS.
- **Owner-reported earlier terrain recovery:** terrain-native follow can temporarily pause while finding a safe route, then resume without manual intervention in tested terrain. Other terrain/cancel/performance scenarios remain pending.
- **Fresh tests on this repackaged ZIP:** 22/22 isolated pure-Java self-test suites, **2,658 assertions PASS**, all Exit Code 0 with JDK 21 and `javac --release 17`; focused Python C2 tests **16/16 PASS, Exit Code 0**. These do not compile Forge-bound Java classes or certify Minecraft gameplay. The uploaded candidate report independently documented the same suite counts.
- Forge `compileJava`/build on Windows JDK17, exact alpha.3 client visuals, FLY-01–FLY-14 completion, STEP_DOWN and mixed terrain, cancellation/combat, save/rejoin, loaded/unsafe terrain behavior, mod compatibility, and 1/3/5/10 NPC Forge MSPT **remain open where not individually confirmed**. The ZIP integrity, version metadata consistency and 30 relative Markdown links across the README/CHANGELOG/current and previous patch notes were also checked in this packaging session.

## Detailed C2 implementation notes added since the previous packaged version

## 2026-10-22 — Airborne Follow Target Projection (Creative flight-Y oscillation; candidate)

- **Baseline:** Stable Trajectory Follow B full repository; user confirmed zigzag is noticeably associated with flying Player targets. Scoped Follow-only change, no GitHub operations.
- **Audit:** Stop-distance and vertical reach used the player's world Y even during flight; terrain-native planning also received aerial Y in normal plans, live extensions and predictive hints. This caused inconsistent stopping and goal-height scoring for a grounded NPC following an airborne player.
- **Implementation:** Added pure Java `C2FollowTargetProjection`, with 2-tick flight entry and 4-tick landing/exit debounce (actual Creative flying/elytra, not ordinary jumps). Airborne stop-distance uses XZ and ignores the unattainable aerial vertical-difference pause. Every terrain-native Follow plan uses ground-height projection from currently verified **reachable** local directed graph nodes near player XZ, or an actor-footY neutral fallback outside scan/over unsupported terrain. No fabricated support, no force-load, no unapproved C1 movement.
- Clear speculative hints and unstarted routes on target flight/ground transitions. Existing active C1 token retains ownership and normal cancellation/combat/landing verification; generic C2 Navigate and C1 physics untouched.
- Added `targetMode`, `targetProjection`, `projectedY` to read-only Follow status, new regression suite + Windows runner, test report and gameplay checklist.
- **Local verification:** 22/22 Pure Java regression suites (2,658 assertions) PASS using `javac --release 17` on JDK21; focused backend C2 16/16 PASS. Forge compilation, flight gameplay and 1/3/5/10 NPC MSPT **PENDING** (Gradle distribution unavailable here). This does not certify all zigzag resolved.

## 2026-10-21 — C2 Follow Stable Trajectory B (implementation candidate)

- Scoped correction of flat Follow steering wobble: lateral deadband/damping along verified segments; certified waypoint remains the aim near segment end.
- Conservative arrival braking and physical intermediate waypoint crossing; final C1/C2 receipt tolerances unchanged.
- Freshly verified follow target preferences retained across small player moves only while preferred target remains ahead and inside the local scan window.
- Added `C1F2TrajectoryArrival` and `C2StableTrajectoryFollowBSelfTest`; added PowerShell regression script, test report and Windows acceptance checklist.
- Tested: 21/21 pure Java suites, 2,614 assertions PASS; Backend C2 16/16 PASS. Forge compile and gameplay **PENDING** due to missing Gradle 8.8 distribution in this environment.
- No GitHub operations and no changes to C1 owned physics, combat, collision/hazards, cancellation or 3D traverse receipts.

## 2026-10-21 — C-TN Smooth Follow Hardening C (S1–S4, IMPLEMENTATION CANDIDATE)

- S1 — Diagnostic-only `C2FollowSmoothMetrics` tracks movement vs unnecessary wait/intentional HOLD in server ticks, max consecutive wait, terminal handoffs, preplan attempts/hits/misses. Exposed by `/npcortex c2 followstatus <npc>`; no motion/physics permission derives from telemetry.
- S2 — Add bounded, immutable non-authoritative `C2FollowPreparedContinuation` route hints while the CURRENT owned C1 ticket moves and an approved endpoint is inside the local snapshot. A hint is keyed by exact C1 token, endpoint, moving-player position and age. It is single-use and invalidated on cancellation/failure/target relocation, token change, mismatch or timeout. Only physically VERIFIED C1 completion can promote it to an unstarted route; next server tick still reruns existing C1 graph, swept-collision, hazard and 3D landing safety. Never execute from prediction. Retains one global follow plan batch per tick and one preplan attempt per token+endpoint, with 10-tick minimum spacing.
- S3 — Detour-side stability across small target movements: retains selected left/right detour for player drift below 6 blocks from the chosen detour anchor; resets after large relocation or a successful normal corridor. Does not bypass terrain checks, failure backoff or bounded replanning.
- S4 — New `C2SmoothFollowHardeningCSelfTest` (31 pure Java assertions) and Windows script `scripts/test-c2-smooth-follow-hardening-c.ps1` running 20 pure Java suites. Existing navigation (C2 navigate), C1 physics, authoritative C1 ownership, Mixed 3D, cancellation/combat and world collision code remain unchanged.
- Focused run: 20/20 pure Java suites / 2,591 assertions PASS; backend C2 tests 16/16 PASS; Java17 parser PASS (5 units). Pure local graph planning microbenchmark ~1.86ms/plan for a fixed 169-node/1200-edge synthetic fixture (NOT Minecraft MSPT, NOT multi-NPC load).
- NOT VERIFIED: Windows Java17 Gradle Forge build and actual Minecraft smoothness/transition, cancellation/combat/lifecycle/3D regression, 1/3/5/10 NPC performance. Local Gradle wrapper cannot fetch distribution. No GitHub changes.

## 2026-10-21 — C-TN Persistent Terrain-Native Follow Controller (Candidate)

- Replaced Follow-only shortlist-of-4 geometrically ranked destinations with bounded single-pass reachable directed 3D graph route selection; real edge cost/risk, heading-change penalty, stable target preference, and support-aware corridor choice. Does not change normal C2 Navigate or C1 movement physics.
- Added bounded lateral-detour fallback (maximum six corridor selections, keeps detour side when reasonable) when a safe route must initially move sideways around a ravine. All edges must still pass fresh world/C1 validation; no infinite global planner or direct-to-player collision bypass.
- Added repeated but rate-limited fresh terrain recovery windows (40/80/120 server ticks), avoiding permanent one-shot stationary target wait and per-tick heavy probing.
- Added Follow telemetry `terrainDecision`, `terrainReprobes`, `detourHops`, `terrainSelections`, and `lastFailure`.
- Added 36 new Pure Java contract assertions, 19/19 suites 2,560 assertions passing locally, and Windows build/gameplay/test report. **Forge compilation, physical movement, 1/3/5/10 MSPT and full quality gate remain PENDING.** No GitHub operations.

## 2026-10-21 — Persistent Follow C-3D: Integrated Mixed 3D Locomotion (CANDIDATE; WINDOWS BUILD/GAMEPLAY PENDING)

- **Baseline:** C-S1–S4 Persistent Follow C Build Fix #1 A, user-reported smoother flat Follow and observed `persistentAppends` up to 33. User selected C to improve pauses at obstacles/elevation transitions.
- **C-3D:** Added an opt-in Follow-only bounded mixed lease for verified WALK/STEP_UP/STEP_DOWN/JUMP_UP/SAFE_DESCEND segments, at most six stages with one C1 owner/token and a per-stage fresh graph, loaded chunk, collision, risk, support, headroom and landing verification. Each vertical stage retains the original C1S2 `verify()` receipt; cannot report success solely based on planning. Falls back to the previous standalone typed C1 primitives if mixed proof cannot be obtained. Static `c2 navigate` unchanged.
- **Safety:** Preserve real `Player.travel()` / `jumpFromGround()`, combat priority, operator cancellation exact `CANCELLED` terminal, no Save/Quit replay, bounded budgets, fail-closed unknown terrain and no GitHub changes. New read-only `mixedStage` in C1 motion status and `mixed3DStarts` in F1 followstatus.
- **Verification:** Pure Java 18/18 suites, **2,524 assertions PASS** (including 34 new), 5 Forge-side source Java syntax parse PASS, focused backend C2 16/16 PASS. **Forge compile remains blocked** by Gradle 8.8 DNS resolution in this environment, Windows gameplay/full quality gate PENDING. Do NOT infer performance, modded terrain or 3D gameplay PASS.
- **Artifacts:** Dedicated `scripts/test-c2-persistent-3d-c.ps1`, `docs/c2-persistent-3d-c-test-report.md`, `docs/c2-persistent-3d-c-windows-checklist.md`; outstanding work in existing `PENDING_TESTS.txt`. Single `CHANGELOG.md` retained.

---

## Previous packaged Development history — retained verbatim below

## Unreleased — Development `v0.2.0-alpha.2`: Navigation, Player-like Follow and Persistent Motion (2026-10-21)

### Version and documentation (packaging-only)

- Advanced Forge `mod_version` and `mods.toml` from the source ZIP's stale `0.1.1-alpha` to `0.2.0-alpha.2`; aligned Python package version as `0.2.0a2` and runtime `__version__` as `0.2.0-alpha.2`. This continues the previously packaged `v0.2.0-alpha.1` development line, **not** the old C1/C0 identifiers in the input ZIP.
- Updated `ReadMe.md` to reflect the code now present through C2-P1-B1/B2/B3/C/D, 3D Terrain Sprint 1–4, Follow F1/F2, B+ steering and persistent Follow C-S1–C-S4; removed stale statements that these were unimplemented.
- Added [human-readable Patch Notes](docs/patch-notes/v0.2.0-alpha.2.md) for this candidate, and updated `PENDING_TESTS.txt` without dropping the earlier detailed checklist. **`CHANGELOG.md` remains the only consolidated chronological changelog.**
- Preserved detailed new C2 milestone history from the uploaded ZIP below and the prior packaged `v0.2.0-alpha.1` and C1/C0/B/A historical entries after it. No runtime Java/Python, data migrations, test logic, Gradle dependencies, GitHub branches or tags were changed by this packaging operation.

### Highlights present in this source (not all gameplay-certified)

- Reliable A* / hierarchical region planning; transactional C2 waypoint execution, cancellation and bounded recovery; Terrain-Aware 3D inspection, steps, jump and safe descent, plus responsive dynamic replanning.
- Follow Player with bounded local rolling targets, player-collision parity/risk-aware traversability, turn-aware paths, 360-degree safe corridor local steering, and client-side locomotion presentation.
- Persistent bounded Follow Motion Session: when fresh graph, token and live swept collision checks permit it, append verified flat WALK segments without retiring the same C1 motion token at every short route boundary. This does not create global navigation, teleport, unbounded motion or autonomous restart replay.
- Latest `C2FollowRuntime` BuildFix1 corrects an out-of-scope `extended` variable compilation error in two lines, **but Forge success is unverified for this patched candidate**.

### Acceptance boundary

- Prior maintainer-reported Windows Minecraft: basic flat navigation/obstacle avoidance; Sprint 2 STEP_UP +0.5, JUMP_UP +1.0 and SAFE_DESCEND -1.0 on valid fixtures; Sprint 3 automatic JUMP_UP and SAFE_DESCEND; Sprint 4 main recovery scenarios; basic F1 Follow Player; and an observed visual improvement from newer adaptive steering. These are **scoped observations**, not a pass for all C2 safety gates or for this final Persistent Follow BuildFix1 ZIP.
- **Packaging-session regression rerun (this ZIP): PASS** — all **17 isolated Java suites / 2,490 assertions** compiled with `javac --release 17` and executed on OpenJDK 21 with exit code 0; focused backend `test_c2_*.py` **16/16 tests PASS** with clean exit 0. This matches the source-carried BuildFix1 test report, but still does **not** compile any Forge/Minecraft-dependent classes or certify JDK 17 runtime behavior. Windows Forge rebuild, real Follow gameplay and 1/3/5/10 NPC MSPT remain open. Review `PENDING_TESTS.txt` and the [Windows checklist](docs/c2-persistent-follow-session-c-windows-checklist.md).

---

## Detailed C2 implementation history carried from the uploaded candidate

## 2026-10-21 — Persistent Follow C Build Fix #1 (Option A: minimal compile fix; WINDOWS REBUILD PENDING)

- **Baseline:** `NPCortex_C-S1-S4_PersistentFollowMotionSession_C_FullRepository_CANDIDATE.zip`; user-provided Windows JDK 17 Forge `compileJava` evidence: `C2FollowRuntime.java:223: cannot find symbol: variable extended`.
- **Fix:** In `C2FollowRuntime.tick`, move `boolean extended=false;` to the enclosing `ticket.active()` scope before the route-extension conditional, and change the inner statement to assign `extended=...` instead of declaring a shadow/local variable. **Exactly two source lines changed.** Short-circuit logic, fallback and safety checks are otherwise unchanged.
- **Out of scope / unchanged:** C1 motion ownership, transactional cancellation, combat, 3D Navigation, route planning, watchdogs, Vanilla physics, Gradle dependencies and unrelated `ModLoadingContext.get()` deprecation warning. No GitHub actions.
- **Verification here:** Pure Java `javac --release 17` 17/17 suites / **2,490 assertions PASS** and focused backend C2 **16/16 tests PASS**. These tests do **not** compile `C2FollowRuntime` with Forge. **Windows JDK 17 Forge Gradle build on the patched ZIP is PENDING**; do not mark the original user-reported build failure resolved until the user rebuilds. Windows gameplay and 1/3/5/10 NPC MSPT remain PENDING.
- **Docs:** Same `CHANGELOG.md`, updated `PENDING_TESTS.txt`, and `docs/c2-persistent-follow-session-c-test-report.md` (Build Fix #1 section).

---

## 2026-10-21 — C-S1..C-S4 Persistent Follow Motion Session C (IMPLEMENTATION CANDIDATE; Forge/Gameplay PENDING)

- **Baseline:** B+ Hybrid Any-Angle + Continuous Adaptive Steering Candidate. Explicit implementation approval C-S1..C-S4, no GitHub operations.
- **C-S1:** Follow maintains its ephemeral C2 Session while C1 keeps a single owned motion token across multiple dynamically approved flat route windows. Bounded cumulative C1 walking lease enlarged to 600 ticks, 64 targets / 96 approved blocks; no extensions in last 80 ticks (clean terminal drain); C2 failure, segment/route watchdog and terminal receipts remain. Actual terminal/cancel still zeros horizontal speed; no new physics controller.
- **C-S2:** Added fresh-graph rolling path continuation anchored at current committed endpoint. Require loaded local snapshot, pure-WALK same-elevation edges, bounded planner cadence, matching C1 token and non-combat owner, fresh graph edge + risk and live player-width swept corridor for EACH added leg. Atomically extend C1 before C2 receipt route. Route journal remains session-level; never persist transient movement or replay Follow after restart. New `followstatus` counters `persistentAppends` / `rollingMisses`.
- **C-S3:** Corrected zero-forward turn-in-place preflight: no speculative forward collision probe when forward input is zero. Inertia is cleared and Vanilla travel tick retained; nonzero forward still checks loaded chunks, terrain, hazard and swept collision.
- **C-S4:** Added new 68-assertion pure-Java safety/route/token/turn regression, dedicated Windows JDK17 runner and game acceptance report/checklist. Static C2 Navigation, 3D traversal, combat and C0 ownership unchanged. No GitHub operations.
- **Evidence:** `javac --release 17` JDK21: 17/17 pure Java suites / 2,490 assertions PASS, focused backend 16/16 PASS. **Forge Build BLOCKED** due unavailable Gradle 8.8 download; **Gameplay and 1/3/5/10 NPC MSPT PENDING**. Do not interpret isolated tests as real Forge acceptance.

---

## 2026-10-21 — B+ Hybrid Any-Angle + Continuous Adaptive Steering (CANDIDATE; Windows Gameplay PENDING)

- **Baseline:** C-L1..L4 Full Locomotion Animation Controller C Full Repository Candidate. Explicit H1–H4 approval; no other architecture rewrite and **no GitHub operations**.
- **H1 Follow goal stability:** `C2FollowPlanner.selectRolling` scores up to four reachable, freshly planned rolling targets with forward progress, lateral error, path cost and a bounded preference for the previous target. Changes apply only to Follow; ordinary C2 navigation/agent A* remains unchanged. Verified final goals remain graph standing nodes; sub-block precision is introduced at the local steering stage, not by inventing an unverified moving target/landing coordinate.
- **H2 certified 360° flat corridors:** added `C2BPlusCorridor`, a bounded, player-footprint-wide (.32 half-width) level, zero-extra-risk Graph certificate, replacing only the Follow-only overly conservative enclosing-rectangle shortcut proof. Require all touched standing cells, same foot height, bidirectional directed WALK edges and ordinary edge cost. No direct shortcut over missing ground, unknown/unloaded cells, risky floor or unverified jumps/slabs/stairs. `C2WaypointBridge.sweptFollowCorridorClear` samples the live player-volume along Follow chains/extensions (<=48 steps at <=.16-block interval), rechecks loaded chunks/world-border/collision/block risk. The original `sweptWalkClear` stays unchanged for static C2 Navigation and existing C1 per-tick fail-closed checks.
- **H3 continuous local heading:** added `C1F2AdaptiveSteering` to steer toward a sub-block lookahead projected on the *current certified horizontal segment* instead of aiming at its endpoint every Tick; 360° yaw remains under `C1VanillaMoveControlAdapter`/`C1PlayerAvatar.travel()`. Never blend across a corner without a chord certificate; off-lane input falls back to the original committed endpoint. Never change the receipt target or the terminal verification coordinate.
- **H4 safety + bounded recovery:** keeps one original C1 owner/ticket per committed Follow WALK chain; unchanged C1 Combat ownership and Cancel, loaded-chunk safety, 3D primitives, bounded 4-candidate/3000-expansion plans and 10-actor cap. Adds pure Java B+ regression and a Windows Java17 test script, and repairs old self-test scripts' explicit source dependency lists. Any existing Follow pause/cooldown, real-world corner collision or risk condition can still stop motion and request bounded recovery; smoothness is **not** gameplay-certified.
- **LOCAL TESTS:** `javac --release 17` using installed JDK21: **16/16 suites, 2,422 assertions PASS**, including 18 new B+ assertions. **Backend C2 focused 16/16 PASS**. Full backend suite emitted **201 tests / OK** in log, but the outer command timed out; do not claim a clean timed runner exit. **Forge build BLOCKED before compile** (`UnknownHostException: services.gradle.org`, Gradle 8.8 unavailable). Windows Forge build, real gameplay, collision/smoothness, 1/3/5/10 NPC MSPT and compatibility remain PENDING. No GitHub operation performed.

---

## 2026-10-21 — C-L1..L4 Full Locomotion Animation Controller C (CANDIDATE; Gameplay PENDING)

- Approved scoped C-L1..4 based on Turn-Aware Safe Smoothing B baseline. Added transient per-avatar `C1LocomotionPresentation` phases (IDLE/TURNING/STARTING/WALKING/CORNERING/STOPPING/INTERRUPTED). Display via existing C1 motion status.
- Split physical movement yaw from visual head/body pose; capped per-tick body/head turns with bounded head lead and twist using shortest-arc wrapping; ease-out follow yaw turn cap reduced from 32° to 24° with forward braking and collision checks retained. Vanilla Player.travel, collision, C1 ticket tokens, combat priority, cancel, and C2 path safety unchanged.
- Added `C1ClientRotationBlend` and revision-scoped client END-tick pose easing for verified RemotePlayer views; first rotation snapshot restores angles, subsequent updates no longer force custom yaw over Vanilla tracker. World/token/death transitions retain viewer fencing.
- Added 52-assertion C pure Java test and Windows Java17 runner, test report/checklist. **15/15 isolated suites / 2,404 assertions PASS**; Forge build **BLOCKED** by missing Gradle 8.8 distribution (`UnknownHostException services.gradle.org`); actual Forge compilation, animation visuals, gameplay, multiplayer and 1/3/5/10 NPC MSPT **NOT VERIFIED**. Focused C2 Python backend regression **16/16 PASS** (backend code unchanged).
- Exactly one CHANGELOG.md retained; PENDING_TESTS.txt updated. No GitHub operations.

## 2026-10-21 — C2-F2 Turn-Aware Path Planning + Safe Smoothing B (Candidate; Windows Acceptance PENDING)

- Added follow-only bounded directional A* (`C2TurnAwarePlanner`) with small turn penalty **on top of** existing physical directed-edge and risk costs. Static C2 operator Navigation hierarchy unchanged.
- Added conservative graph-certified, max-3-cell any-angle flat WALK shortcut selection (`C2F2SafeShortcut`), with fresh-graph execution revalidation; untouched C1 motion ownership, physics, swept collision and hazard checks.
- Preserved all vertical traversal edges and verified original nearest follow ring/HOLD/cancellation contracts.
- Updated isolated PowerShell regression scripts and added `scripts/test-c2-turn-aware-b.ps1` plus Windows test report/checklist.
- Local Java 17 target compilation and 14 isolated pure-Java suites **PASS (2,352 assertions)**. Forge Gradle build **BLOCKED by DNS resolving services.gradle.org**; actual Forge gameplay and MSPT **PENDING**.
- No GitHub commits, pushes or branch operations.

## 2026-10-21 — C2 Generalized Block Traversal C (CANDIDATE; WINDOWS GAMEPLAY PENDING)

- **Approved C1–C4 Scope**: Player Collision Parity over native `noCollision` and support shapes; independent passable/costly/forbidden risk; directional weighted graph edges; integration with Follow and C2 Motion preflight; no PathfinderMob or replacement Player physics.
- Added `C2BlockTraversalWorld.java` for conservative, bounded server-world risk classification across full player-sized volumes. No passability block whitelist: vanilla or modded plants without blocking collision may be crossed; risk severity for selected vanilla hazards and optional datapack tags is separate. Forbidden/unloaded remains fail-closed.
- `C2NavGrid.Cell` now carries `riskPenalty` while retaining the five-parameter constructor; `C2TerrainObservation.Risk.AVOIDABLE` represents physically traversable but costly terrain, including underlying floor block risks. Added `C2TraversalCost.java` (pure policy).
- `C2TraversabilityGraph.build3DWithRisk` adds positive risk costs from swept volume and source/target surfaces to every verified directed movement edge. Diagonal, reverse-edge validation, step, landing, movement ownership and time-of-check revalidation remain. `C2GraphWorldProbe`, `C2WaypointBridge`, `C1P1MotionRuntime`, `C2AgentRuntime` now distinguish soft from hard risks.
- Added extension tags `npcortex:traversal_costly` and `npcortex:traversal_forbidden`, Java isolated regression runner and 23-assertion C suite. **PASS** 13 Java `--release 17` pure-Java suites / 2,331 assertions on JDK21; **Forge compile BLOCKED** by Gradle 8.8 distribution DNS; actual Windows/Forge/1-3-5-10 NPC gameplay still **PENDING**. Backend unchanged, not rerun; no GitHub operations.
- See `docs/c2-generalized-traversal-c-test-report.md`, `docs/c2-generalized-traversal-c-windows-checklist.md` and `PENDING_TESTS.txt`. Changes are NOT certified on Minecraft server runtime yet.

## 2026-10-21 — C2-F2 Adaptive Follow B (CANDIDATE; NOT GAMEPLAY VERIFIED)

- **Approved Scope B:** Follow-only reachability-prefiltered local goal selection, previously failed route avoidance / ring-goal hysteresis, safe corner steering with turn/endpoint deceleration, and one bounded delayed stuck-recovery re-probe; preserve C1 Custom ServerPlayer physics, all transactional motion ownership and C2 navigation isolation.
- `C2FollowPlanner.java` uses directed graph reachability to reserve the existing 4 expensive hierarchical plans for candidates that can be reached topologically; supports read-only per-session avoid hints and stable preferred ring position; immediately HOLD if already on a verified follow-ring node. `C2FollowAlternatives.java` keeps at most 8 recently failed waypoint/goal nodes in memory per active follow session.
- `C1F2ContinuousWalk.java` no longer blends toward an unverified corner-cut chord; maintains intermediate walk ticket continuity and conservative waypoint/receipt verification. `C1VanillaMoveControlAdapter.java` adds near-corner deceleration without replacing Player.travel.
- `C2FollowPolicy.java` + `C2FollowRuntime.java` add a single 120-tick delayed fresh-graph recovery window after the existing 3 failures while stationary, then fail closed until target movement. Follow status now reports `avoided`.
- Pure JVM 7-suite Java17-target regression validation; one obsolete Fix #2 look-ahead assertion updated to enforce the safer new steering contract; new 26-assertion B regression suite and Windows test script. **Not an in-game PASS.**
- Actual Forge build blocked by missing local Gradle 8.8 distribution and DNS; full backend test attempt incomplete; Windows gameplay/performance/3D/combat/ownership gates remain pending. No GitHub operations performed. See `docs/c2-f2-adaptive-follow-b-test-report.md`, checklist and `PENDING_TESTS.txt`.

## C2-F2 Fix #3 — Vanilla-inspired Follow MoveControl Adapter A (2026-10-21; CANDIDATE)

- Baseline: C2-F2 Fix #2 Full Repository Candidate. Source-audited repeated short Follow tickets and zero extension events in user logs; follow movement worked but visual smoothness not accepted.
- Added Follow-only, pure-Java `C1VanillaMoveControlAdapter` for bounded yaw rotlerp and smoother forward input speed changes, without inheriting `Mob`, using Minecraft `MoveControl` directly or adding a second physics owner. Existing grounded/swept collision validation, C1 `Player.travel`, 3D typed traversal, combat, cancellation and token ownership preserved.
- Permit quicker **bounded** local follow replanning only after verified route terminal (2-tick floor), preserving 20-tick failure backoff and global planner-per-tick guard; no new C0 goals on follow.
- Added `C2F2VanillaInspiredFollowASelfTest`, `scripts/test-c2-f2-fix3-vanilla-adapter.ps1`, report and Windows checklist. Local **30 Java self-test suites / 5,096 assertions PASS** targeting Java 17 on JDK21; Backend **16 C2-focused tests PASS**. Full Backend suite **TIMED OUT** in this run (unchanged code). Forge compilation **BLOCKED** by unavailable Gradle wrapper distribution. Real Windows visual smoothness/gameplay and 1/3/5/10 NPC MSPT **PENDING**.
- Known limit: C1 still stops horizontal velocity at independent Follow route terminal; no claim of PathfinderMob-level visual parity, persistent cross-route movement or Forge gameplay PASS. No GitHub operations; `PENDING_TESTS.txt` preserved, exactly one CHANGELOG.md.

---

## C2-F2 Fix #2 — Follow Steering and Motion Telemetry (Architecture C; 2026-10-20; CANDIDATE)

- **Baseline:** C2-F2 Fix #1 Full Repository Candidate. User observed improvement over F2 but still not PathfinderMob-like visual smoothness; no claim of visual PASS.
- Added pure-Java `C1F2MotionTelemetry` per owned Follow WALK ticket (distance, nearStationary, zero-forward, turns, extensions, waypoints), single terminal `WALK_TELEMETRY` INFO line and live owned-ticket progress in `c2 followstatus`. No per-tick log flood; client interpolation requires Windows observation.
- Extended `C1F2ContinuousWalk` with short look-ahead steering around verified flat turns, retaining yaw rate and fail-closed ground/collision checks. Added bounded same-ticket precommit from `C2WaypointBridge.extendFollowWalkChain` backed by new `C2WaypointExecution.walkExtensionAllowed` and exact C1 token/owner validation. Up to 24 queued nodes / 32 total blocks / 240 ticks per owned session; extension never crosses vertical traversal and does not extend when player substantially changed direction.
- Pure Java test `C2F2MotionSmoothingFix2SelfTest` (42 new assertions), Windows script `test-c2-f2-fix2-smoothing.ps1`, architecture note, test report and Windows checklist. **29 Java suites / 5,068 assertions PASS**, unchanged Backend **201 tests OK** (verbose rerun), Forge-facing **mock API compilation PASS**. **Forge Gradle build BLOCKED by 8.8 DNS; Windows Gameplay, client visual smoothness, MSPT and all other PENDING_TESTS remain NOT VERIFIED.**
- Scope guard: no new world interaction, C0/Backend schema, PathfinderMob, teleport, force-loaded chunks, multi-chunk global planner, Vanilla combat/physics modification or GitHub operations. **Still discontinuous at independent local Follow route terminal boundaries; don't claim fully seamless path-mob quality.**

---

## C2-F2 Fix #1 — Continuous Player-like Locomotion (Architecture C; 2026-10-20; CANDIDATE)

- Baseline: C2-F2 Full Repository Candidate. Source Audit traced stop/start jitter to per-ticket `stopHorizontalVelocity()`, idle pre-turn in Follow and client `*RotO` interpolation resets. **This fix does not change ordinary C1 operator movement, C0 navigation, combat impulses, jump/descend physics or C0 journaling**.
- Added bounded Follow-only `C1F2ContinuousWalk` steering (max 6 flat targets, 7.75 total blocks, 200 ticks, stall/cancel/height validation), executed within a **single owned C1 Vanilla `travel()` session** and one correlated C1 ticket across multiple verified WALK nodes. Face turns <=18 degrees/tick during motion; sharp unsafe turns get zero forward input until aligned enough. Velocity only stops on terminal receipt, cancel or unsafe condition, not every flat intermediate waypoint.
- Added Follow-only multi-node receipt in `C2WaypointExecution` and preflight in `C2WaypointBridge`: verify every directed graph edge, world loaded/border and swept full-player collision before issuing a token; check current heading ahead, floor support/hazard and AABB collision each authoritative C1 tick. Terminal receipt must match token and reached final node; typed 3D primitives **never** join WALK chain. Combat cancels stale Follow chain then follows existing Vanilla knockback and safe replan behavior.
- For rotation, preserve `*RotO` endpoints on subsequent verified client snapshots (reset only first snapshot per viewer token), cap active C1 explicit rotation sync at once per 2 ticks, and downgrade per-revision rotation sync/ack INFO logs to DEBUG. Viewer revision validation unchanged.
- Added one pure Java contract self-test (`C2F2ContinuousFollowFix1SelfTest`, **41 assertions**) and `scripts/test-c2-f2-fix1-continuous.ps1`; architecture, Test Report, Windows Checklist. Local `javac --release 17` **28 pure Java suites / 5,026 assertions PASS**, Backend **201 tests OK**, Follow/Waypoint mock API compilation PASS. **Real Forge compile blocked by Gradle 8.8 download DNS (`UnknownHostException`) before compile; Windows gameplay/MSPT/packet metrics PENDING**. No GitHub operations, no new Qwen/Interaction Skills/multi-chunk navigation.

---

## C2-F2 — Natural Player-like Locomotion & Rolling Local Follow (Architecture C; 2026-10-20; CANDIDATE)

- Built **only** on the C2-F1 Follow Candidate, preserving C1 Vanilla travel/jump/combat motion ownership, C0 transactional navigation/cancel, S4 Fix #1 recovery, one CHANGELOG.md and one PENDING_TESTS.txt. No GitHub operations.
- C2 live 3D NavGrid now has verified **flat-only diagonal WALK** transitions, Euclidean cost `sqrt(2)` and an admissible Euclidean lower-bound heuristic in B2/B3. Both orthogonal alternatives must consist of existing verified, bidirectional WALK edges, **and** the direct diagonal sweep must pass the world collision probe; otherwise no diagonal edge. No diagonally jumping, drops, unknown chunks or wall corner-cutting.
- Kept the old 4-way `C2TraversabilityGraph.build()` and legacy waypoint constructor strict for historical regression; C2 live static navigation uses a separate verified-3D route factory. Follow's separate factory permits at most **3 collinear same-height WALK edges** to be combined into one C1 motion ticket, but every omitted edge is reverified on a fresh graph at start, as is a loaded/world-border-safe **AABB swept from the avatar's current physical position** to the endpoint. No skipped jump/step/landing receipt, and no change to C1 physics.
- Follow-only pre-segment **yaw interpolation** (<=18 degrees/server tick) reduces instantaneous facing changes while the NPC has no active C1 ticket; after heading alignment, original `C2WaypointBridge` issues exactly one owned C1 motion ticket. This is not an animation overhaul, acceleration model or mid-motion steering; animation/client smoothness remains for Windows visual acceptance.
- **Rolling local Follow** replaces the F1 hard pause at approximately 8 blocks horizontal separation. For far-but-same-dimension targets, choose a finite safe forward frontier **inside the radius-6 currently loaded local graph**, walk there, rescan/replan once C1 verifies progress, and repeat under existing cooldown / four-candidate / planning budget / failure budget. Never teleport, force-load chunks or build a multi-chunk persistent planner. Unknown terrain, unsafe/no-path, dimension changes, foreign C1 motion, death, offline, canceled sessions and reboot remain fail-closed. No C0 Goal per follow hop.
- New `C2F2Locomotion` and `C2F2NaturalLocomotionSelfTest`, `scripts/test-c2-f2-natural.ps1`, `docs/c2-f2-architecture.md`, `docs/c2-f2-test-report.md`, `docs/c2-f2-windows-checklist.md`.
- **Local evidence**: `javac --release 17` Pure JVM **27 self-test suites / 4,985 assertions PASS**; F2 tests include no-corner-cut, diagonal B2/B3 cost, old route contract, fresh intermediate proof, stale tickets, angle wrap, target >8 blocks, near-ring compatibility, and 10 independent policy instances. Mock Forge-facing adapter compilation PASS. Unmodified Backend **201 tests OK (36.518s)**. **Actual Windows Forge Build, real diagonal/player-looking locomotion, >8-block Follow and 1/3/5/10 NPC MSPT not yet verified**; these remain in PENDING_TESTS.txt. Forge Gradle wrapper is not available locally. No feature creep to Qwen/interaction/multi-chunk.

---

## C2-F1 — Reliable Follow Player (Architecture C; 2026-10-20; CANDIDATE)

- Built on the verified Windows core-gameplay Sprint 4 Fix #1 Full Repository (no GitHub actions). Added **operator-only** UUID-based Follow Session with `c2 follow <npc> <onlinePlayer>`, `unfollow`, `followstatus`; `c2 cancel <npc>` also safely stops an active Follow Session when no C0 static navigation is present.
- Added bounded 3D Follow-ring route selection using fresh B3 local graph (radius6), stop/resume hysteresis 2.5/4.0 blocks, movement threshold 1.5, 20-tick cooldown, <=4 route candidates / 3000 expansions each, at most one plan batch per global server tick; no C0 Goal/Action or Qwen request on target movement. No multi-chunk following or teleport.
- Shared proven `C2WaypointBridge` execution across C0 static and ephemeral Follow owners without changing C1 Vanilla movement. Route/C1 token correlation and mutual exclusion prevent simultaneous C2 static/Follow ownership; foreign C1 action remains fail-closed, C0 unresolved journal blocks Follow.
- Added `C2FollowJournal` SavedData for **audit only**, with old sessions disabled after Save/Quit/restart and no movement replay. Stop owned C1 ticket on unfollow/offline/near stop, bounded failure retry and timeout, allow resumption when target comes back within local loaded radius. No new C0 backend tool or schema.
- New `C2FollowPolicy`, `C2FollowPlanner`, two self-tests, Windows test script, architecture/report/checklist. Local **26 Java 17-target pure suites / 4,923 assertions PASS**; unchanged Backend **201 tests PASS**; mock Forge-facing compilation PASS. **Actual Forge Build / Windows Gameplay / 1/3/5/10 NPC MSPT pending** (Gradle download unavailable locally). Existing PENDING_TESTS unchanged except appended F1 checklist.

---

## Terrain-Aware 3D Traversal — Sprint 4 Fix #1: Transactional Replanning Recovery Hardening (2026-10-20; CANDIDATE)

- **Source-audited regression from Windows Gameplay:** `/npcortex c2 status yui` became `INTERRUPTED` with `P1-D C1 motion resumed during replanning; fail closed`, despite C1 later reporting `SETTLED`/grounded. The latter does **not** prove the active C1 session at interruption was `AUTO_SETTLING`.
- Added read-only, immutable origin data to the existing `C1P1MotionRuntime.C2MotionTicket` (`AUTO_SETTLING`, `AUTO_COMBAT`, `C2_COMMAND`, `OPERATOR_COMMAND`, `SPAWN_SETTLING`); no change to Vanilla travel/jump/gravity/combat calculation. Preserve each session's terminal origin/token to detect a foreign command even if it finished between C2 ticks.
- Added `C2S4RecoveryPolicy.replanOwnership`: **only** verified automatic settling/combat physics may keep C2 in bounded wait without starting another movement; any C2/Operator/Spawn session that appears during replanning fails closed. All replacement routes still require inactive C1, three stable loaded supported ground ticks, fresh terrain and B3 checks.
- When an active C0 action is interrupted, release the local owner and record `CANCEL_PENDING` for idempotent async terminal C0 cancellation acknowledgment instead of leaving an indefinitely blocking `INTERRUPTED` journal. Stop a C1 session only when its *active token exactly matches* the C2 route; never stop a foreign owner. Preserve original goal/action IDs. When the terminal state is verified by C0, a new navigation no longer needs unsafe manual reset.
- Support **narrow legacy migration** of the three known action-owning `P1-D` replan interruption reasons to `CANCEL_PENDING`; unknown `INTERRUPTED` rows remain fail-closed and never auto-reset or replay movement. C0 / Backend schema unchanged.
- Added `C2S4TransactionalRecoveryFix1SelfTest` (267 pure-Java assertions) and `scripts/test-terrain-s4-fix1.ps1`. Latest isolated Java suite: **24 classes / 4,706 assertions PASS** (Java 17 target executed with Java 21). Backend C2-focused **16 tests PASS**; full Backend suite **201 tests PASS (37.184s)** on this unchanged Backend in Fix #1. **Forge Build NOT VERIFIED** locally (Gradle 8.8 download unavailable), nor gameplay, crash safety, or MSPT. See `docs/terrain-sprint4-fix1-test-report.md` and `docs/terrain-sprint4-fix1-windows-checklist.md`.
- Scope preserved: no new pathfinding algorithms, no new C1 Physics, no interaction skills, no multi-chunk navigation, no GitHub operations. ONE `CHANGELOG.md` and one `PENDING_TESTS.txt`.

---

## Terrain-Aware 3D Traversal — Sprint 4 (Architecture C; 2026-10-20; CANDIDATE)

- Built on Sprint 3 Candidate **only**. Added typed `C2S4RecoveryPolicy` for terminal C1 3D receipts with exact ticket identity, bounded stable-ground recovery and fail-closed handling for foreign/unknown/unloaded states. Preserved B3/P1-D replan limits, C0 action ownership, C1 Vanilla Physics and existing cancellation transaction.
- Introduced S4 recovery WAIT/READY events and a 3-tick grounded/loaded/supported confirmation window with 100-tick settle deadline before using a fresh 3D graph. Replan results never count as verified goal completion without C1/C0 receipts.
- Normal Save/Quit transitions action-owning C2 runs to durable `CANCEL_PENDING` and stops C1 motion; stale action-owning journal records after restart are reconciled asynchronously with C0, never replayed. Ambiguous HTTP creation, crash/fync, absent backend and unknown motion remain fail-closed.
- Added `C2S4RecoverySelfTest` (696 isolated Java assertions PASS), Sprint 4 Windows script/checklist/report. S3 104 assertions and P1-D 187 assertions re-ran PASS; total isolated Java regression 23 suites / 4,439 assertions PASS. Backend C2-focused suite 16 tests PASS, **full backend retry 201 tests PASS** (initial run timed out; retry completed). Forge Gradle 8.8 download failed (UnknownHostException) before compile: Forge Build and all Sprint 4 live gameplay / actual 1/3/5/10 NPC MSPT PENDING. No GitHub operations.

---

## Terrain-Aware 3D Traversal — Sprint 3: Verified Transactional 3D Navigation (Architecture C; 2026-10-17; CANDIDATE)

- Built on Sprint 2 Candidate, no Sprint 4. Added **directed weighted 3D graph** kinds WALK, STEP_UP, STEP_DOWN, JUMP_UP, SAFE_DESCEND, with independent ascending/descending collision approvals and no dangerous invented reverse edges. Preserved legacy flat graph tests.
- Added conservative world read-only 3D clearance with loaded swept-player footprint, safe standing AABBs and jump headroom; no chunk force-load. Distinguishes verified `STAIR_TRANSITION` (partial >=50% support, Vanilla stair, headroom and noCollision confirmed) from FULL WALKABLE, and excludes generic partial support.
- Unified half-height footY→supportY interpretation across terrain scan, start/goal planning, C2 route and replan. `c2 navigate`, `navplan`, `navhier` accept fractional target feet Y, with strict target-height verification for live navigation.
- Extended C2 Waypoint and C1 Bridge with typed C1 Vanilla S2 Traversal Tickets and exact terminal `TRAVERSAL_COMPLETE_<KIND>` phase checks; off-center vertical approach first consumes a separately verified flat centering ticket. Maintained one C0 goal/action, per-segment C1 ticket ownership, P1-D bounded replanning and Fix #1 cancellation/reconciliation.
- B3 Region partition now uses weak local connectivity for grouping only, **without** losing directed portal and path rules. Added S3 synthetic planner/waypoint/multi-NPC self-tests, Windows test script, test report/checklist; preserved one `CHANGELOG.md` + `PENDING_TESTS.txt`.
- **Evidence:** 21 isolated Java suites / 3,657 assertions PASS (recheck count in final report); Backend full 201 tests PASS. Forge build cannot fetch Gradle 8.8 locally: **Windows Forge build and 3D gameplay remain NOT VERIFIED**. STEP_DOWN, dynamic 3D recovery, crash/rejoin and real MSPT remain pending. No GitHub operations.

---

## Terrain-Aware 3D Traversal — Sprint 2: Verified Vanilla 3D Motion Primitives (Architecture C; 2026-10-17; CANDIDATE)

- Based strictly on Terrain Sprint 1 Candidate; **operator-only Forge C1** `/npcortex c1 traverse <npc> <step_up|step_down|jump_up|safe_descend> <forward|back|left|right> <blocks>`, bounded to 0.65..1.45 blocks. This is **not** autonomous C2 3D navigation, not a Jump/Step edge in B1, and does not call C0/Ollama.
- Added dependency-free `C1S2TraversalPlan` (typed primitive, target Y, finite bounded inputs, grounded/airborne/landing verification, actual horizontal position, fail-closed combat interruption). New runtime session fields reuse **one existing C1 Vanilla `travel()`/`jumpFromGround()` owner**, without teleport, `setPos`, dual travel ticks or changed Qwen/Backend contracts. Combat impulses retain priority and cancel stops commanded input while preserving gravity until landing.
- Reused Sprint 1 terrain observations with a new server-thread `C2NavigationSensor.landingAt()` for exact destination preflight (loaded 3x3 neighbor footprint, support, height, hazard, headroom / AABB collision). Stair half-footprint only eligible for **operator physics proof** if at least half supported and exact Vanilla collision is clear; this does not declare graph WALKABLE. Verify target terrain again before any success receipt to fence placed/removed landing blocks.
- Added `C1S2TraversalPlanSelfTest`, `scripts/test-terrain-s2.ps1`, Windows fixtures/checklist and test report. No additions to automatic C2 Waypoint, Region, A*, C2 Replanning, Backend, Inventory or C1 Combat formula/lifecycle.
- **Local evidence:** Sprint 2 Java contract 64 assertions PASS; isolated baseline Java Terrain/Nav/A*/Waypoint/Cancellation regressions PASS (2,371 unique assertions including new contract); Backend full 201 tests PASS. Forge Gradle 8.8 wrapper distribution cannot be downloaded in this sandbox, therefore **actual Windows Forge compilation, Vanilla physics movement/landing, all stair orientations, multiplayer/MSPT, and dynamic terrain safety gameplay are PENDING**. The package is a Candidate, not Full Quality Gate PASS. No GitHub operations.

---

## Terrain-Aware 3D Traversal — Sprint 1: Reliable Terrain Intelligence (Architecture C; 2026-10-16; CANDIDATE)

- Approved scope only: improve **read-only Terrain Geometry + Collision Classification**, preserving C1 Vanilla Physics, C2-P1-D replanning, Fix #1 post-cancel recovery, B1-B3 flat `WALK` planner and Backend behavior. **No Sprint 2/3/4 or Jump/Step/Drop execution**.
- Added `C2TerrainGeometry` (player-footprint support using a union of VoxelShape collision rectangles, preserving conservative classification), immutable `C2TerrainObservation` (typed Support/Clearance/Risk/Reason), and integrated the same evidence into `C2NavigationSensor` NavGrid scan and new operator-only bounded `/npcortex c2 terraininspect <name> <x> <supportY> <z>` diagnostic.
- Added dependency-free `C2TerrainGeometrySelfTest` and `scripts/test-terrain-s1.ps1`; Windows test checklist + report under `docs/terrain-sprint1-*`. Preserved one `CHANGELOG.md` and `PENDING_TESTS.txt`.
- **Local tests**: 1,073 new pure Java assertions PASS; eight core terrain/navigation self-tests PASS (2,296 reported assertions including new suite); 201 Backend tests PASS; mock Minecraft sensor compilation/10 mock adapter checks and mock command-tree compile PASS. **Forge Gradle Build NOT VERIFIED**: Gradle 8.8 unavailable in this environment. Real Minecraft block-shape accuracy, per-coordinate inspection, unloaded-chunk behavior and Server MSPT still require Windows acceptance.
- No GitHub Operations.

---

## [Unreleased] — C2-P1-D Reliable Transactional Dynamic Replanning (Candidate) — 2026-10-16

- Added `C2ReplanningPolicy`: bounded per-Goal route generations, maximum 3 replan attempts, 20-tick cooldown, 12,000 expansion budget per attempt / 36,000 per Goal, and explicit stop on cancel.
- Integrated synchronous server-thread route replacement into existing `C2AgentRuntime`/`C2WaypointExecution` without creating an additional C0 action or a second C1 physics owner.
- Fresh World Sensor + Traversability Graph + B3 hierarchical search for blocked/deviated flat WALK routes; C1 bridge continues to re-check edges before every segment.
- Enforced same-ticket terminal receipt correlation, fail-closed unknown/mismatched tickets, `REPLANNING` action journal state, ownership checks before route replacement, and one global replanning attempt per END tick.
- Preserved Fix #1 Cancellation/Recovery semantics and original C1 Vanilla movement. Added domain events for replanning and route replacement.
- Added `test-c2-p1d.ps1`, pure Java regressions, Windows checklist, test report and carried forward all outstanding cases in `PENDING_TESTS.txt`.
- This is a **candidate**, NOT Windows Gameplay Acceptance; no GitHub operations. Forge Build blocked locally due unavailable Gradle 8.8 download.

## C2-P1-C Fix #1 — Transactional Post-Cancel Recovery (Architecture C; 2026-10-16; CANDIDATE)

- Added goal-scoped `POST /v1/agent/goals/{goal_id}/cancel` to C0, guarded by world/actor/action identity and the existing goal session lock. Pending C0 action is atomically fenced as `failed` with `operator_cancelled` verdict, and `pending_action_id` cleared without forging a successful action result; repeat cancellation is idempotent. No SQLite schema rewrite needed.
- Added Forge `C2CancellationRecovery`, persistent `CANCEL_PENDING` state, asynchronous retry with existing ChatBridge and terminal acknowledgement validation. On verified terminal C0 state and inactive local C1 motion, journal becomes `CANCELLED` (or truthful previous terminal state), permitting another C2 job with **no manual `/npcortex c2 reset`**. Legacy operator-cancelled `INTERRUPTED` rows can reconcile, but arbitrary interruption remains fail-closed.
- Added cancellation guard against manual reset during pending reconciliation. Did **not** change C1 physics, B3 path planning, P1-D or GitHub.
- Added Python cancellation tests (8), pure Java cancellation policy assertions (11), Windows `scripts/test-c2-p1c-fix1.ps1`, candidate docs and test report. Local Backend full suite **201 tests PASS**, Java isolated regression **2,286 assertions PASS**; Forge build remains **NOT VERIFIED** here because Gradle distribution download is blocked. Windows Build and Minecraft Acceptance **PENDING**.
- Maintained single `CHANGELOG.md` and `PENDING_TESTS.txt`, including existing **NOT YET TESTED** Unloaded Chunk and Partial Block/Terrain cases, plus new cancellation network/crash recovery coverage gaps.

---

## C2-P1-C — Reliable Transactional Waypoint Engine (Architecture C; 2026-10-16; IMPLEMENTATION CANDIDATE)

- Built on user-confirmed B3 Core Acceptance. Added bounded `C2WaypointExecution` pure-Java route/segment ownership state machine, `C2WaypointBridge` Forge adapter, and `/npcortex c2 navigate <name> <x> <feetY> <z>` (integer target standing-block position); C1 `C2MotionTicket` allows correlating each transient motion session with exactly one waypoint. Existing B3/B2 diagnostics remain read-only.
- One C0 Qwen-selected `move_to` action owns the entire path, rather than one Ollama request per step. A fresh B1 collision Graph is checked before **each** next segment; results require matching ticket, `COMPLETE` phase and actual position within tolerance. Final Goal also requires successful C0 verification. No automatic replan (P1-D), jump, step/drop or teleport; collision/physics remain Vanilla-owned by C1.
- Added pure protocol regression 261 assertions, Windows `scripts/test-c2-p1c.ps1`, implementation notes, Windows Checklist, Test Report and updated `PENDING_TESTS.txt` while retaining one unified CHANGELOG.md. Local pure-Java 17-target aggregate 2,275 assertions PASS; Backend 193 tests PASS. Forge wrapper blocked fetching Gradle 8.8 BEFORE compilation; **Windows Forge Build, Gameplay, runtime C1 ticket integration, crash-injection and concurrent MSPT PENDING**. Stub-based command syntax check is NOT Forge acceptance. No GitHub operations.

---

## C2-P1-B3 — Reliable Hierarchical Region Planner (Architecture C; 2026-10-16; IMPLEMENTATION CANDIDATE)

- Added pure-Java `C2RegionGraph`: bounded tile partition, connected components within each tile, immutable Region/Portal metadata; only existing verified B1 directed edges can become portals (no invented cross-wall links).
- Added `C2HierarchicalPlanner`: exact intra-region shortest-path costs, exhaustive portal overlay with deterministic weighted route, shared bounded search budget and independent B2 A* reference verification / explicit fallback. Results distinguish `PATH_FOUND`, `NO_PATH`, `OUT_OF_SCAN`, `UNSAFE_TARGET`, `INVALID_START`, `BUDGET_EXCEEDED` and `INCONSISTENT_GRAPH` with immutable paths.
- Added read-only Minecraft diagnostics `/npcortex c2 navregion` and `/npcortex c2 navhier`; scoped actor/dimension/lifecycle/motion checks and one coherent Graph+Snapshot capture per command. No NPC execution, no caching, no Qwen/Backend/C1 motion changes.
- Added pure-JVM `C2HierarchicalPlannerSelfTest`, `scripts/test-c2-p1b3.ps1`, Windows gameplay checklist, test report, and carried forward `PENDING_TESTS.txt` with **NOT YET TESTED** P1-A unloaded-chunk gameplay and partial-block/terrain per-coordinate accuracy. One `CHANGELOG.md` retained.
- **Local candidate tests:** B3 pure JVM **677 assertions PASS**, Java B2/B1/P1-A/C1 aggregate **2,014 assertions PASS**; Backend **193 tests PASS**. See `docs/c2-p1b3-test-report.md` for environment and measured limits. Forge Build / Windows Gameplay / concurrent MSPT **NOT YET VERIFIED** for this candidate. No GitHub operations.

---

## C2-P1-B2 — Reliable General-Purpose A* Engine (2026-10-16; IMPLEMENTATION CANDIDATE)

- On the latest B1 Traversability Graph ZIP, implemented pure-Java, immutable, deterministic, bounded `C2AStarPlanner`, with typed `PATH_FOUND`, `NO_PATH`, `INVALID_START`, `OUT_OF_SCAN`, `UNSAFE_TARGET`, `BUDGET_EXCEEDED`; expansion budget max 845, fail-closed snapshot/graph vertex consistency, weighted admissible horizontal heuristic, exact goal and ordered path/metadata.
- Added **operator-only read-only** `/npcortex c2 navplan <npc> <x> <feetY> <z> [radius] [budget]`, a coherent Graph+Snapshot capture on the Minecraft server thread, and per-call diagnostics. It does **not** move NPCs, create regions, persist routes, call Ollama or execute Waypoints. Existing C1/C2 commands and movement behavior are preserved.
- Added Java 17 `C2AStarPlannerSelfTest`, Windows `scripts/test-c2-p1b2.ps1`, `docs/c2-p1b2-astar-path-planning.md`, `docs/c2-p1b2-test-report.md`; retained one unified `CHANGELOG.md`.
- **P1-A pending tests remain NOT YET TESTED** in `PENDING_TESTS.txt`: Unloaded Chunk Safety gameplay and Partial Block/Terrain Accuracy per-coordinate. B1 per-edge correctness and concurrent MSPT remain open.
- Forge Build and Minecraft Gameplay **NOT VERIFIED** for B2 until Windows user acceptance. No GitHub operations.

---

## C2-P1-B1 — Traversability Graph (Architecture C; 2026-10-16; IMPLEMENTATION CANDIDATE)

- Implemented the FIRST substage of the user-approved hierarchical 3D navigation plan: immutable `C2TraversabilityGraph` vertices at distinct support heights, bounded four-cardinal `WALK` edges and explicit bidirectional collision-clearance validation. Conservative fail-closed treatment for unloaded, blocked, hazardous, unsupported or partially-supported cells; rejects duplicate/out-of-bounds input, diagonal shortcuts and guessed step/jump/drop edges.
- Added `C2GraphWorldProbe` binding P1-A fresh on-demand world snapshots to Minecraft server-thread swept player-AABB collision checks with no force-loading. Added operator-only read-only `/npcortex c2 navgraph <name> [radius]` to display graph counts and timing without commanding movement. **No A*, region hierarchy, autonomous movement or waypoint execution** is enabled.
- Added `C2TraversabilityGraphSelfTest`, `scripts/test-c2-p1b1.ps1`, `docs/c2-p1b1-traversability-graph.md`, `docs/c2-p1b1-test-report.md` and requested `PENDING_TESTS.txt`. Only **one** unified `CHANGELOG.md` exists. Unloaded Chunk Safety and Partial Block / Terrain Accuracy gameplay remain explicitly **NOT YET TESTED**.
- Local test results, Windows Forge build and gameplay evidence are listed in the test report. Isolated JVM assertions do not prove actual Forge collision, MSPT or final acceptance. No GitHub operations.

---

---

## Earlier consolidated Development history (preserved)

## Unreleased — Development `v0.2.0-alpha.1`: C2 Embodied AI / C2-P1-A World Representation (2026-10-16)

### Packaging changes (this archive only)

- Promoted the **Development source identifier** from `v0.1.2-alpha.2` to `v0.2.0-alpha.1`; synchronized Forge Gradle, Forge `mods.toml`, Python package metadata and Python runtime `__version__` (PEP 440 `0.2.0a1`). No released tag created.
- Preserved all C2-P0 and C2-P1-A runtime/source changes **exactly as supplied** in `NPCortex_C2-P1-A_C_WorldRepresentation_FullRepository_CANDIDATE(1).zip`; this packaging step does **not** implement A*, waypoints, replanning, or change game logic.
- Rebased this README and consolidated CHANGELOG on the previous Development documentation, appended the supplied C2 milestone history, and added a plain-text `PENDING_TESTS.txt`. Historic candidate-era test reports in `docs/` are left untouched.

### Packaging-session verification (this environment)

- **Archive / scope:** 187 regular files; 6 approved files modified, only `PENDING_TESTS.txt` added; ZIP integrity and metadata consistency checked. Markdown local links checked: **36 links, 0 broken**.
- **Java isolated regression:** 11 self-test classes, **1,222 assertions PASS**, `javac --release 17` on a Linux **JDK 21** host. This does **not** verify Gradle/Forge compilation on Windows JDK 17.
- **Python focused regression:** 11 tests **PASS (exit code 0)** across C2 embodied contract and C0 Windows acceptance-script static checks. Full Python discovery printed **193 tests / OK**, but the overall command did **not** terminate cleanly within the outer timeout; do **not** claim an independently verified full-suite exit code 0 for this packaging run.
- **Forge/Minecraft:** no new Windows Forge Build, live Minecraft or real Ollama integration test was run during packaging. The separately reported maintainer Windows acceptance follows below.

### Maintainer-reported acceptance vs. open gates

- **Windows Forge Build:** maintainer-reported **PASS** for C2-P1-A (2026-10-16), after local build validation. **This packaging session has not independently reproduced a Windows Forge build.** The original candidate's Linux Gradle download failure remains historical tool-environment evidence only.
- **Automated test evidence for the P1-A candidate:** 27 NavGrid Java assertions, 42 C2 Foundation assertions and other retained Java self-tests were reported passing; **193 backend Python tests** were reported passing. These test results do **not** validate unloaded chunks or real partial-block semantics.
- **Maintainer-reported Windows Minecraft gameplay (scoped):** flat-terrain navscan; place/break/rescan response; hazard classification; lifecycle checks; and a five-NPC *sequential* navscan smoke test. These do **not** establish per-coordinate semantic accuracy, actual unloaded-chunk boundary safety, or *concurrent* 1/3/5/10-NPC performance/MSPT.
- **C2-P0 scoped acceptance:** maintainer reported single-player movement to a nearby same-height target reaching `C2=COMPLETED` / `verified=true`, and later short-range precision movement working; this is not independent proof of Qwen decision trace, recovery or multiplayer.
- **`NOT YET TESTED` P1-A gates:** real unloaded-chunk gameplay safety; per-coordinate Partial Block / Terrain Accuracy. Concurrent performance/MSPT and broader regression/compatibility coverage also remain open. See `PENDING_TESTS.txt`.
- **Release / sprint gate:** P1-A is **not** marked fully accepted; do not infer P1-B pathfinding or publish an unconditional quality-gate claim. Continue Audit → Options → Approval → Implement → Test. Maintainer manages GitHub operations.

---

## C2-P1-A — Bounded 3D World Representation (Architecture C; 2026-10-16; CANDIDATE)

- First staged delivery for user-approved **C2-P1 Architecture C**. Added testable bounded immutable `C2NavGrid` and read-only `C2NavigationSensor` with server-authoritative collision/foot-surface checks, hazard classification, unknown/unloaded sentinels, world-border/loaded-chunk guards. Radius defaults to 4, max 6, support levels ±2, at most 845 cells per observation. Multiple 3D candidate heights per X/Z column are retained; complex support shapes remain conservatively `PARTIAL_SUPPORT`.
- Added operator-only `/npcortex c2 navscan <name> [radius]` for diagnostic capture on the Minecraft server thread, P1-A tests and Windows test checklist in `docs/c2-p1a-world-representation.md` plus `scripts/test-c2-p1a.ps1`. No model/API schema changes, no tick polling, no new locomotion/pathfinding; C1 P0/P1 mechanics and existing P0 `move_to` remain unchanged.
- **Local tests:** 27 new P1-A Java assertions + 1,195 retained dependency-free Java C1/C2 assertions **PASS**, Python Backend discovery **193 tests PASS**. Forge compile **NOT VERIFIED** (Gradle 8.8 download `UnknownHostException: services.gradle.org`). Real Forge collision and 1/3/5/10 NPC MSPT, gameplay and compatibility testing still required.
- **Quality Gate pending**: Do NOT start/claim P1-B A*, P1-C waypoints or P1-D replanning until Windows Forge build and Minecraft P1-A acceptance. No GitHub operations.

---

## C2-P0 — Precision Movement & Configurable Goal Tolerance (Architecture C, 2026-10-16; CANDIDATE)

- **User-approved C precision enhancement** to the existing Architecture C: allow short-distance C2 `move_to` through the *same* C1 Vanilla player motion session (no second physics system), preserve normal C1 operator minimum 0.5 block, and add bounded per-goal precision radius via `/npcortex c2 move <name> <x> <y> <z> [tolerance]` (default 0.35; explicit 0.20–0.75 blocks).
- Align C2 precheck/goal creation/live verifier and C0 `move_to` expected effect using the validated matching goal criterion. A target already within tolerance is reported as such without sending an unnecessary Action. Do not report success for a moved-but-unverified result.
- Verified locally: **1,224 JVM isolated assertions PASS** (11 tests; `javac --release 17` on JDK 21 host); **193 backend unittest cases PASS** (includes 8 C2 contract tests). No direct Forge dependencies compiled by these isolated checks.
- Forge compile **NOT VERIFIED**: wrapper cannot download Gradle 8.8 (`UnknownHostException: services.gradle.org`). Windows gameplay precision, boundary/collision, motion regressions, real Qwen, 1/3/5/10 NPC, multiplayer and crash tests remain open. This is an **Implementation Candidate**, not a completed Quality Gate; no GitHub operations.
- Windows checklist and limitations: `docs/c2-p0-precision-movement-c.md`. One unified `CHANGELOG.md`; no separate sprint changelog.

---

## C2-P0 — Architecture C Event-Driven Embodied AI Foundation (2026-10-16; IMPLEMENTATION CANDIDATE)

- Implemented initial C2 cross-runtime chain using existing C0 endpoints and C1 Custom ServerPlayer with no PathfinderMob, teleport, or second physics controller. Built `C2Scheduler`, `C2Events`, extensible `C2SkillRegistry`/`C2MoveExecutor`, `C2MoveGeometry`, `C2ActionJournal` (overworld SavedData), trusted Forge `C2WorldSensor`, `C2ActionVerifier`, `C2AgentRuntime` and async `ChatBridge.c2Post` client.
- Operator-only `/npcortex c2 move/status/cancel/reset` commands. Actual skill available in this P0 is **only** straight, same-height `move_to` within 7.5 blocks. Qwen selects the skill, Forge independently restricts destination, then C1 executes Vanilla travel and C0 verifies a fresh post-result position. No autonomous multi-step nav or natural-language target grounding yet.
- Lifecycle fail-closed on cancel, death, despawn, ambiguous callback/timeout, save/quit/restart; explicit journal reconciliation/reset required. SavedData alone is not crash-proof; no exactly-once guarantee, cross-runtime automatic crash recovery or multiplayer FIFO claims.
- Tests: C2 pure JVM geometry/event/scheduler **32 assertions PASS** with Java 17 target; C0 backend focused **69 tests PASS**; C2 service-backed integration contract **4 tests PASS**; full Backend suite **189 tests PASS** with mocked model and sensor. Full Forge Gradle compilation was **blocked before compilation** by unavailable Gradle 8.8 distribution, Windows gameplay and real Ollama test **NOT RUN**, 1/3/5/10-NPC performance and compatibility gates **OPEN**. **Candidate only — NOT Quality Gate passed**. See `docs/c2-p0-event-driven-embodied-agent.md` and `scripts/test-c2-p0.ps1`. No GitHub operations.

---

---

NPCortex keeps **one consolidated `CHANGELOG.md`** for the `Development` branch. This history continues the earlier [`Development` C0 baseline](https://github.com/SIRP00M/NPCortex/tree/Development) and the `0.1.2-alpha.1` C1-P1-B snapshot. This local **`0.1.2-alpha.2` source candidate** is not a release/tag, does not modify `main`, and has not been pushed to GitHub by this packaging step.

**Version display:** Forge `0.1.2-alpha.2`; Python package `0.1.2a2` (PEP 440); in-package Python `__version__ = "0.1.2-alpha.2"`. Historical earlier version numbers below are kept deliberately. A sprint title is not a published release.

> **Evidence policy:** Historical entries marked candidate keep their original scope. Owner-reported acceptance of selected P0/P1-A/P1-B behaviors does not establish full C1 quality gates. Java policy tests do not verify Forge compilation or Minecraft gameplay. Do not push until the maintainer confirms the applicable Windows acceptance gates.

## Unreleased — Development `v0.1.2-alpha.2`: C1-R2 Fix #2 + BuildFix1 (2026-10-15)

### Changed

- Advanced source/display version from the previous development snapshot `0.1.2-alpha.1` to `0.1.2-alpha.2` in Gradle `mod_version`, Forge `mods.toml`, backend distribution metadata and backend `__version__`.
- Brought `ReadMe.md` up to the **latest uploaded source** (C1-P1-C, R0 lifecycle, R1/R1-A death presentation, R2 combat/motion ownership and the targeted BuildFix1). Kept C0–C1-P1-B history and this single changelog, without changing runtime functionality during version packaging.
- Carried forward the source's R2 Fix #2 build-failure correction: `C1P1MotionRuntime.java` uses existing `C1VanillaMotionOwnership.captureVanilla(...)` rather than nonexistent `capture(...)`. This **code fix came with the uploaded source**, not from the version-bump packaging step.

### Evidence / release gate

- **This packaging session ran** 1,227 isolated Java regression assertions across P0 sync, P1-A motion/rotation, P1-B inventory/SaveData, P1-C lifecycle/tick, R0 policy/coordinator and R1/R2 combat/death policies: all passed on a **JDK 21 host using `javac --release 17`**. This does not certify a native JDK 17 Forge build. Focused backend Python tests passed **69/69 (exit 0)**: C0 agent 56, planner 10 and Windows acceptance-script static tests 3. Full Python backend regression was not run in this packaging session.
- **Forge compile attempt failed before Java compilation** because the Gradle wrapper could not download Gradle 8.8 (`java.net.UnknownHostException: services.gradle.org`). This is an environmental blocker, **not** evidence that the reported `captureVanilla` BuildFix1 has compiled successfully under Forge.
- **Windows Forge Build, real player Knockback II + Zombie/wall verification, death/respawn/poof, inventory/network regression, and multi-NPC/multiplayer compatibility remain open** for this exact ZIP unless independently confirmed by the maintainer.
- No new gameplay feature or schema migration was implemented by the version/documentation changes. No GitHub commit/push/merge/tag was performed.

## New milestones in this source since `v0.1.2-alpha.1`

## C1-R2 Fix #2 — Build Failure Audit #1 (2026-10-15; MINIMAL COMPILE FIX CANDIDATE)

- Resolved the user-reported Windows Forge `:compileJava` failure at `C1P1MotionRuntime.java:148`: changed `combatVelocity.capture(...)` to the existing `C1VanillaMotionOwnership.captureVanilla(...)` method. No changes to impulse math, movement ownership, Network, Inventory, Death, Backend, or SavedData.
- Verification remains required: Windows Forge `build`, gameplay player Knockback II, Zombie, Movement, Death/Respawn/Poof and Inventory/Network regression. Isolated Java policy tests cannot substitute for Forge compilation or in-game acceptance. No GitHub operations performed.

---

## C1-R2 Fix #2 — Architecture C Movement Lifecycle Refactor (2026-10-15; IMPLEMENTATION CANDIDATE)

- **User-provided failure evidence:** `VANILLA_KNOCKBACK` registered `velocity=(0.92536,0.4,0.76298)` after a real Knockback II sword hit, but `COMBAT_STEP age=1` subsequently saw `incoming=(0,0,0)` and no server displacement. This proves the motion was missing at the handoff, **not** that Minecraft did not calculate knockback; the exact external writer that cleared/replaced the vector is still unproven.
- **Approved C movement lifecycle:** added pure-Java `C1PlayerMotionPhase` (COMBAT > COMMANDED > SETTLING) and `C1VanillaMotionOwnership` so one authoritative END-tick physics owner receives real Vanilla velocity across attack/world/tick boundaries. Repeated Vanilla calls replace, not sum, the captured vector; meaningful newer engine motion wins. After travel, only Vanilla's damped/post-collision velocity remains eligible for continuation. Transient state clears on Death/Despawn/Stop/Session End/Server Stop.
- Preserved existing `Player.travel` and Fix #1's collision-only `Entity.move` fallback, which never teleports or computes a custom knockback. Corrected combat completion to treat grounded downward gravity as rest after the minimum duration. Added first-three-frame `C1-R2-FIX2 COMBAT_STEP` diagnostics reporting motion ownership, input, position delta, and collision state; added Windows script and acceptance document.
- **Local tests:** 1227 isolated Java 17-target assertions passed with `javac --release 17` on JDK 21 host; focused Backend C0 tests **69 passed, exit 0**. These are NOT Forge compile or Minecraft combat confirmation. Forge wrapper is still blocked fetching Gradle 8.8 from `services.gradle.org` before Java compilation. **Windows Forge Build, actual Player Knockback II displacement, wall collision, Zombie regression, Death/Inventory/Network and 1/3/5/10 NPC testing remain open.** No GitHub operations. See `docs/c1-r2-fix2-movement-lifecycle-refactor.md`.

---

## C1-R2 Fix #1 — Server-authoritative Combat Physics fallback (2026-10-15; IMPLEMENTATION CANDIDATE)

- **Context and evidence:** Windows player-attack logs show `reason=VANILLA_KNOCKBACK` with horizontal velocity (e.g. `0.8148, 0.4, 0.8810`), while authoritative `/npcortex c1 motion` positions remained unchanged through `IDLE → COMBAT_IMPULSE → SETTLED`. User reported Zombie hits can cause knockback, but direct sword/Knockback II hits did not visibly move the NPC. The Vanilla force calculation and session activation therefore work, whereas applying the velocity to the server position is not reliable on this synthetic, clientless `ServerPlayer`.
- **Scoped B fix:** Preserve `LivingEntity.knockback` as the sole source of impulse magnitude/direction and `Player.travel(Vec3.ZERO)` as the first physics path. In the existing one-owner combat tick, capture incoming Velocity, call Vanilla `travel`, and measure authoritative displacement. If `travel` does **not** advance the relevant axis and no wall/ceiling collision was registered, call Vanilla `Entity.move(MoverType.SELF, remainingVelocity)` for that axis **once**. No teleport, new knockback force, full `doTick()`, second per-tick loop, protocol change, backend change, or SavedData migration. Preserve the vanilla `Entity.move` collision solver.
- **Diagnostics:** `C1-R2-FIX1 COMBAT_STEP` reports velocity before `travel`, `travelDelta`, fallback requested, actual server displacement, collision flags and resulting velocity during the first three frames of a Combat Session. `/npcortex c1 combat` includes `collisionFallbackSteps` and `travelOnlySteps` while a session is live. These counters alone do **not** prove successful displacement: evaluate `actualDelta` and `/npcortex c1 motion` position.
- **Tests run here:** 947 isolated Java 17-target assertions passed (baseline 928 + 19 R2 fix assertions); focused Backend C0 agent suite 56 tests passed, exit 0. Forge compile NOT verified: wrapper Gradle 8.8 distribution is unavailable in this runtime, and `./gradlew --offline compileJava` failed before Java compilation. Real Windows Forge Build and Minecraft Combat/Movement/Death/Network tests remain mandatory. No GitHub operations performed. See `docs/c1-r2-fix1-server-authoritative-physics.md`.

## C1-R2 — Vanilla Combat Physics (Architecture C, connection-safe integration; 2026-10-15; IMPLEMENTATION CANDIDATE)

- Based on user-accepted C1-R1-A death animation/poof. Added a distinct **COMBAT** phase to the R0 lifecycle policy, while preserving the single P1-A travel owner. C1 `ServerPlayer` still uses Vanilla world `tick()` and real `super.knockback()`, `hurt()`, resistance and damage cooldown; its synthetic listener still **does not run connection-driven `doTick()`**. This is a staged C implementation, not full logged-in PlayerList parity.
- Added `C1CombatTickPolicy` and a bounded per-NPC combat impulse window to the original `C1P1MotionRuntime`: actual Vanilla velocity is integrated via `Player.travel(Vec3.ZERO)` while a planned movement intent is paused; no custom strength formula or second physics loop. Fall checks continue through the existing Vanilla `doCheckFallDamage` path. Death/detach clears the pending session and does not reanimate dead entities.
- `C1PlayerAvatar.knockback` calls `super.knockback` first, then schedules only changed finite impulses. Also monitors Vanilla `hasImpulse` opportunistically for externally injected velocity while idle. Sends Vanilla `ClientboundSetEntityMotionPacket` to verified real viewers on an accepted impact and on late verified tracking; original C1-P0 two-phase recovery and protocol version remain unchanged. Added operator-only `/npcortex c1 combat <name>` read-only diagnostics and `/npcortex c1 knockback <name> <0.1..2>` Vanilla callback fixture.
- Maintained R1-A one-shot death particles, R1 death window, R0 lifecycle, P0 tracking, P1-A motion/rotation, P1-B inventory/ledger, C0 backend, persistent actor identities and SavedData schema. Portal navigation, Qwen combat AI, monster targeting, and full connection `doTick` are out of scope.
- **Local test evidence:** 928 isolated Java regression assertions (including 114 new R2 and updated R0 test doubles) passed with `javac --release 17`/JDK 21 host. Unchanged Backend C0 focused test: 56 tests plus 8 subtests, exit 0. **Forge compile was not reached:** Gradle 8.8 download hit `UnknownHostException: services.gradle.org`. No new Minecraft gameplay, enchantment/attack verification, 1/3/5/10-NPC benchmarks, multiplayer or modded velocity validation. See `docs/c1-r2-vanilla-combat-physics.md`. No GitHub operations.

---

## C1-R1-A — Mob-Like Death Despawn Particles (2026-10-15; CANDIDATE)

- User-approved R1 presentation-only enhancement: after the existing **26-tick** Vanilla Player death fallover, the server broadcasts a single bounded burst of Vanilla `POOF` (26) and `CLOUD` (10) particles at the dead NPC's position **before** detaching the avatar. Uses `ServerLevel.sendParticles`, not a custom renderer/network packet, and is visible only to nearby players in the same dimension (subject to client particle settings).
- Hardened emission preconditions: burst only after the normal presentation deadline, while the original avatar is still tracked and not removed, and SavedData confirms `DEAD`; never emit on stale avatar, early teardown, interrupted lifecycle or server shutdown. Removing the volatile presentation record makes the burst one-shot.
- Kept R1 death animation, authoritative death/keepInventory transaction, respawn, R0 lifecycle foundation, P0 two-phase tracking, P1 movement/rotation/inventory, Qwen backend, UUIDs and SavedData schema unchanged. No knockback, portals or AI in this scope.
- Extended dependency-free death-policy regression checks; **Forge Compile and visual particle confirmation on Windows are still required**. No GitHub operations.

---

## C1-R1 — Verified Vanilla Player Death Presentation (2026-10-15; CANDIDATE)

- Added a bounded **26-tick Vanilla Player death pose window** after authoritative Death/Inventory processing (previously death immediately discarded `ServerPlayer` in the same tick). Death persistence remains `DEAD` from the initial END tick, so dead actors cannot move, interact, or auto-restore after restart.
- Added per-viewer, token-checked `DeathPresentation` / `DeathReady` messages atop C1-P0 Two-Phase Networking. Client enters `RemotePlayer` zero-health state for native death rendering and acknowledges dead state; a visual screenshot/gameplay check is still required. Late viewers can receive death state after normal entity-presence verification.
- Respawn waits until the short presentation ends; R0/P0/P1-A/P1-B lifecycle, Inventory Journal, UUID persistence and GameRules remain authoritative. This does not add knockback, automatic portals, AI or full PlayerList login.
- Local isolated Java 17 tests: **764 assertions passed** (R1 policy 43 + R0/P0/P1 regression 721). Unchanged Backend C0 focused regression: **56 tests + 8 subtests passed, exit code 0**. See `docs/c1-r1-verified-vanilla-death.md` for Windows Acceptance. Forge compile and Minecraft rendering **not yet confirmed** for this candidate. No GitHub operations.

---

## C1-R0 — Vanilla Lifecycle Foundation (2026-10-15; IMPLEMENTATION CANDIDATE)

- Approved **R0 only** of C1 full lifecycle refactor. Added `C1VanillaLifecycleCoordinator` and `C1VanillaLifecyclePolicy` to make phase ownership explicit: Vanilla WORLD `ServerPlayer.tick`; **no** synthetic `ServerPlayer.doTick`; NPCortex owns bounded MOTION, deferred DEATH and per-viewer TRACKING. A detached/non-ALIVE avatar cannot advance WORLD tick. No full PlayerList/Connection emulation, portal, death animation or knockback expansion is included.
- Consolidated the three existing server-tick listeners into a coordinator that preserves **P0 network → P1 motion → P1-C death** event order and preserves server-shutdown ordering. Moved pre-spawn Inventory and Lifecycle restore, after-spawn Motion preparation, and pre-detach Motion cleanup behind the same boundary. Existing APIs, SavedData formats, Backend and the two-phase client repair protocol are unchanged.
- Added dependency-free R0 ownership test (13 assertions) and coordinator test-double harness (12 assertions), plus `scripts/test-c1-r0-foundation.ps1` and `docs/c1-r0-vanilla-lifecycle-foundation.md`.
- **Local results:** 25 new + 696 existing isolated assertions **PASS** under `javac --release 17` / JDK 21 host, each test process exited 0. Backend file contents are unchanged; focused C0-agent Backend regression **56 passed, 8 subtests passed (exit 0)**; full suite not rerun. Forge Build **could not start compilation** because Gradle 8.8 distribution download fails with `UnknownHostException: services.gradle.org`. Windows Build, gameplay, network, 1/3/5/10-NPC benchmark, multiplayer and mod compatibility are NOT certified. No GitHub operations were made.

---

## C1-P1-C — Fix #1: Selective Vanilla World Tick, Idle Gravity & Damage (2026-10-15; CANDIDATE)

- **Source diagnosis:** `C1PlayerAvatar.tick()` and `doTick()` were both overridden to no-op in P0/P1. Minecraft 1.20.1 normally decrements `ServerPlayer.spawnInvulnerableTime` inside `ServerPlayer.tick()`, explaining the user-observed `damageAccepted=false`/20 HP even with `setInvulnerable(false)`. `settle` temporarily activating `Player.travel` confirms that physics is separately session-controlled, not continuously simulated while idle.
- **Approved selective Vanilla integration:** `C1PlayerAvatar.tick()` now calls `super.tick()` (server WORLD phase) and then performs a lightweight idle support check. **`doTick()` remains disabled** because the passive synthetic connection is not a logged-in real player's movement connection and could duplicate the P1-A controlled physics. This is not full Vanilla Player tick parity.
- **Idle physics:** when an idle, active, loaded NPC loses floor support, a support probe every 4 server ticks starts the existing bounded P1-A settle session. No chunk forcing, AI polling or secondary physics system. Walk/jump commands continue through the original motion controller.
- **Fall-damage integration:** after each authoritative `Player.travel()` step, call the Vanilla `ServerPlayer.doCheckFallDamage` method (normally invoked by real player movement packets). Fall checks run **after** iterating motion sessions, preventing a fatal fall from mutating the same iteration's map. Vanilla gravity/friction/collision remain in `Player.travel`.
- Expanded read-only `/npcortex c1 lifecycle <name>` diagnostics to show GameMode, invulnerable/flying abilities and noGravity, so Creative vs. spawn protection vs. incomplete physics can be distinguished without guessing. No SavedData format changes, no Backend/SQLite modifications and no GitHub operations.
- **Local checks:** P0 Network 26 + Motion 29 + Rotation 29 + Inventory Journal 544 + Inventory SavedData test doubles 14 + Lifecycle SavedData test doubles 22 + new idle-physics policy 32 = **696 isolated Java 17 compatibility assertions passed**. These tests ran on a JDK 21 host using `javac --release 17`; they are NOT Forge compilation. The local Forge Gradle wrapper failed to download Gradle 8.8 (`UnknownHostException: services.gradle.org`) before `compileJava`. Windows Forge Build, Damage, fall damage, player world-tick compatibility, three-NPC regression and performance remain unverified. Details: `docs/c1-p1-c-vanilla-tick-fix1.md`.

---

## C1-P1-C — C-Optimized Selective Vanilla Player Lifecycle (2026-10-15; CANDIDATE)

- Added `C1LifecyclePhase`, `C1LifecycleData` and `C1P1CLifecycleRuntime` with ALIVE/DEAD/TRANSITION SavedData states, health/phase revisions, safe legacy-P1-B defaults and blocked corrupt/ambiguous lifecycle data. Respects stable World/Actor/Profile UUID mapping.
- Enabled Vanilla `ServerPlayer.hurt`/Health and selectively dispatches cancellable Forge `LivingDeathEvent`, without calling unverified `ServerPlayer.die` or `PlayerList.respawn` on a passive connection. Lethal events queue a bounded tick-END teardown and inspect **current** `keepInventory`, dropping via Vanilla `Inventory.dropAll` only when the rule is false; snapshot/ledger receipts stay separate.
- Added operator-only `lifecycle`, `damage`, `kill`, `respawn`, and guarded `dimension` commands. Controlled respawn uses saved location; dimension transfer is allowed only to an already loaded, collision-free position beside an operator in that destination dimension. Reuses existing P0 viewer tracking and P1-A rotation/equipment restoration.
- Explicit crash limitation: separate lifecycle/inventory/world ItemEntity writes are not an atomic transaction; interrupted transitions stay fail-closed (manual reconciliation not implemented). Full Vanilla PlayerList/bed/XP/login/clone/portal parity and Mod compatibility are NOT claimed. No Qwen/NPC autonomous skill work and no GitHub operations.
- Local Java mock checks: lifecycle SavedData **22 assertions** passed; P1-B Inventory SavedData **14 assertions** passed; prior isolated P0/P1-A/P1-B regression tests are retained. **Forge Gradle wrapper cannot fetch Gradle 8.8 from services.gradle.org**, so no Forge Compile/Windows Gameplay acceptance is claimed. See `docs/c1-p1-c-selective-vanilla-lifecycle.md`.

---

## Unreleased — C1-P1-B Transactional Inventory & Equipment (2026-10-15)

**Development source version stays `0.1.2-alpha.1`** (Python distribution metadata: `0.1.2a1`). This package **replaces the earlier C1-P1-A ZIP at the same development pre-release label**; it does not bump to `alpha.2`, publish a release or create a GitHub tag. This entry describes the actual C1-P1-B implementation added to the attached source, not a documentation-only change.

### Added / changed in the C1-P1-B source

- Added a **Vanilla ServerPlayer Inventory adapter**, including 36 main/hotbar slots, armor slots 36–39, offhand slot 40 and selected hotbar state; exposed permission-2 operator-only `/npcortex c1 inventory|equipment|ledger|give|equip|clear|copyhand` commands. `copyhand` is a debug COPY, not a trade or transfer from the operator.
- Added `C1InventoryData` (Overworld SavedData) and `C1InventoryJournal`: full Vanilla ItemStack NBT inventory snapshots and per-NPC monotonic-revision transaction receipts in the same SavedData record. Explicit transaction UUID replays give `REPLAY` for the same operation and `CONFLICT` for a changed one. The **512-receipt** journal limit fails closed rather than discarding old replay protection; corrupt persisted records are blocked and preserved.
- Restored the NPC inventory before world registration and synchronized visible equipment **only on a mutation or client-verified (re)tracking**, preserving the prior C1-P0 two-phase network sync and C1-P1-A movement/rotation code. No polling loop, automated item pickup, C0 skill executor or Qwen-inventory integration was introduced.
- Added [`docs/c1-p1-b-transactional-inventory.md`](docs/c1-p1-b-transactional-inventory.md), the `test-c1-p1-inventory.ps1` / `test-c1-p1-inventory-saveddata.ps1` scripts and standalone Java inventory journal / mock-NBT SavedData tests.

### Evidence and limits

- **Maintainer-reported Windows single-player Minecraft acceptance (2026-10-15):** giving and equipping a diamond sword and repeating transactions behaved as expected; **inventory persistence and transaction replay after Save & Quit/rejoining the world** were also confirmed for the tested scenario. This is not crash-injection testing or proof of cross-system exactly-once semantics.
- **Rechecked against this updated ZIP in the packaging environment:** Inventory Journal **544** assertions, mock-NBT Inventory SavedData **14**, P0 network state **26**, P1-A motion **29**, and rotation **29** — **642 total isolated Java assertions passed** when compiled with `javac --release 17` on a **JDK 21 host**. These are dependency-free/isolated checks, **not JDK 17 execution, Forge compilation or Minecraft gameplay**. Python C0-B Windows acceptance-script static guards passed **3/3**; the attempted full Python backend suite did **not** finish before timeout, so no exit-0 result can be claimed. Gradle Forge Build and a complete Minecraft retest were not run in this environment.
- `APPLIED_PENDING_WORLD_SAVE` is an **in-memory** acknowledgement; full durability requires Minecraft's world save. Avoid representing this as disk-level fsync, transactional transfers or SQLite atomicity.
- **P1-B Quality Gate OPEN:** multi-NPC inventory isolation in Minecraft, crash-injection/recovery, modded item/capability compatibility, multiplayer, performance and broader regressions have not been confirmed. C1-P1-C Player Lifecycle and AI autonomy are future, separately approved scopes.
- No GitHub Commit/Push/Merge/Tag was performed by preparing this artifact.

## Unreleased — Development version alignment: `0.1.2-alpha.1` (2026-10-15)

### Changed

- Started the explicitly approved **C** pre-release version line for the `Development` branch: **`0.1.2-alpha.1`** for Forge and human-readable backend version; **`0.1.2a1`** for Python distribution metadata (PEP 440 equivalent).
- Synchronized `forge-mod/gradle.properties` (`mod_version`), `forge-mod/src/main/resources/META-INF/mods.toml` (`version`), `backend/pyproject.toml` (`project.version`) and `backend/src/npcortex_backend/__init__.py` (`__version__`).
- Updated `ReadMe.md` and this single consolidated `CHANGELOG.md` to reflect the development version and branch policy. All previous `0.1.1-alpha` mentions tied to historical milestones/reports remain intact.

### Unchanged / limits

- **Version/metadata/documentation change only**: no gameplay feature, Java/Python runtime logic, networking protocol, SQLite schema, automated test implementation or Forge dependency changes.
- `Development` is a pre-release workstream; **`main` is not modified** and no tag or published release is created. A later promotion/merge into `main` needs separate source/branch reconciliation, tests and explicit maintainer approval, especially because `main` previously followed a different version line.
- Existing maintainer-reported C1 gameplay acceptance remains bounded to its tested scenarios. **C1-P0 overall Quality Gate is still open.**

## Unreleased — GitHub Development → C1 documentation reconciliation (2026-10-15)

### Added / updated in this local Full Repository ZIP

- Carried the Development line from the published C0 headless snapshot into **C1-P0 True Player-based NPC feasibility**, **C1-P0 Fix #2 viewer-specific network synchronization**, **C1-P1-A controlled movement** and **C1-P1-A Rotation Fix #1**.
- Updated `ReadMe.md` to describe the included C1 source, actual operator commands, scope boundaries and owner-reported acceptance separately from unit-test evidence.
- Reconciled this single changelog's wording with the GitHub Development baseline without claiming a new published version or falsely marking the entire C1 quality gate complete.
- Kept detailed earlier C1 packaging notes and A2–C0 development history below for traceability; the existing `docs/c1-*.md` files remain candidate-time technical reports.

### Not changed by this documentation reconciliation

- **No** Java, Python, packet contract, SQLite schema (still v4), model prompt, Gradle configuration, tests, Forge mod version or runtime behavior was modified by the README/CHANGELOG update.
- **No** GitHub commits, pushes, merges, branches, releases or tags were created by this package preparation.

## C1 — Maintainer-reported Windows Minecraft acceptance (2026-10-15)

### C1-P0 Fix #2 — Two-phase network synchronization: tested behavior accepted

- **Initial spawn:** Minecraft client reported `CLIENT_ENTITY_PRESENT` with `repairs=0`.
- **World exit and rejoin:** client initially reported `CLIENT_ENTITY_MISSING`; server performed `CLIENT_ENTITY_REPAIR` attempt **1/2** and the client subsequently reported `CLIENT_ENTITY_PRESENT` with `repairs=1`.
- Maintainer **visually confirmed Yui reappeared**. World UUID, Actor UUID and GameProfile UUID stayed the same; a new session's **Entity ID** changed as expected.
- **Bounded acceptance, not entire P0:** broader re-tracking, despawn/lifecycle edge cases, custom skins and multiplayer need their own checks; the overall C1-P0 Quality Gate is still **OPEN**.

### C1-P1-A — Controlled movement and Rotation Fix #1: gameplay acceptance

- The maintainer previously accepted **basic operator-controlled movement gameplay** in Windows Minecraft (not general navigation/survival or full Vanilla physics compatibility).
- After leaving/re-entering the world, the maintainer confirmed **Yui's head and body retained their prior facing direction**, resolving the earlier visible head-orientation reset to 0°. This is **rotation gameplay acceptance** for the tested scenario.
- These are owner-observed in-game results, **not** automatically run Forge/CI, LAN, multi-user, build reproducibility or third-party-mod compatibility proofs.

### C1-P1-A — Three-NPC smoke test: tested behavior accepted

- A **Yui / Hina / Ai** test in Windows Minecraft confirmed simultaneous spawns, distinct UUIDs, independent turning and movement, persistence after rejoining the world, independent despawn, and basic tracking/rotation regression.
- This confirms only a **3-NPC smoke test**. It is **not** a measured 5/10/20-NPC load test, performance benchmark, multiplayer acceptance or autonomous AI-NPC operation.

### C1-P1-B — Transactional inventory: tested behavior accepted (limited)

- The maintainer confirmed **diamond sword give/equip** and **transaction replay** worked in Windows Minecraft single-player.
- **Inventory persistence and transaction replay after Save & Quit/rejoin** also passed in the user-observed Windows test. The C1-P1-B source persists inventory snapshots and receipts using Overworld SavedData.
- **Not equivalent to the full P1-B Quality Gate:** multi-NPC inventory isolation, crash-injection, modded items/capabilities, performance and multiplayer have not been verified.

### Still open before the next sprint

- Reproducible Forge/Java/backend test evidence for the exact ZIP/commit to be pushed, plus any remaining lifecycle/tracking edge cases and owner gameplay checklist items.
- **C1-P1-B Inventory/Equipment is implemented** and has the limited Windows gameplay acceptance above, but its full quality gate remains OPEN. C1-P1-C Player Lifecycle, custom skin validation, autonomous AI-to-C1 skill execution, mod integration, multiplayer and load scaling are **not** implemented/accepted in this snapshot.
- **C1-P1-C Player Lifecycle** requires a fresh source audit, A/B/C options, maintainer scope approval and tests before code changes. **No Test, No Push.**

---

## Historical implementation notes — source ZIP (packaging-time evidence)

The following development records were written before the Windows owner acceptance described above. In particular, phrases such as *Windows retest required* mean the evidence was pending **when that package was prepared**, not that later owner-reported acceptance should be ignored. They are retained to distinguish implementation, isolated Java checks, Forge build and actual gameplay.

## C1-P1-A — Rotation Fix #1: Minimal Compile Fix (2026-10-15; CANDIDATE)

- Corrected one invalid field access in `C1P0ClientSync.java`: `session.revision()` → `session.revision`. `RotationSession` exposes `revision` as a field, not a method; the Windows Forge `compileJava` failed on this line.
- No change to rotation logic, packet contracts, C1-P0 two-phase network behavior, B+ movement, identity persistence, or backend. Existing Forge deprecation warnings are out of scope.
- Local regression: P0 Sync **26 assertions**, P1 Motion **29 assertions**, P1 Rotation **29 assertions**, and SavedData/UUID Save/Load **12 assertions with test doubles** passed (96 total). Compiled with `javac --release 17` using a JDK 21 host; this is not a Forge build. Backend pytest printed **185 passed, 175 subtests passed**, but the run was interrupted after the summary, so no clean exit-code confirmation. Local Forge Gradle wrapper failed to resolve `services.gradle.org` before compiling. Windows Forge `build` and Minecraft rotation/restoration regression must be rerun to close the quality gate; the previous Windows build failed.

---

## C1-P1-A — Rotation Fix #1: Full Rotation State Synchronization (2026-10-15; CANDIDATE)

- Added separate persisted `yaw`, `pitch`, `headYaw`, and `bodyYaw` fields; migrated legacy yaw-only records without changing world/actor/profile UUIDs or the existing SavedData key.
- Restored all four angles before ServerLevel registration, and added an event-driven session-scoped `RotationSnapshot` / `RotationApplied` handshake after client entity confirmation or explicit turn; no extra idle tick/polling.
- Added `/npcortex c1 rotation <name>` diagnostics, pure Java rotation/migration/ACK unit tests and Windows Minecraft Acceptance steps in `docs/c1-p1-a-rotation-fix1.md`.
- Local checks: Java 17 P0 Sync 26 assertions, P1 Motion 29 assertions, Rotation State/ACK 29 assertions, and **SavedData Save/Load 12 assertions with isolated NBT test doubles**. Backend pytest displayed 185 passed + 175 subtests passed but process was terminated without confirmed exit 0. Forge compile blocked before compilation by unavailable Gradle 8.8 distribution (network failure).
- **Candidate, not Forge/Gameplay verified:** Windows `dev-forge.ps1 -Task build` and visual restore tests are still required. Other B+ physics and C1-P0 Networking scope unchanged.

---

## C1-P1-A — B+ Controlled Player Physics Proof (2026-10-15; CANDIDATE)

- Added a **bounded, server-controlled movement intent** controller separate from the `C1PlayerAvatar` entity. Uses Vanilla `Player.travel(Vec3)` and `jumpFromGround()` under opt-in operator test commands, without per-tick teleport, Qwen polling or autonomous navigation.
- Added `/npcortex c1 move <name> <forward|back|left|right> <0.5..8>`, `turn <name> <yaw>`, `jump <name>`, `settle <name>`, `stop <name>` and `motion <name>` for controlled feasibility tests.
- Added stall, timeout, unloaded-chunk and cancellation bounds. Runs active/settling sessions only; no force-loading distant chunks, no idle AI loop and no backend modifications.
- Recorded live server-side position/yaw into the existing C1 SavedData schema, retaining persistent World/Actor/GameProfile UUIDs.
- Preserved C1-P0 Fix #2 viewer-specific two-phase synchronization and passive connection. No P1-B Inventory/P1-C Lifecycle, skin, TaCZ, Minecraft actions or autonomous daily routine implemented.
- Local validation: dependency-free Java 17 `C1P1MotionPlan` compiled and passed **29 JVM assertions**. Forge Build and real collision/gravity/client interpolation/restart tests are **not yet verified**; see `docs/c1-p1-a-controlled-player-physics.md`.
- Python Backend regression displayed **185 tests / 175 subtests passed** but the test process hung after the summary and was interrupted by timeout; this is not a clean exit-0 run. Source/API unchanged.
- Based on user-reported **C1-P0 Fix #2** successful Windows single-player Spawn/Restart/Re-tracking/Despawn acceptance; dedicated Custom Skin and multiplayer remain open.

---

## C1-P0 — Fix #2: Two-Phase Viewer Synchronization (2026-10-15; CANDIDATE)

- Implemented a **viewer-scoped** Forge `SimpleChannel` handshake: PlayerInfo preparation, Client `ProfileReady` acknowledgement, Server verification request, and client-side `RemotePlayer` presence observation based on UUID + Entity ID.
- Added bounded recovery when a tracked Client reports missing: up to two viewer-only vanilla player-spawn packet retries, without creating another Server-side entity or modifying existing identities/SavedData.
- Added session token validation, disconnect/StopTracking/dimension/respawn cleanup, server-side timeout and a diagnostic `/npcortex c1 sync <name>` command.
- Added `scripts/test-c1-p0-sync.ps1` and a dependency-free Java 17 state-machine test (26 assertions pass in the packaging environment). Full Python Backend regression separately passed **185 tests and 175 subtests (exit 0)**; dedicated Windows Forge Build and Minecraft restart tests remain required.
- Preserved C0 Backend, Python/SQLite v4, Player-based architecture and single `CHANGELOG.md` policy. No movement, AI action loop, custom skin or TaCZ combat added.
- **Quality Gate remains OPEN:** the Forge Gradle distribution is unavailable in the packaging environment; Java syntax and a unit state-model test are not Forge compilation or gameplay acceptance. See `docs/c1-p0-network-sync-fix2.md`.

---

## C1-P0 — Build Failure Fix #1: SavedData API (2026-10-15; Windows retest required)

- Updated `C1IdentityData.get(MinecraftServer)` to use the three-argument
  `DimensionDataStorage.computeIfAbsent(C1IdentityData::load, C1IdentityData::new, STORAGE_KEY)`
  signature matching the Minecraft 1.20.1 / Forge 47.4.10 compilation log.
- Removed the unused `DataFixTypes` import. No changes to world save key, UUID
  serialization, spawning, networking, backend contracts or the PowerShell launcher.
- Documented the **existing** repository-root commands
  `./scripts/dev-forge.ps1 -Task build` and `./scripts/dev-forge.ps1 -Task runClient`.
- Local verification: `C1IdentityData.java` compiled with `javac --release 17`
  against isolated 1.20.1-style API test doubles; a Java harness passed a
  UUID save/load/deactivate round-trip against those test doubles. This is
  **not** a real Forge compile or Minecraft runtime test.
- `bash ./gradlew --offline --no-daemon compileJava` could not reach compilation
  because Gradle 8.8 distribution was unavailable to the wrapper in this
  environment (network/DNS failure). Windows Forge Build and in-game
  C1-P0 gates remain unverified. `ModLoadingContext.get()` deprecation warning
  is out of scope.

---

## C1-P0 — True Player-based NPC Feasibility Prototype (2026-10-15; UNVERIFIED)

- Added a **stationary** `ServerPlayer` subclass with a passive synthetic packet listener and opt-in `/npcortex c1` operator commands for identity, spawn, info, list and despawn. This is **not** an autonomous NPC or proven gameplay capability.
- Added stable World/Actor/GameProfile UUIDs via overworld SavedData and explicit active/deactivated restore intent.
- Added PlayerInfo packet preparation, Forge start-tracking/login/respawn/dimension viewer notifications, and cleanup on explicit despawn.
- Kept C0 backend, SQLite v4, conversation bridge and old APIs unchanged. No PathfinderMob, FakePlayer proxy, navigation, mining, building or TaCZ integration.
- **Unverified:** Gradle/Forge compile was blocked by unavailable Gradle 8.8 download/DNS; Minecraft client and restart tests not run. Default vanilla skin only; custom PNG/model selector not implemented.
- See `docs/c1-p0-player-feasibility.md` for risk register, Windows acceptance procedure and test limitations. **Do not mark P0 completed until quality gates pass.**

---

## NPCortex C0-B — Windows Acceptance Script Fix (A)

Date: 2026-10-15
Baseline: `NPCortex_v0.1.1-alpha_C0B_StructuredDecisionReliability_FullRepository.zip`

### Scope

Fix only the Windows `-VerifyGoalId` acceptance assertions after real-Qwen headless testing succeeded and a persisted ledger was inspected directly. No changes to Backend, Ollama transport, SQLite schema v4, B1–B3, Java/Forge, or runtime action semantics.

### Changes

- `scripts/test-c0-headless.ps1`: Materialize all action and filtered-status PowerShell pipeline results as arrays before evaluating `.Count`; verify exactly two attempts, one `unverified` and one `succeeded`.
- Print concise action-ledger diagnostics (`action_id`, `skill_id`, `status`, `verdict`) with actual counts **only if** the expected numbers disagree.
- `backend/tests/test_c0_windows_acceptance_script.py`: Three static regression tests protect the array conversion, eliminate old pipeline-count assertions, and require mismatch diagnostics.

### Observed user evidence prior to patch

- Real Qwen headless mock-Forge workflow passed, including false-success rejection and verified block completion.
- After restart: Goal persisted as `completed`, with exactly two persisted actions. Direct API inspection showed `unverified`/`effect_not_observed` and `succeeded`/`observed_effect`.
- Original Windows acceptance command failed at `Unverified action status persisted` despite the direct API reporting exactly one matching action.

### Release gate

Candidate until the fixed `-VerifyGoalId` path is executed in Windows PowerShell and all assertions pass. Native PowerShell and real Qwen execution were not available in the assistant's Linux environment.

---

## NPCortex C0-B — Structured Decision Reliability

Base: `NPCortex_v0.1.1-alpha_C0_CompleteHeadlessAgentRuntime_FullRepository.zip`

### Changed

- Grounded Qwen agent decisions in per-observation advertised skills;
  planner receives schema, strict argument shapes and bounded examples.
- Ollama C0 decisions now use a per-step JSON Schema via `/api/chat.format`,
  deterministic temperature zero, and a C0-only `num_predict=256`.
- Added diagnostic retry messages with server-derived rejection reasons;
  invalid skill IDs, unobserved positions and malformed args still fail closed.
- Added `PlannerDecisionError` mapped to 502 `planner_invalid_decision`,
  distinguishing invalid Qwen decisions from genuine `provider_unavailable`.
- Added 10 focused tests including Ollama payload isolation and HTTP error shape.
- Added `scripts/test-c0-planner.ps1` and Windows verification documentation.

### Explicit non-changes

- No SQLite schema changes (still v4); no destructive migrations.
- No changes to Goal or Action state machines, verification, skill execution,
  B1-B3 behavior, Java/Forge source, Player NPC architecture, or GitHub.
- Still a headless/mock-Forge C0 test; real Windows Qwen integration pending.

---

## NPCortex C0 — Complete Headless Agent Runtime (Candidate)

Based on `NPCortex_v0.1.1-alpha_B3B_HTTPLifecycleHardening_FullRepository.zip`.

- New `agent_contracts.py`: validated world/dimension/actor-bound snapshots,
  observed blocks, inventory, nearby entities/drops, and typed success criteria.
- New `agent_skills.py`: modular capability definitions and strict arguments,
  preconditions and independently checkable postconditions (`move_to`,
  `break_block`, `place_block`, `pickup_item`). No real-world skills are executed.
- New `agent_runtime.py`: bounded goal/decision/action orchestration using
  Qwen JSON, no hardcoded tree-specific policies, request/observation/result
  idempotency, pending action recovery, conservative verification, model
  failure budget and per-goal in-process serialization.
- Schema v4 adds `agent_goals`, `agent_observations`, `agent_decisions`,
  `agent_actions` without modifying old B1–B3 records or API contracts.
- Additional HTTP routes under `/v1/agent/*` via existing HTTP server.
- Windows scripts: `test-c0-regression.ps1` and `test-c0-headless.ps1`.
- Documentation: `docs/c0-headless-agent-runtime.md`,
  `docs/c0-windows-verification.md`, API/README/Future Roadmap updates.
- All Forge Java sources unchanged. No GitHub operations.

**Explicitly not included:** Forge player-based NPC Entity/Movement/Rendering,
real Minecraft perception/actuation, crafting, mining, tree harvesting,
autonomous world loop, relationships and daily schedules.

---

## NPCortex B3-B — HTTP Request Lifecycle Hardening

Base: B3 AI-Driven Social Runtime Full Repository ZIP (74 files). Scope: Windows 10053 stability bug, without B3 feature expansion.

- `backend/src/npcortex_backend/server.py`: bounded, chunked discard of rejected oversized POST bodies before emitting HTTP 413; same discard for unknown route 404; explicit close for invalid framing, unknown-length or excessive/slow requests; JSON API contracts unchanged.
- `backend/tests/test_http_request_lifecycle.py`: 5 focused HTTP 400/404/413 tests, 116 total including existing 111 tests.
- `scripts/test-b3-http-lifecycle.ps1`: strict Windows targeted regression runner with repeat count.
- `docs/b3-http-lifecycle-hardening.md`: audit evidence, trade-offs, acceptance criteria and Windows procedure.
- README: candidate and test entrypoints.

No SQLite migration. No changes to AI behavior, social scheduling, Ollama integration, Forge Java source or existing API routes. No GitHub operations.

**Limit:** Rejected content longer than the bounded 1 MiB drain budget, slow or malformed incoming framing may still lead to a TCP reset due to intentional connection closure. This is preferred to unrestricted request-body consumption on a localhost development HTTP server.

---

## NPCortex B3 — AI-Driven Headless Social Runtime (Implementation Candidate)

**Base:** the user-accepted B2-C Persistence Architecture Refactor Full Repository.

### Added

- `backend/src/npcortex_backend/social.py` — `SocialStore` with durable Social Run + Step journal, active/terminal statuses, explicit operator stop, failure budget (3 consecutive provider failures), idempotent step records, SQLite transactions.
- `backend/src/npcortex_backend/social_runtime.py` — Qwen speaker/stop selection, strict JSON/UUID checks, bounded 2–24-step orchestration, response retry, secure context isolation, per-dialogue serialization, crash-window recovery via original B2 request UUID.
- New HTTP API: `POST /v1/social-runs`, `GET /v1/social-runs/<uuid>`, `POST /v1/social-runs/<uuid>/step`, `POST /v1/social-runs/<uuid>/stop`.
- `OllamaProvider.chat_json()` with Ollama JSON format for coordinator only; legacy `.chat()` remains unchanged.
- SQLite schema v3 with additive `social_runs`, `social_steps`; existing B1 and B2 data and API are preserved. No destructive migration.
- `backend/tests/test_b3_social.py` — isolated tests: speaker choice, no impersonation, budgets, stop, retries, restart, partial-commit recovery, concurrent requests, HTTP and Ollama JSON mode transport.
- PowerShell `scripts/test-b3-social.ps1` for real Qwen acceptance (including pause/resume and post-restart verification), `scripts/test-b3-regression.ps1` for strict Windows Python regression.
- `docs/b3-headless-social.md` API contract, rationale and C extension roadmap status.

### Changed

- `ChatService` exposes creation/inspection/step/stop service methods, shares the B2 dialogue lock, and refuses manual turn insertion into an active B3-run-owned dialogue. Refactored the internal B2 turn method to make it callable safely under that same lock.
- B2 explicit close also closes the associated active B3 run. Existing B2 dialogue calls without a B3 run preserve their contract.
- README, API docs, architecture and roadmap updated for new candidate, v3 schema and future C boundary.
- B2 migration test now expects the **current schema v3** while still validating v1→v2→v3 data survival.

### Explicitly out of scope

No Minecraft Entity, NPC UI, pathfinding, world reading/events, autonomous loop/timer, NPC daily-life agents, dynamic relationships, social memory across dialogues, TaCZ or other game actions. No GitHub operations.

### Compatibility and risks

- SQLite **v3 is forward-only**. Back up the SQLite file with the Backend stopped before first B3 startup. Do not downgrade a migrated DB by launching B2-C against it.
- Explicit request UUID required for every B3 step, enabling safe retry after loss of HTTP response and potential between-journal crash.
- Qwen may return malformed/non-compliant JSON; planner retries once and returns structured 502 if still invalid. Three consecutive model failures close the social run. A new dialogue/run is needed then.
- Existing in-memory locking is **single-backend-process**; no multiplayer auth or distributed locking is claimed.
- An already-running Ollama/Qwen model and Windows environment are needed for user acceptance; fake Ollama HTTP tests do not prove real Qwen response reliability.
- Forge source unchanged; full Forge Build depends on Gradle distribution availability and suitable JDK 17.

---

## B2-C Stability Fix — Persistence Architecture Refactor (Implementation Candidate)

- Introduce `SQLiteDatabase` Unit of Work for explicit short-lived snapshot
  reads, `BEGIN IMMEDIATE` serialized writes, rollback, and guaranteed close.
- Route WorldStore and DialogueStore data operations through the central
  manager; preserve SQLite schema version 2 and all B1/B2 endpoint contracts.
- Make B1 transcript read atomic with conversation metadata.
- Close sqlite3 connections explicitly in Windows-sensitive B1/B2 test fixtures.
- Drain bounded bodies of unknown POST routes before sending the existing 404;
  share server lifecycle settings between production and HTTP tests.
- Add six targeted tests for close-counting, rollback, concurrent writes,
  read-only transactions, migration failure cleanup, and repeated 404 responses.
- Add Windows `scripts/test-b2-stability.ps1` and documentation.
- No GitHub operations, Forge Java changes, B3 implementation, or C world simulation.

---


## B2 — Multi-Actor Dialogue Engine (v0.1.1-alpha implementation candidate)

### Added

- `backend/src/npcortex_backend/dialogues.py` — dialogue registry for 2–8
  explicitly selected participants (NPC actors and/or players), with world +
  dimension validation, 64-turn maximum, participant/speaker UUID tracking,
  atomic SQLite turn commits, and idempotent replay using request UUIDs.
- Additive **SQLite schema v1 → v2** migration, preserving B1 tables
  (`worlds`, `actors`, `conversations`, `transcript`) and original data.
  New tables: `dialogues`, `dialogue_participants`, `dialogue_turns`.
- `POST /v1/dialogues`, `GET /v1/dialogues/<uuid>`,
  `POST /v1/dialogues/<uuid>/turns`, `POST /v1/dialogues/<uuid>/close`.
- Qwen generates **one requested NPC's speech at a time**, using that actor's
  Character Pack and dialogue-local shared context. No private B1 transcript
  is injected into other NPCs' contexts.
- A2 session locking/concurrency limits reused per dialogue, including model
  semaphore. Repeated speaker, invalid participant, stale sequence or closed
  dialogue returns a structured 409 without appending a turn.
- Limit and explicit close; responses carry speaker identity, turn sequence,
  remaining turn budget and replay status. Failed model/storage requests cannot
  commit a partial dialogue turn.
- `backend/tests/test_b2_dialogue.py` — migration, isolation, replay, API,
  concurrency, rollback and limits coverage.
- `scripts/test-b2-dialogues.ps1` — real local Qwen and persistence test on
  Windows; `docs/b2-verification.md` and expanded API/architecture docs.

### Preserved

- All B1 routes and player-to-actor persisted transcripts; all A2 `/v1/chat`
  success fields and Forge `/npcortex ask` behavior.
- Same version `v0.1.1-alpha` candidate: no official 1.0 or new release implied.
- Unchanged Forge Java sources and build configuration.

### Explicitly excluded

- No autonomous initiation, daily routines, social relationships, actor goals,
  background world loop, entity spawning, agent actions, world perception,
  multiplayer authorization or C roadmap features.
- No GitHub operations, no file deletion of user databases.

### Known risks

- Backend must be a single localhost process (no shared distributed locks or
  authentication). SQLite v2 cannot be opened by old B1 code; take a stopped-DB
  backup before first migration.
- Last 12 dialogue turns (A2 context window) are sent to Qwen, while up to
  64 are durably saved. Old turns may not influence generated speech.
- A request ID protects *persisted* turns against duplicate processing; a crash
  after model generation but before SQLite commit may regenerate an answer.
- Headless API only. Owner's JDK 17 Forge build / real Ollama-Qwen / Minecraft
  tests remain necessary before considering B2 complete.


### B1 — World & Actor Identity Foundation (v0.1.1-alpha candidate)

- Added `identity.py` for canonical world/actor UUID, namespaced dimension and
  player ID validation. Character Packs remain templates, not Actor identities.
- Added `world_store.py`: SQLite v1 schema (`worlds`, `actors`, `conversations`,
  `transcript`), atomic initialization, WAL, foreign keys, UUID actor registry,
  deterministic conversation identity, atomic exchange commits, bounded stored
  transcripts (200 messages default), explicit future schema-version refusal.
- Added localhost-only `POST /v1/worlds`, `POST /v1/actors`,
  `POST /v1/actor-chat`, `GET /v1/actors/<uuid>`,
  `GET /v1/conversations/<uuid>` with structured 400/404/409/503 errors.
- Extended `ChatService` to support persistent player-to-actor dialogue using
  existing A2 per-session locking and model request limits; failed Ollama calls
  do not append incomplete exchanges to SQLite.
- Added `NPCORTEX_DB_PATH` and `NPCORTEX_MAX_TRANSCRIPT_MESSAGES` configuration.
- Added B1 Python regression tests and full API/Windows verification docs.
- Kept legacy `/v1/chat`, its success response, and the existing Forge command
  unchanged (legacy history stays in RAM by design). No NPC-NPC/C features.
- Recorded future autonomy features in planning documentation only.

**Known risks:** SQLite + actor API have no authentication; single backend
process is required; ForgeGradle Build and real Ollama/Minecraft verification
await a Windows developer machine. No migration from ephemeral A2 RAM is possible.


### v0.1.1-alpha — A2 Stability Hardening (development baseline, 2026-10-14)

This is a stability patch on top of the provided `NPCortex-Development.zip` Forge conversation-bridge baseline, **not** an NPC entity or autonomous-agent release. Forge/Minecraft integration is not yet verified; do not mark this sprint completed until the project owner's Windows quality gate passes.

#### Backend

- Serialize requests sharing the same `(player_id, character_id)` for coherent LLM context; limit outstanding requests per session (default 2, including the active one) and their wait time (default 20 s).
- Bound in-memory sessions (default 128); remove idle histories after 30 minutes and evict least-recently-used **inactive** sessions on pressure. Active and queued sessions cannot be evicted.
- Limit simultaneous Ollama calls (default 4) and waiting for provider capacity (default 15 s).
- Preserve `/health` and `/v1/chat` success schemas. Preserve the error string while introducing stable `code` identifiers and bounded 429/503 busy responses.
- Validate backend environment configuration early (port, model, Ollama origin URL, character-packs directory and resource-limit ranges).
- Reject unsupported chunked request bodies and ambiguous duplicate Content-Length headers.
- Align backend package metadata/version with Forge `0.1.1-alpha` (`pyproject.toml` distribution version `0.1.1a0`).

#### Forge bridge

- Display descriptive backend HTTP errors and distinguish HTTP timeout, connection refusal and invalid backend configuration in Minecraft chat.
- No new gameplay feature or entity; no command names, HTTP routes or successful response fields changed.

#### Tests and docs

- Retain the original five Python tests and add concurrency, history lifecycle, model capacity, config validation, and live localhost HTTP (mock provider) regression tests.
- Correct README/Windows instructions to say that the Gradle Wrapper **is included** in this ZIP.
- Document new environment variables, HTTP error codes and owner-run Windows/Minecraft acceptance checklist.

#### Limitations

- No persistent chat memory, authentication, real NPC, navigation or agent actions.
- Session cleanup is lazy on subsequent requests, and eviction forgets prior chat context.
- Backend calls to real Ollama/Qwen and ForgeGradle/Minecraft gameplay remain **unverified** in this tool environment; see `docs/a2-verification.md`.
- No GitHub actions were performed. Commit, push, branch and merge remain the project owner's responsibility.
