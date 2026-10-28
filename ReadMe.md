# NPCortex

**An open-source, local-AI companion framework for Minecraft Java 1.20.1 (Forge).**  
Create in-game player-like NPCs with their own personalities and readable display names, interact with a local AI backend, and experiment with navigation, following and carefully guarded tasks.

[Changelog](CHANGELOG.md) · [What's new](docs/patch-notes/v0.2.6-alpha.3.md) · [Character Profiles](docs/character-profiles.md) · [Roadmap](docs/roadmap.md) · [Contributing](CONTRIBUTING.md)

> **Development version: `v0.2.6-alpha.3` (28 October 2026; refreshed source snapshot).** This is an untagged **alpha source milestone**, not a stable release. Features and tests described below have different levels of verification. No automated block-breaking/pickup system, unrestricted navigation or production-ready multi-NPC behavior is claimed.

## What you can do today

| Capability | What is available | Maturity |
|---|---|---|
| **Create a player-like NPC** | Forge-side player-based NPC prototype with spawn, identity, controlled movement, lifecycle and equipment | Experimental; single-player scenarios tested; multiplayer not certified |
| **See NPC names** | Experimental above-head name display uses the NPC’s chosen logical name while preserving its internal GameProfile identity; a separate TAB-list presentation path is included | Above-head names owner-confirmed in tested single-player lifecycle cases; latest TAB behavior unverified |
| **Create a unique personality** | **ESC → NPCortex Manager** to create an NPC with a custom display name, structured personality or raw prompt; edit and save versioned profiles | Owner-confirmed Windows single-player GUI smoke tests passed for the tested creation/profile workflow |
| **Local AI conversation** | Python backend plus Ollama / Qwen 3 8B, with persistent actor dialogue and experimental social turns | Requires separately running the local backend/model |
| **Follow and navigate** | Terrain-aware bounded navigation, follow behavior, waypoint recovery and **operator-issued long-range navigation** on already loaded terrain | Experimental; not global pathfinding, no chunk forcing or cross-world routes |
| **Guarded task workflow** | Explicit movement requests, state tracking, cancellation, terminal reconciliation and read-only world observations; experimental `/npcortex m04 observe` inspects loaded blocks without modifying them | Not a general-purpose autonomous block interaction engine; the new perception path still needs live gameplay verification |
| **Testing tools** | Java/Python regressions, Windows PowerShell scripts and optional Minecraft acceptance datapacks, including G5 multi-NPC lane checks, Rev6 cancel scenarios and read-only navigation diagnostics | G5 functional lanes have limited prior evidence; Rev6 p18 guarded-task completion was accepted in one owner-tested Windows single-player run; other cancellation/checkpoint cases and performance acceptance remain open |


## What's new in v0.2.6-alpha.3 — reliable task completion

Guarded NPC movement tasks now use the **same arrival-position convention** for final verification as the navigation system. A block target `(x, y, z)` is checked against its horizontal center `(x + 0.5, y, z + 0.5)` with the original 3D distance tolerance; this fixes an incorrect *not completed* result when an NPC had successfully reached the intended destination. Java and Python verifiers share synthetic regression vectors. Existing identity validation, task ownership, cancellation rules and persistence safeguards remain in place.

The project owner confirmed the **E3/G3 p18** guarded-movement completion scenario passed in a focused Windows single-player test on 28 October 2026, including a durable completion acknowledgment and the matching stored terminal record. This does not certify every G5 scenario, TAB name presentation, dedicated servers or multi-run performance acceptance. See [release notes](docs/patch-notes/v0.2.6-alpha.3.md).

## Additional changes in this refreshed source snapshot

This replacement package **keeps the version `v0.2.6-alpha.3`** and includes further hardening from the supplied repository:

- **Read-only world inspection (Stage 4 foundation):** `/npcortex m04 observe <npc> <radius> <block>` samples already-loaded blocks with explicit partial/rejected outcomes. It does not mine, place blocks, pick up items, force-load chunks or authorize world changes. See [read-only foundation](docs/stage4/README.md).
- **More reliable local HTTP errors:** a rejected POST with a disallowed Host header drains a safely framed request body within existing limits before returning 403, reducing connection resets without changing the security decision.
- **Build-time safeguards:** Gradle `check`/`build` is wired to run Java self-tests; a separate [offline verification script](scripts/run-offline-verification.ps1) coordinates Java/Python checks on Windows.
- **More accurate timing metrics:** M03 pairs tick-start and tick-end samples and ignores unmatched ends. This is a measurement fix, not a claim of accepted 1/3/5/10-NPC performance.

**Verification boundary:** The new Stage 4 observation path remains unverified in live Minecraft in this packaging session. Previous G5 p18 movement acceptance remains scenario-specific; the TAB-list name issue, other pending G5 scenarios, performance and multiplayer are not declared resolved. This source snapshot was not committed or pushed automatically.

## Requirements

- Minecraft **1.20.1**, Forge **47.4.10**, and **JDK 17** for the Forge development environment.
- **Python 3.10+** for the backend and Windows PowerShell for the included `.ps1` scripts.
- [Ollama](https://ollama.com/) with `qwen3:8b` for local model-based dialogue. No paid cloud AI API key is required for the default setup.

## Quick start (Windows)

From the repository root in PowerShell:

```powershell
ollama pull qwen3:8b
.\scripts\dev-backend.ps1
```

In another terminal, build or run the Forge development client:

```powershell
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

The backend normally listens on **`127.0.0.1:8765`**. The `/health` endpoint checks backend availability, **not** whether Ollama is ready. You can use `/npcortex status` in Minecraft; the broader operator command set requires appropriate permission. See [development setup](docs/development.md), [Forge setup](docs/phase-3-forge.md) and [API reference](docs/api.md).

## Character Profiles and the in-game Manager

The **Character Profile** is the authoritative per-NPC personality record in SQLite. The Manager is reached from the Minecraft **ESC/Pause** menu; it can list NPCs, create new ones and save personality edits. Names **no longer need to match a Character Pack directory** for new profile-based NPCs. Existing Yui/Guide Character Packs remain supported for legacy actors and optional presets.

New profiles use **SQLite schema v5** with immutable revisions and revision-conflict checks. **An existing v1–v4 database does not upgrade automatically**: stop the backend, back up your data, rehearse with a copy, then deliberately set `NPCORTEX_ALLOW_SCHEMA_V5=1` for the upgrade. A fresh database starts at v5. Keep private API tokens, database files and backups out of Git. The new profile endpoints require the local token even when the legacy API token requirement is disabled.

See [Character Profile setup, API, migration and tests](docs/character-profiles.md). A [PowerShell registration fallback](scripts/register-npc-profile.ps1) is provided for operators.

> **Acceptance boundary:** The project owner confirmed tested Windows **single-player GUI smoke scenarios** (including creating Reika through the Manager) passed. This does not establish dedicated-server operation, real existing-database migration, security fault injection, GUI multi-client behavior or full 1/3/5/10 NPC performance. The tested above-head nametag now showed the chosen NPC name. The latest TAB player-list formatter remains pending in-game visual confirmation.

## Bounded long-range navigation

The experimental operator command is:

```text
/npcortex c2 navlong <npc> <x> <feetY> <z>
```

- Attempts multi-leg movement **only across loaded terrain**, within an implementation cap of **160 blocks**. It uses bounded planning, progress/timeout controls and FIFO planning slots.
- Maintains one logical C2 goal/action for internal legs; it does **not** load chunks, save an enduring route or automatically retry uncertain outcomes.
- Obstacles, unloaded terrain and insufficient progress can result in a conservative failure. Existing `navigate`, `move`, Follow and guarded task entrypoints remain distinct.

See [long-range navigation design and manual checks](docs/c2-long-range-option-d.md) and [G5 test-harness guide](docs/g5-test-harness/README.md). An earlier Windows single-player G5 run reported the functional 1/3/5/10 NPC lanes passing, but **not** full G5 acceptance: cancellation quiescence, save-quit checkpoint timing, diagnostic reason distinction, and multi-round MSPT profiling need further evidence. **Rev6 p18 E3 terminal completion was separately accepted by the owner** in a focused Windows single-player test. These outcomes do not certify dedicated-server or multiplayer operation.

See [Visible NPC names](docs/visible-npc-names.md) and [G5 revision 6 test guide](docs/g5-test-harness/REVISION-6.md) for public details and limitations.

**Revision 6 adds** bounded cancellation attempts, per-action cancellation/result diagnostics, stronger checkpoint/terminal evidence correlation and conservative performance flagging. These are verification tools; this package has no new autonomous block interaction or certified multi-client behavior. Earlier gameplay verdicts do not certify Revision 6.

## Repository layout

```text
backend/           Python local AI HTTP backend, SQLite persistence and tests
forge-mod/         Forge 1.20.1 Java mod and independent JVM regression tests
character-packs/   Optional JSON presets and legacy characters
integrations/      Experimental integrations and opt-in test datapacks
scripts/           Development, verification and operator tooling
docs/              Public architecture, API, setup and test guides
.github/workflows/ Python and Forge build CI configuration
```

## Security and limitations

- Keep the backend bound to **loopback**. Do not expose it directly to the internet. Optional local API authentication does not replace a public-service security review.
- Actor/world IDs, server-side permission checks, and token handling remain authoritative on the server. GUI forms must not be treated as proof of authorization.
- There is **no certified automatic mining, pickup or general world mutation**. M04 contracts/previews and test harnesses are not an autonomous executor.
- The alpha milestone has not passed full multi-client, dedicated-server, multi-mod, database-upgrade, G5 performance or fault-injected Quality Gates.

NPCortex is distributed under the [MIT License](LICENSE) for original project code. Minecraft/Forge, models and external assets have their own licenses. See [CONTRIBUTING.md](CONTRIBUTING.md) for developer guidelines.
