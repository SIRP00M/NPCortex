# Changelog

NPCortex follows **development-milestone pre-release versioning**. The current source preview is **`v0.2.4-alpha.2`** (Forge) / **`0.2.4a2`** (Python distribution). Entries describe source milestones, not stable releases or confirmed full Quality Gates. Older milestones remain available in the [GitHub commit history](https://github.com/SIRP00M/NPCortex/commits/main).

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
