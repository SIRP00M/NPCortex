# G5 harness revision 5 - Character Profile aware, isolated, lane-by-lane (TEST ONLY)

Everything here is test tooling. No C2 scheduler/planner/journal/motion, E3, backend contract, SQLite schema or `MAX_ACTORS` change. **No scenario has run in Minecraft yet: all gameplay is PENDING.**

## What changed and why

| Earlier finding | Revision 5 |
|---|---|
| p20/p21: lanes `g5n02`.. failed with HTTP 400 (no Character Pack) | New NPCs are default-mode: `c1 spawn <name>` needs no pack, C2 registers `default_profile` itself. Lanes are now fresh identities `yui, g5a02..g5a10`. Lane `yui` in a fresh world is a NEW NPC with a *default* profile (not the Yui pack). |
| Old `INTERRUPTED` journal rows fenced reused lanes | A run needs a **fresh world** (preflight proves no saved NPC identities / M03 evidence) and **fresh actor ids**. The harness never resets anything: a lane with a non-terminal journal is *reported* by the preflight; resolve it by hand or use a new lane name. |
| the collector read `backend/data/npcortex.sqlite3` by default | **No default database.** Collector and preflight FAIL CLOSED unless the run folder proves isolation (below). |
| p10 cancelled after completion | p10 now walks 150 blocks and the cancel only counts at a stop still >= 25 blocks from the target (`unless entity @s[distance=..25]`). |
| "LEG_QUIESCING" was inferred from the phase `REPLANNING` | That phase also occurs in S4 replanning, so it is **not** evidence. p10 has two verdicts (below). |
| a missing profile before the first goal looked like a failure | A default-mode lane has no profile row until its first C2 goal registers it: `NOT_REGISTERED_YET` is expected. Only a logged `Character Profile NOT registered` is `REGISTRATION_FAILED`. |

## Run procedure (PowerShell is the orchestrator; the data pack only does supported in-game steps)

1. **Fresh disposable world** (superflat, cheats on, nothing else in it). `powershell -File scripts\install-g5-datapack.ps1 -WorldName <W> -Update` (rebuild first with `scripts\build-g5-datapack.ps1` if you changed the generator).
2. **Isolated backend**: `powershell -File scripts\start-g5-backend.ps1 [-RunId <id>] [-Port 8765]`. It creates `g5-results\run-<id>\` with `G5-ISOLATED.marker.json`, starts the backend with `NPCORTEX_DB_PATH` = a NEW `backend.sqlite3` inside that folder, records pid/port in `backend-run.json` and writes `g5-results\current-run.json`. It refuses a busy port (a live backend is never reused), an existing run folder and a shell with `NPCORTEX_ALLOW_SCHEMA_V5` set. Stop it later with `start-g5-backend.ps1 -Stop`. The Forge mod talks to `http://127.0.0.1:8765` by default; use another `-Port` only if you also change the mod's `backend.url`.
3. **Preflight**: `powershell -File scripts\g5-preflight.ps1 -Phase Start -WorldName <W>` (exit 0 PASS / 1 FAIL, writes `preflight.json`). PASS means the prerequisites hold, **not** a test verdict. It checks: marker valid; database inside the run folder, not `backend/data`, no junction/symlink, readable, v5, `integrity_check` ok; the backend on the port is the recorded process (port owner = pid or its child) and answers `/health`; a local credential exists (value never shown); database empty; world has no `npcortex_c1_p0.dat` and no M03 evidence; installed pack identical to the repository pack; lanes <= 10. Between scenario groups run `-Phase Before` (only G5 lanes in the database; no lane with a non-terminal journal).
4. In game: `/reload`, `/function npcortex_g5:setup`, F3+P, `/function npcortex_g5:probe`, then `/function npcortex_g5:start` and `scripts\g5-gate.ps1 -WorldName <W> -Watch` as before. **The gate STOPs unless the preflight passed.** A GO is logged as `SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT`.
5. Collect: `powershell -File scripts\collect-g5-evidence.ps1 -WorldName <W>` (uses `current-run.json`; or pass `-G5RunDir`). Refused unless isolation + a passing preflight of the same run id are recorded. Pass `-BackendLog g5-results\run-<id>\backend.err.log` to include the backend console.

## Multi-NPC verdict (p19-p22): every lane on its own

For each lane the collector builds a row: C1 identity (M03 `profile_id` = GameProfile id), actor id (from the logged C2 lines), **profile binding** (current profile row of the isolated database whose display name is the lane name), Goal id, Action id, journal states, terminal line(s), distance to its own target. A lane is PASS only with: profile BOUND, exactly one goal, exactly one `COMPLETED` terminal line, at its target. The scenario is PASS only if all lanes PASS, every lane has its own goal / actor / GameProfile id, and the performance record exists. One `REGISTRATION_FAILED` lane FAILs the scenario as a prerequisite failure (other lanes stay independently judged and listed). Lanes without evidence keep the scenario PENDING. `Lane-by-lane` table is in `REPORT.md` and `summary.json` (`lane_table`).

