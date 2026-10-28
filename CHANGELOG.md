# Changelog

NPCortex follows **milestone-based alpha versioning**; entries document features added to the public source and verification boundaries, not production certification. Older milestones and exact commits remain available in the [GitHub history](https://github.com/SIRP00M/NPCortex/commits/main).

## Unreleased — `v0.2.7-alpha.1` · Guarded native block breaking and item pickup (2026-10-28)

**Base:** GitHub `main` commit `b192549` (`v0.2.6-alpha.3`). This source milestone adds opt-in native world actions to the previously guarded navigation and world-inspection foundation; it is not a stable release or automatic AI mining system.

### Added

- **Manager Brk / Pck:** explicit nearby block-break and dropped-item pickup via Preview → Confirm, with per-request operator authorization and a restrictive allowlist. Breaking uses bounded timed mining; pickup uses an explicit action, not an item magnet.
- **Durable native action ledger:** additive `cplus_wn_*` records, claim exclusion for competing NPC actions, restart fencing, typed receipts, `HOLD` for ambiguous results, and operator-triggered reconciliation. Python backend coordinates records but does not itself mutate the Minecraft world.
- **Safe effect boundaries:** cross-checks for conflicting movement, inventory, follow/navigation and guarded task states, plus bounded native request processing; existing C1/C2 and E3 contract behavior is preserved.
- **Native verification tools:** Java contract/self-tests, Python store/HTTP/wire regressions, test datapack and real Forge GameTest harness source. Synthetic SQLite schema fixture is included because migration-compatibility tests require it.

### Testing and limitations

- The owner reported successful Windows single-player native block breaking, explicit pickup into NPC inventory and competing-actor `CLAIM_CONFLICT` in the scenarios tested. The original candidate technical report also documents offline/GameTest runs; these are separate evidence types and do not certify all scenarios in this packaged ZIP.
- This packaging run: **53/53 milestone static tests**, **72/72 native backend tests**, and **5/5 HTTP request lifecycle tests** passed. Full Python backend discovery was attempted on Python 3.13 but did not complete within the execution limit; GitHub CI's Python 3.11 full-suite result must be checked after publishing. A fresh native JDK 17 Forge build was not run here.
- **Not implemented/accepted:** automatic walking before Brk/Pck, automatic proximity item pickup, autonomous Qwen task dispatch, broad multiplayer/dedicated-server acceptance, G4 MSPT/performance acceptance, crash-window exactly-once world mutation, and complete TAB display-name verification.

### Public source packaging

- Aligned Forge/runtime `0.2.7-alpha.1` with Python package `0.2.7a1`, refreshed public README/CHANGELOG and added milestone Patch Notes; Java/Python gameplay implementation remains as supplied in `NPCortex(10).rar`.
- Excluded AI-agent configuration, workstation caches, worlds, real databases, raw evidence/manifests and obsolete G5 `g5n` datapack helpers. Kept tracked public tests, CI, Gradle wrapper, datapack source and **synthetic v4 SQLite fixture**.
- **GitHub sync caution:** copying a ZIP over a Git checkout does *not* stage deletions of tracked legacy paths. Remove the obsolete `g5n` test-pack files from the Git index/worktree using the accompanying cleanup CMD script before staging and committing. Never delete static contract tests merely to hide a real failure.

## Unreleased — `v0.2.6-alpha.3` refreshed source snapshot · Read-only world inspection and reliability hardening (2026-10-28)

**Base:** GitHub `main` `cbd9cd9` (`v0.2.6-alpha.2`). This replacement supersedes the earlier prepared `v0.2.6-alpha.3` ZIP **without incrementing the version**. Gameplay code is preserved from `NPCortex(9).rar`; public packaging only filters local artifacts and updates version metadata/docs.

### Added / changed

- Stage 4 **read-only world perception** and `/npcortex m04 observe` with bounded, loaded-chunk-only sampling and explicit `OK`, `PARTIAL_UNLOADED`, `PARTIAL_BUDGET`, `CANCELLED` and `REJECTED` outcomes. `CommandOrigin` is diagnostic/reporting only; mutation authorization, exactly-once effect ledger, mining and pickup executors are not provided.
- HTTP rejection reliability: a framed POST rejected by Host validation now drains within existing time/size limits before the 403 response. Token checks and rejection outcomes remain in place.
- Gradle `check`/`build` now invokes Java self-tests; `scripts/run-offline-verification.ps1` provides an opt-in Windows offline verification workflow.
- M03 tick duration measurement now pairs START/END and ignores an unmatched END; this does not establish multi-NPC MSPT/performance acceptance.
- Added read-only world-perception and rejection-handling tests, Java self-tests, and [Stage 4 technical documentation](docs/stage4/README.md).

### Verification boundary

- Scope-limited C2/G3 p18 Windows gameplay acceptance remains **historical evidence**, not a fresh result for this exact source snapshot.
- Read-only world perception has offline policy tests; its live-world Forge command, dedicated-server behavior and performance are **not accepted** here. No world mutation feature or background autonomous action executor is claimed.
- The uploaded archive contained development caches, local worlds, databases, logs, candidate manifests and AI-assistant configuration; these were removed from the public ZIP. Existing sanitized test fixtures, automated tests, source, CI and previous Patch Notes are retained.
- Backend/Forge checks should be evaluated separately; a successful isolated test is not equivalent to a full native Minecraft acceptance.

## Unreleased — `v0.2.6-alpha.3` · Reliable NPC task completion at destination (2026-10-28)

**Compared with GitHub `main` commit `cbd9cd9` (`v0.2.6-alpha.2`).** This is a fix within the existing NPC names/navigation reliability milestone, not a stable release.

### Fixed

- **Correct movement task arrival verification:** Java and Python E3/G3 terminal validators compare the NPC's observed position to the horizontal **block center** `(x+0.5, y, z+0.5)`, matching C2's arrival convention, instead of the integer block corner. The vertical foot height and the existing 3D tolerance are unchanged.
- **Cross-language verification:** new shared, synthetic JSON position vectors and focused Java/Python tests verify accepted and rejected boundaries, negative coordinates and legacy durable/fail-closed states. Existing motion control, task ownership, persistence schema and world-effect permissions were not changed by this focused fix.

### Verification and limitations

- **Owner-reported Windows single-player acceptance (2026-10-28):** focused G5 Rev6 **p18** passed after the position-contract alignment, with C2 `COMPLETED`, typed G3 terminal `COMPLETED` and durable acknowledgment, SQLite integrity and one matching durable terminal record. This is a scenario-limited result from owner-provided evidence, not a fresh Forge run performed during packaging.
- Earlier Rev5 functional 1/3/5/10 NPC tests remain scoped to their tested environment. Other G5 cases, including p08 reason distinction, p10 cancellation timing, p11 mid-goal checkpoint timing, TAB name presentation and multi-round G4/MSPT performance acceptance, are **not certified by p18**.
- This public packaging preserves runtime code and automated tests from the supplied archive, removes workstation/AI-agent assets and raw owner evidence, aligns Forge/Python metadata to `0.2.6-alpha.3` / `0.2.6a3` and retains earlier Patch Notes and change history. No GitHub commit, push or tag was performed.

## Unreleased — `v0.2.6-alpha.2` · More reliable NPC navigation diagnostics and G5 verification (2026-10-28)

**GitHub comparison base:** `main` commit `3d55562` (`v0.2.5-alpha.1`). The earlier `v0.2.6-alpha.1` package was prepared but is not present on `main` at packaging time; this archive includes its readable-name/G5 changes as well as the newer Revision 6 tests. `alpha.2` denotes iteration of the same milestone, not a new stable release.

### Updated since the earlier `v0.2.6-alpha.1` package

- **Navigation diagnostics:** C2 result/cancel logging now reads the same runtime state through a small read-only formatter, preserving the pre-cancel state and terminal reason for debugging. No path selection, movement token or recovery algorithm was changed by this update.
- **G5 Revision 6:** revised per-tick cancellation attempts (maximum five), tighter save/quit snapshot correlation, explicit guarded-task terminal evidence, stricter collector diagnostics and performance uncertainty reporting.
- **Verification contracts:** updated static tests, datapack functions and PowerShell/Node regression harnesses to check that instrumented behavior is not misreported as accepted gameplay.
- **Visible names:** retained the separate client nametag and Forge TAB formatting changes from `alpha.1`. The internal GameProfile identity remains unchanged; the TAB result is **not yet visually accepted**.
- Packaging filters raw local worlds, databases, AI-agent files, build/cache data, private logs and run-specific manifests while preserving source, CI, tests, generated datapack sources and sanitized regression fixtures.

### Tests and limitations

- Earlier G5 Rev5 Windows single-player evidence: 18 PASS, 3 PENDING, 1 UNVERIFIED, 0 FAIL among 22 scenarios; functional 1/3/5/10 lanes passed in that environment. **Do not transfer these verdicts to Rev6**. Rev6-specific p08/p10/p11/p18 checks and performance acceptance are still open until evidenced.
- Static/fixture tests are not a Windows Forge Build or live Minecraft gameplay acceptance. In particular no autonomous mining, block placement, pickup, force-loaded pathfinding, dedicated-server or multiplayer release certification is claimed.

## Unreleased — `v0.2.6-alpha.1` · Visible NPC names and improved navigation testing (2026-10-27)

**Compared against GitHub `main` commit `3d55562` (`v0.2.5-alpha.1`).** This is an experimental public source milestone, not a stable release or a claim that all gameplay gates passed.

### Added / changed

- **Readable NPC names:** added an opt-in clientbound `npcortex:names` identity-to-display-name synchronization path for above-head NPC names, with bounded packets and lifecycle refresh after join, rejoin or dimension transitions. Added a separate Forge TAB display-name formatter in the latest source. Saved identity, GameProfile name/UUID, gameplay selectors and C1 motion/network identity remain unchanged.
- **G5 navigation harness revision:** extended PowerShell isolation/preflight, generated test datapack and result collector for independent 1/3/5/10-NPC profiles and navigation lanes, safer cancellation evidence and explicit failure states. No force-loading, backend replay or autonomous movement behavior was added by the harness.
- **Read-only C2 diagnostics:** bounded, sanitized terminal-reason and cancellation-state logging to distinguish cases such as long no-progress, oscillation and quiescing without changing the navigation state machine or action semantics.
- Added focused Java/source-level tests and operator documentation. The existing G5 scripts/data pack are development acceptance tools, not shipped NPC gameplay features.

### Evidence and remaining limits

- Owner-reported Windows single-player: above-head names for Reika/Mika and name persistence across rejoin, tracking and dimension round trips passed in the tested cases. **Latest TAB player-list name handling remains unverified**; earlier captures showed internal `N...` names.
- G5 revision-5 run (22 scenarios): **18 PASS, 3 PENDING, 1 UNVERIFIED, 0 FAIL**. The 1/3/5/10 NPC functional lanes passed for the tested environment. P10 quiescing cancellation, P11 in-progress Save & Quit snapshot and P18 E3 terminal review remained pending; P08 did not distinguish the failure cause. Added read-only diagnostics may help future runs, but are not retrospective proof.
- Performance is **measured, not accepted**: one 10-NPC round, no comparable baseline or profiler/MSPT acceptance. Dedicated server, multiplayer, full TAB presentation, fault injection and full-suite Forge acceptance remain open.
- This GitHub-ready packaging changed version metadata and public docs, excluded local workspaces/evidence, preserved production source logic from the uploaded developer archive, and did not commit or push GitHub.

---

## Unreleased — `v0.2.5-alpha.1` · Character Profiles, in-game Manager and bounded long-range navigation (2026-10-27)

**Base:** GitHub `main` commit `d3e95b2` (`v0.2.4-alpha.2`). This is a new public source milestone, not a tagged stable release.

### Added

- **Character Profiles:** SQLite v5 per-actor personalities, immutable history, structured/raw prompts, explicit opt-in with verified backup before existing database migration, profile import/export and revision-safe edits. Legacy Character Packs and older actor records are preserved.
- **NPCortex Manager GUI:** ESC/Pause screen entry for NPC listing, creating NPCs with arbitrary names, profile registration, editing and saving. Uses a dedicated network channel with server-side authorization and conservative offline/uncertain state handling; includes a PowerShell profile registration fallback.
- **Experimental long-range navigation:** operator `navlong` entrypoint and loaded-terrain-only bounded internal legs under a single logical C2 run, explicit long-running state, FIFO planning fairness, progress/timeout controls and conservative failed-state reporting. No force loading, automatic retry or cross-world planner.
- **Verification tools:** Profile migration/API tests, in-game Manager protocol/core tests, G5 navigation acceptance datapack source and PowerShell observation/evidence scripts, isolated Java policy tests, and public operator guides.

### Compatibility and scope

- Forge metadata: `0.2.5-alpha.1`; Python distribution: `0.2.5a1`. Runtime code is preserved from the uploaded October 27 source snapshot; this packaging pass changes metadata/documentation and filters workstation-only artifacts.
- Existing v1–v4 databases require deliberate `NPCORTEX_ALLOW_SCHEMA_V5=1` opt-in for the migration. Do not migrate an irreplaceable database without verified copy/restore rehearsal.
- This milestone does **not** implement dynamic AI-generated personalities, evolving memory, autonomous world mining/pickup, automatic unloaded-chunk navigation or a new background action scheduler.

### Verification status

- **Owner-confirmed Windows single-player:** GUI smoke scenarios including character creation/profile registration passed in the scenarios tested. Earlier G4 C1/C2 motion and shutdown cases were accepted for their specific single-player test scope.
- Reported Java/build/backend test outcomes from the development workspace are historical; the GitHub-Ready package is not by itself evidence that all those tests have been rerun.
- **Still unverified:** full long-range G5 gameplay acceptance, multi-NPC 1/3/5/10 performance, dedicated-server Manager availability, live v4→v5 migration, comprehensive security/fault injection and multiplayer/multi-viewer compatibility.

---

## Unreleased — `v0.2.4-alpha.2` · Optional API authentication and lifecycle fixes (2026-10-26)

**Compared against GitHub `main` commit `9de4ef4` (`v0.2.4-alpha.1`).** This is a targeted follow-up in the **same** `0.2.4` milestone, not a new stable release.

### Added / changed

- **Optional local token for legacy HTTP APIs:** `NPCORTEX_REQUIRE_TOKEN=1` requires the existing shared C+ loopback credential on legacy GET/POST API routes. It defaults **OFF** for backward compatibility, and `/health` stays unauthenticated. Unauthorized requests fail with HTTP 403. The existing C+ routes continue to require their credential.
- **Forge client compatibility:** the existing chat and C2 backend clients attempt to send the shared token with requests; they omit it if the local credential is unavailable. Token values must not be logged or committed.
- **Spawn-failure identity safety:** a failed avatar spawn removes its `LIVE` entry only if the entry still refers to the attempted avatar; persisted active state is deactivated only when the attempted profile identity still matches.
- **Shutdown cleanup:** remove a redundant C1 motion-stop call from C2 shutdown processing after the earlier lifecycle cleanup; retain pending-cancellation reconciliation. This does not prove all shutdown paths safe.
- Added `backend/tests/test_legacy_token.py` covering disabled/enabled auth, invalid credentials and legacy endpoints.

### Compatibility, verification and limits

- When enabled, non-Forge legacy clients and older Windows smoke scripts must send `X-NPCortex-CPlus-Token`; otherwise they can receive 403. Do not enable the flag in existing client workflows without checking this compatibility change.
- The G4 Windows single-player passes documented in the previous milestone remain **historical user-reported evidence**. No dedicated-server shutdown, F3 spawn-failure fault injection, Java client-to-flag-enabled backend gameplay, multiplayer, or M04 mining/pickup acceptance is inferred from these source changes.
- This packaging pass changed version metadata and documentation only in addition to retaining the supplied five implementation-file edits and one new test; no other runtime logic was modified. No GitHub operation was performed.

## Unreleased — `v0.2.4-alpha.1` · Guarded movement, recovery and M04 safety foundation (2026-10-26)

This milestone follows `v0.2.3-alpha.1` on GitHub `main`. The uploaded working repository has been filtered into a public source package; it is **not** a GitHub release or tag.

### Added

- **Guarded NPC movement task flow:** extend the earlier dry-run previews with operator-triggered C2 handoff, explicit task/attempt correlation, cancellation and terminal result handling. Fail-closed `HOLD` remains when the outcome cannot be proven; no automatic replay of uncertain movements.
- **Task transport and recovery:** durable operation/outbox metadata, local authentication, explicit sync/audit and bounded recovery diagnostics, plus controlled acceptance tests for network/process interruptions.
- **M03 reliability and verification tools:** opt-in in-game read-only snapshots and guided acceptance datapacks for copied test worlds; backend/transport regression and performance measurement tooling. These are not the same as successful end-to-end gameplay acceptance.
- **M04 foundation (partial):** typed sequential work contracts, durable target/step metadata, conservative world/target previews and non-destructive C1 inventory unequip support. No autonomous block-breaking or pickup executor is enabled.
- **One-command offline verification:** `scripts/accept-m04.ps1` aggregates source checks, contract tests, backend tests and (when available) a Forge build. A tooling PASS does not certify native world actions or Minecraft GameTest.

### Fixed / hardened

- Hardened HTTP request validation and loopback/local-access handling in the Python backend.
- Added targeted C1/C2 server-thread assertions for selected lifecycle and movement paths. These do **not** establish complete thread safety across all accessors, shutdown paths or multiplayer contexts.
- Corrected selected persistence/SQLite-test cleanup, movement-status empty-state handling, response correlation and Forge command/test harness regressions.

### Verification and limitations

- **Owner-confirmed (Windows single-player, G4):** spawn/despawn/motion, save/rejoin, shutdown with active C2 goal followed by rejoin, and Follow start/stop all passed in the tested scenarios. Earlier reported build/contract suites are historical evidence, not a rerun of this GitHub-ready package.
- **Not certified here:** fresh Windows Forge build, full Python suite process exit on this package, multiplayer/multi-viewer behavior, dedicated-server shutdown, exhaustive thread safety, 1/3/5/10 NPC performance, real crash/power-loss recovery and M04 automated native mining/pickup.
- M04 gates remain partial / HOLD wherever world-effect authority, command provenance or permission proof is missing. A read-only preview is not authorization to mutate the world.
- The M04 one-command script was reported failing at the evidence-reviewer gate in a prior Windows run; a successful acceptance rerun for the current package has not been supplied.

### Packaging

- Set Forge/runtime version to `0.2.4-alpha.1`, Python distribution to `0.2.4a1`.
- Replaced noisy work-in-progress release notes with concise user-facing README, consolidated changelog and milestone Patch Notes.
- Excluded local AI-assistant configuration, code-graph output, the root pending-test tracker, candidate manifests, raw SQLite/world saves, compiled caches, generated acceptance records and other workstation-specific files.
- Kept production Java/Python source, automated tests, CI workflows, public technical guides and operator tooling without changing their runtime logic.

## Previous milestones

### `v0.2.3-alpha.1` — Persistent NPC tasks and movement previews (2026-10-23)

Introduced durable read-only task records, FIFO operator-driven task dispatch, bounded observation leases, local recovery diagnostics, and typed **dry-run** movement previews. Preview alone did not initiate movement or world effects.

### `v0.2.2-alpha.1` — NPC action contracts (2026-10-22)

Introduced shared action identity, ownership, lifecycle and queue/resource contracts; this was the foundation for future task execution, not the executor itself.

### `v0.2.1-alpha.1` — Follow stability (2026-10-22)

Improved airborne-player target projection and player-following stability, including the previously observed flying-target zigzag case.

### `v0.2.0-alpha.2` — Navigation and persistent Follow (2026-10-21)

Expanded bounded terrain-aware 3D path planning, movement recovery, obstacle replanning and continuous Follow sessions.

### `v0.2.0-alpha.1` — Embodied world sensing (2026-10-16)

Added foundational world representation and bounded obstacle/terrain observation for player-based NPCs.

### `v0.1.2-alpha.2` — NPC lifecycle and combat (2026-10-15)

Extended the C1 player-avatar prototype with lifecycle, death and server-authoritative movement/combat experiments.

### `v0.1.2-alpha.1` — Player-based NPC and inventory (2026-10-15)

Added C1 player-avatar, controlled movement/rotation and transactional inventory/equipment persistence on top of the earlier C0 backend agent foundation.

Earlier **A2 / B1–B3 / C0** headless conversation, world identity, persistence and agent protocols are documented in `docs/` and preserved in the repository history.
