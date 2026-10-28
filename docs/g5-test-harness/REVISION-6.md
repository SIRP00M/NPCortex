# G5 harness revision 6 (collector `rev6.0`) + D1 diagnostics + TAB name

Owner-approved scope "Option C + D1" after the Rev5 gameplay run (run `rev5-20261027-183353`, world `NPCortex_G5_Rev5_02`).
The Rev5 archives (`g5-results/NPCortex-G5-TestResults-20261027-191359.zip`, `...-191439.zip`) and their reports
(18 PASS / 3 PENDING / 1 UNVERIFIED / 0 FAIL) are **immutable history**. A Rev6 report is a new document with its own version stamp; it never rewrites a Rev5 verdict.

Everything below is **BUILT and offline-tested; Minecraft gameplay is PENDING (Owner)**. Nothing here claims gameplay PASS.

## What changed

| Area | Change |
|---|---|
| Production (log only) | `C2Diagnostics` + two `LOG.info` lines in `C2AgentRuntime` (D1-A in `result()`, D1-B in `cancel()`). `C2AgentRuntime` minus those two lines is byte-identical to the Rev5 file (pinned by a static contract). |
| Production (display only) | `NpcTabName`: server-side `PlayerEvent.TabListNameFormat` for `C1PlayerAvatar` only. |
| p11 | Collector fix: the snapshot **closest in time** to the checkpoint (same +-1.5 s window, same MOVING/REPLANNING/DECIDED rule, plus "belongs to this scenario's goal") instead of the first one in the window. Real Rev5 snapshot pair kept as a regression fixture (`scripts/fixtures/m03-real-p11-rev5`). |
| p10 | Dedicated phase, **at most 5 attempts**, per-tick watch (`lib/p10_watch`, own `1t` schedule, bounded), one cancel per attempt, per-attempt timeout (150 polls) with a noted cleanup cancel, explicit COMPLETED_WITHOUT_CANCEL exit. No `c2 status` any more (function feedback is suppressed by vanilla: the Rev5 log has zero `C2=` lines). |
| p08 | Reason comes only from D1-A. |
| p18 | The pack stops after the movement and tells the Owner to **type** `taskmovefinalize yui` then `taskmovestatus yui`; the collector needs both typed answers **and** the durable database terminal. |
| Collector | Shared reads (`FileShare.ReadWrite\|Delete`, unfinished last line dropped and noted), relative paths resolved against the PowerShell location, backend logs found through `backend-run.json`, `backend-stop.json` (health before shutdown), run-scoped gate log, performance first-sample flag, evidence gaps, version + provenance, separate status dimensions. |

Not changed (deferred by the Owner): `CPlusM03Runtime` (the first-sample timing defect), G4 acceptance, journal recovery, motion ownership, E3 behaviour, scheduler/FIFO, SQLite schema, backend contracts, `MAX_ACTORS` (10).

## D1 logging contract (log only, one line per event, no per-tick logging)

```
NPCortex C2 diag cancel actor=<actor uuid> goal=<goal uuid> phase=<journal phase> long=<LongState>
NPCortex C2 diag result actor=<actor uuid> goal=<goal uuid> succeeded=<true|false> phase=<journal phase> long=<LongState> reason=<text, <=160 chars, control characters replaced by spaces>
```
Example (illustrative, not a recorded result):
```
[..] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 diag cancel actor=a015695c-... goal=511414dd-... phase=REPLANNING long=LEG_QUIESCING
[..] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 diag result actor=a015695c-... goal=7f3088c6-... succeeded=false phase=MOVING long=LEG_ACTIVE reason=LONG_NO_PROGRESS legs=6 best-effort heuristic; no path claim
```
* **D1-B** is logged in `cancel()` after the active run is resolved and **before** any mutation (motion stop, replanning stop, scheduler release, cancellation recovery, journal write): the state of the invocation, not a later one.
* **D1-A** is logged in `result()` after the ownership guard and **before** the `ACTION_FINISHED` event, the journal write `RESULT_PENDING` and the HTTP send. The reason is the exact `detail` string; `finish()` later replaces the journal message with the C0 detail (`C0 action=failed ...`), which is why `c2 status` could never show the reason.
* No token, prompt or credential is involved. Only ids, the phase/state names and the reason text that production already built.
* `C2AgentRuntime` calls `C2Diagnostics.cancel(run)` / `result(run, ...)`; the state-specific read happens inside the formatter so the existing source-shape contract "cancel has no LongGoal special case" (`C2LongGoalBoundarySelfTest`, 81 checks) stays valid and unchanged.

## TAB player list (Forge 47.4.10, verified with `javap`)