## p10: two verdicts, never mixed

* **Cancellation safety** (needs only the journal + snapshots): the cancel hit an *active* goal (not after completion), no COMPLETED result, the NPC stopped and stayed stopped (>= 2 snapshots >= 6 s later, spread <= 0.75), the journal ends `CANCELLED`. Otherwise FAIL / PENDING with the reason (`CANCEL_PENDING`/`INTERRUPTED` = reconciliation not evidenced = PENDING).
* **LEG_QUIESCING race coverage**: only `COVERED` when `c2 status yui` - issued by the pack in the cancel tick, immediately before `c2 cancel` - was echoed to the client chat log (Render thread `[CHAT] yui C2=... long=LEG_QUIESCING`). The sample must be the ONLY `c2 status` echo within -0.5 s..+1.5 s of the cancel note (two samples = `UNVERIFIED`, we cannot tell which belongs to the cancel tick). A sample with another `long=` is `NOT_COVERED`; no sample at all is `UNVERIFIED`. A goal that completed at/before the cancel instant makes the cancel PENDING (too late), not a safety FAIL. The scenario status is PASS only for safety PASS **and** COVERED; safety PASS with anything else is `UNVERIFIED`. No production instrumentation was added: `c2 status` is an existing command. Whether your client writes command feedback to `latest.log` is itself unverified; if it does not, p10 will honestly stay UNVERIFIED.

## Offline checks (none of this is gameplay evidence)

`powershell -File scripts\run-g5-validation.ps1` runs: pack build, static validator (8315+ checks), 15 validator mutations, controller simulation (228 assertions incl. status sample + far-cancel + lane names), collector tests (430 checks incl. lane/profile binding, registration failure with and without a journal row, p10 split verdicts, duplicate status samples, stale performance records, isolation fixtures), gate tests (130), installer, and `test-g5-isolation.ps1` (53 checks incl. ancestor junctions, odd path spellings, run folder under backend/, one real isolated backend process on a free port, temp folders only).

## Still unverified / limits

LongState at a cancel unless the chat sample is logged; FIFO wait distribution (not logged); real MSPT; whether `p22` (exactly `MAX_ACTORS=10` concurrent claims) behaves; dedicated-server runs.

## Owner acceptance checklist (Windows, real Minecraft) - everything below is PENDING until you report it

**A. Visible names** (disposable single-player world, cheats on, `/npcortex c1 spawn Reika`, `Mika`, `g5a02`, `Yui`):
1. Each NPC's name tag shows exactly its C1 name (`Reika`, not `NReika_237778`); your own name and other players are unchanged.
2. Several NPCs at once each show their own name. Existing worlds: NPCs saved by older versions show their names too.
3. Save & Quit, re-open: names are correct again (also for NPCs that were far away and are tracked again when you walk back).
4. Nether/End and back (`/execute in minecraft:the_nether run tp @s 0 80 0`, then back): names stay correct.
5. `/npcortex c1 despawn Reika` then `spawn Reika`: the tag is correct again. `@e[type=player,tag=...]` selectors and the G5 scenarios still find the NPCs (they use tags, not names).
6. Dedicated server (optional): join as a second player; tags are correct; a non-mod client cannot join (unchanged).

**B. G5 on a fresh world** (follow "Run procedure" above; use a NEW world name and a NEW run folder):
1. `start-g5-backend.ps1` prints the run folder; `g5-preflight.ps1 -Phase Start -WorldName <W>` -> `G5 PREFLIGHT PASS`.
2. p00 probe, then `/function npcortex_g5:start` and `g5-gate.ps1 -Watch`; at every GO press the gate command. A GO is only progression.
3. p10: after it ends, open `summary.json` -> scenario `p10_cancel_quiescing` -> `cancellation_safety` and `leg_quiescing_coverage` (COVERED / NOT_COVERED / UNVERIFIED are all legitimate outcomes; do not "fix" an UNVERIFIED).
4. p19 (1 NPC), p20 (3), p21 (5), p22 (10): in `REPORT.md` the lane table must show every lane `PASS`, registration `BOUND (default rev 1)`, its own goal/actor/GameProfile id. Run `g5-preflight.ps1 -Phase Before` between groups if you re-run any.
5. Send me the `NPCortex-G5-TestResults-*.zip`. Do NOT run `/npcortex c2 reset` for a failed lane: report it (a lane with `REGISTRATION_FAILED` or an `INTERRUPTED` journal is evidence, not something to repair).
