# Changelog

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