* `ClientboundPlayerInfoUpdatePacket.Entry(ServerPlayer)` carries `player.getTabListDisplayName()`; Forge fires `PlayerEvent.TabListNameFormat` inside that getter and caches the result. Every `createPlayerInitializing` call site of `C1P0Runtime` therefore sends the logical name without any new packet.
* `NpcTabName.onTabListName`: only for `C1PlayerAvatar`, name resolved from the authoritative `C1IdentityData` (by the avatar's GameProfile UUID), validated with `NpcNameCodec.validName`, any failure falls back to vanilla's profile name. GameProfile name/UUID, selectors, saved identity, the C1 sync protocol and the `npcortex:names` channel are untouched. `refreshTabListName()` is **never** called (it would broadcast an NPC's info to clients that do not track it). Tab visibility is unchanged: NPCs stay listed (`C1PlayerAvatar.allowsListing()` returns false but `Entry(ServerPlayer)` hard-codes `listed=true`; hiding is a separate decision).
* Works for vanilla clients and a dedicated server too (server-side display name); the name tag above the head still uses the `npcortex:names` client channel.
* Edge cases: the first call is cached for the avatar's life (the identity exists before the avatar is created, so the name is available); a respawned avatar is a new object with a fresh cache; a name that fails validation keeps the profile name; real players never match the `instanceof` guard.

## Verdict rules (collector `rev6.0`)

| Scenario | PASS needs | Otherwise |
|---|---|---|
| p08 | one failed terminal, D1-A line for the same actor and goal with reason `LONG_NO_PROGRESS` or `LONG_OSCILLATION` | no D1-A line -> UNVERIFIED; another reason -> FAIL "scenario expectation not met" (not by itself a production defect); mismatching actor/goal or several lines -> FAIL |
| p10 | an attempt whose cancellation safety is PASS **and** D1-B recorded `long=LEG_QUIESCING` for the same actor and goal in the cancel tick | safe cancels but no quiesce evidenced in <= 5 attempts -> UNVERIFIED; no cancel issued -> PENDING; a safety FAIL in any attempt -> FAIL; > 5 attempts or two cancels in one attempt -> FAIL |
| p11 | the snapshot closest to the checkpoint (same window and rule as Rev5) | PENDING |
| p18 | one C2 COMPLETED, typed `G3 COMPLETED task=<id> durable ACK; actor fence released`, typed `taskmovestatus` (`E3 G2=COMPLETED ... G3 durable terminal recorded`), database row in `cplus_e3_g3_terminals` for the same task | PENDING naming exactly what is missing; an answer for another task -> FAIL |

`summary.json` / `REPORT.md` carry `status_dimensions` (functional navigation, cancellation safety and quiescing coverage, long-goal failure reason, recovery, E3 finalization, performance = `MEASURED_NOT_ACCEPTED`, owner gate = `SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT`, milestone = not accepted by the collector). They are never collapsed into one PASS.

Performance: records whose max is > 1000 ms or whose mean is > 3x p95 are flagged `first_sample_contamination: SUSPECTED`; raw mean/max are shown as `*_raw`, **not** recalculated, and only `tick_event_p95_ms` + `server_average_tick_ms` are listed as usable. G4 stays `MEASURED_NOT_ACCEPTED`.

## Offline test commands (all executed for this milestone; results in `VALIDATION-RESULTS.md` and `PENDING_TESTS.txt`)

```powershell
cd F:\AI\NPCortex\forge-mod
.\gradlew.bat build verifyRev6 verifyNpcNames verifyManagerProtocol verifyManagerCore verifyProfileRegistration verifyG2Identity verifyG3TerminalContract verifyM03 verifyM04Contract verifyTransportE verifyTransportOutbox
java -cp "build\classes\java\main;build\classes\java\test" io.github.sirp00m.npcortex.C2LongGoalBoundarySelfTest   # run from forge-mod
cd F:\AI\NPCortex\backend\tests; $env:PYTHONPATH='../src'; python -m unittest discover -s .
cd F:\AI\NPCortex; python scripts\check-dist-separation.py
powershell -File scripts\run-g5-validation.ps1
```

## Owner Windows run (NEW world, NEW isolated backend run folder)

```powershell
cd F:\AI\NPCortex
scripts\build-g5-datapack.ps1
scripts\install-g5-datapack.ps1 -WorldName <NEW_WORLD> -Update
scripts\start-g5-backend.ps1 -RunId rev6-<date>            # fresh isolated DB; prints the run folder
scripts\g5-preflight.ps1 -Phase Start -WorldName <NEW_WORLD>   # must say PASS
```
In game (superflat, cheats): `/reload`, `/function npcortex_g5:setup`, F3+P, `/function npcortex_g5:probe`. In a second console: `scripts\g5-gate.ps1 -WorldName <NEW_WORLD> -Watch`.

1. **TAB / names** (before the scenarios): `/npcortex c1 spawn Reika`, `Mika`, `Yuna` (3+ NPCs). Press TAB: the list shows `Reika`, `Mika`, `Yuna` (not `NReika_...`); name tags still show the same names. Then Save & Quit and re-open; walk away and back (tracking); Nether round trip; `/npcortex c1 despawn` + spawn again; an NPC of an older world if you have one; a second modded client on LAN if available. `/data` / selectors still use the internal name by design.
2. `/function npcortex_g5:only/p08_large_wall_no_progress` -> wait for AWAIT -> gate GO -> gate command. Expected collector outcome: PASS only if the D1-A reason is `LONG_NO_PROGRESS`/`LONG_OSCILLATION`.
3. `/function npcortex_g5:tests/cancellation` (p09, then p10 = up to **5** attempts, about 5 minutes). Do not touch anything while it runs. UNVERIFIED (quiesce never hit) is a legitimate outcome; do not re-run endlessly.
4. `/function npcortex_g5:only/p11_savequit_checkpoint` -> at CHECKPOINT: Esc > Save and Quit, re-open the same world, `/function npcortex_g5:rejoin` (runs p12 and p13).
5. `/function npcortex_g5:only/p18_regress_e3_taskmove` -> when the pack prints the gold instruction, type **in this order** and wait for each answer in chat:
   `/npcortex debug cplus taskmovefinalize yui` (expect `G3 COMPLETED task=... durable ACK; actor fence released`) then `/npcortex debug cplus taskmovestatus yui` (expect `E3 G2=COMPLETED ... G3 durable terminal recorded`). Only then the gate GO appears; press the gate command. Never finalize a task of an older run.
6. Small multi-NPC regression: `/function npcortex_g5:only/p20_multi_3_npc` (and, if time allows, `tests/multi_npc` for 1/3/5/10).
7. Collect (the game and the backend may keep running; no `-BackendLog` needed, no file-lock error):
   `scripts\collect-g5-evidence.ps1 -WorldName <NEW_WORLD>`  then `scripts\start-g5-backend.ps1 -Stop` (records the health before the shutdown).
   If you collect after the backend was stopped, `backend/health.json` says UNREACHABLE and `summary.json -> backend.health_state` says `HEALTHY_BEFORE_STOP_UNREACHABLE_AFTER_SHUTDOWN (expected)`.
8. Send the new `NPCortex-G5-TestResults-*.zip`. Do **not** run `/npcortex c2 reset`; a failed lane or an `INTERRUPTED` journal is evidence, not something to repair.

## Limits (honest)

* Whether the per-tick watch really hits the 2-3 tick quiesce is unknown until the Owner run (the Rev5 log shows 24 leg boundaries of +0.10 s to +0.15 s per 150 blocks; the watch needs the position to stand still for one tick after >= 10 moving ticks). Five attempts bound the effort; UNVERIFIED is a valid result.
* TAB rendering, name lifecycle, dedicated server and a second client are visual/Owner checks; the unit tests cover the name resolution and the contracts, not the screen.
* The MSPT/profiler evidence, the G0 baseline, repeated rounds and the M03 first-sample fix are deferred (G4 stays MEASURED_NOT_ACCEPTED).
* p18 acceptance depends on the local G3 transport (backend token) working in the Owner's world; a HOLD from `taskmovefinalize` is reported as evidence, not repaired.

## Addendum 2026-10-28: E3/G3 arrival-point alignment and the fresh p18 run

The Rev6 p18 attempt ended `C2 COMPLETED` + `G3 HOLD: position outside tolerance` (corner vs centre contract, see `PENDING_TESTS.txt`). That run and its archive stay as immutable evidence; do **not** finalize or retry its task. The production fix changed Forge Java and backend Python, so the fresh run needs a rebuilt mod and a freshly started backend.

```powershell
cd F:\AI\NPCortex
scripts\start-g5-backend.ps1 -Stop                       # stop any older backend (it still has the old verifier)
scripts\build-g5-datapack.ps1
scripts\install-g5-datapack.ps1 -WorldName <NEW_WORLD> -Update
scripts\start-g5-backend.ps1 -RunId rev6b-<date>        # fresh isolated DB
scripts\g5-preflight.ps1 -Phase Start -WorldName <NEW_WORLD>   # must say PASS
```
Start the client with `forge-mod\gradlew.bat runClient` (it recompiles the mod). In game: `/reload`, `/function npcortex_g5:setup`, `/function npcortex_g5:probe`; second console `scripts\g5-gate.ps1 -WorldName <NEW_WORLD> -Watch`. Then:

1. `/function npcortex_g5:only/p18_regress_e3_taskmove`; wait for the gold instruction.
2. As the operator, in this order: `/npcortex debug cplus taskmovefinalize yui` (expect `G3 COMPLETED task=... durable ACK; actor fence released`), then `/npcortex debug cplus taskmovestatus yui` (expect `E3 G2=COMPLETED ... G3 durable terminal recorded`). A `G3 HOLD` is evidence: report it, do not repeat the command.
3. Gate GO, then `scripts\collect-g5-evidence.ps1 -WorldName <NEW_WORLD>` and `scripts\start-g5-backend.ps1 -Stop`; send the new zip.

p18 is accepted only when the collector shows exactly one C2 COMPLETED, the g2 row COMPLETED, the typed FINALIZE/STATUS lines for that task and a `cplus_e3_g3_terminals` row with outcome COMPLETED. The fix is deterministic for every approach direction, but one gameplay run proves only that run.
