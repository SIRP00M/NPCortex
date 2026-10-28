# NPCortex Roadmap #2 - G5 test harness, revision 4 "probe v2 + owner gate" (TEST ONLY)

> **Revision 6 (D1 diagnostics, p10 up to 5 attempts with a per-tick trigger, p18 typed finalize, collector hardening `rev6.0`): see [REVISION-6.md](REVISION-6.md) - it supersedes the p08/p10/p11/p18 rules and the log-collection notes of the older revisions.**
> **Revision 5 (Character Profile aware, isolated run, lane-by-lane, p10 split verdicts): see [REVISION-5.md](REVISION-5.md) - it supersedes the lane names (`g5a02..`), the default database and the p10 rules described below.**

A Minecraft 1.20.1 data pack drives the scenarios; M03 snapshots written by the existing production bridge are the **independent evidence**; PowerShell reads what the
pack cannot (`g5-gate.ps1` decides when it is safe to progress, `collect-g5-evidence.ps1` decides every verdict). No production source, backend, prompt, E3 or journal code is touched.
**Nothing in this package has run in Minecraft yet: every gameplay scenario is PENDING.**

## Why revision 4 (actual gameplay evidence)
The first real p00 (revision 3) printed `players_visible=1`, `selector=NONE`, `tag=FAIL`, `entity_data=FAIL`, `mode=FALLBACK`, while the production log shows
`ServerLevel.addNewPlayer returned yui ... pos=(2.0,-60.0,2.0)`, `CLIENT_ENTITY_PRESENT`, and two M03 snapshots place `yui` at (2.0, 2.0). So the NPC exists; the selector missed it.

| Finding | Evidence | Change |
|---|---|---|
| `@e[type=player,...]` cannot see the C1 NPC | decompiled 1.20.1: a positive `type=player` sets `includesEntities=false`, `findPlayers` then reads `PlayerList` (the NPC is deliberately not in it, `C1P0Runtime.java:31`) unless one of `x,y,z,dx,dy,dz` is present, which switches to `ServerLevel.players` (it contains the NPC). The same decompile shows `x/y/z` move the origin of `distance=`. | every `@e` selector carries a bounded search box; distance checks use `as <npc> positioned <p> if entity @s[distance=..r]` |
| p00 printed `tag=FAIL`/`entity_data=FAIL` although it never attempted them | adoption was gated on `selector=OK` | probe v2: independent capabilities, `NOT_ATTEMPTED` is distinct from `FAIL` |
| `say` expanded the selector text in a probe message (`...visible to Dev`) | real log | validator forbids `@` inside `say` |
| rev 3 fallback advanced scenarios on a fixed timer | review | owner gate: no scenario progresses on a timer |

## Search box (test harness only)
`x=-250,y=-64,z=-150,dx=500,dy=20,dz=300` on every `@e` selector: covers the 150-block lane, detours around the 161-wide wall and the 10 multi-NPC lanes with margin.
It does not touch C2 radius, LongGoal limits, chunk loading (no `forceload`) or motion ownership. The preferred selector kind (`@e[type=player,box]`) scans only `ServerLevel.players`
(a handful of entries); the typeless kind scans entity sections inside the box and is used only if the player kind fails. Selector overhead was **not measured** (assessed from the code path only).

## p00 probe v2 (`/function npcortex_g5:probe`, about 8 s)
Seven independent capabilities, each printed as `PROBE <name>=PASS|FAIL|NOT_ATTEMPTED`:
`playerlist_control` (`@a` sees only the operator; informational), `world_limited_player`, `typeless_entity`, `tag_v1`, `tag_v2` (tag add + verify + remove, only through a selector kind that itself passed),
`entity_data` (`data get entity Pos`), `position` (agrees with block 2,2). Then `selector_chosen=world_limited_player|typeless_entity|NONE` and `mode=ADAPTIVE|OWNER_GATED`.
Safety: a candidate is tagged only when exactly one untagged non-operator candidate exists; the operator is excluded by tag; another player/NPC makes the probe AMBIGUOUS and nothing is tagged.

## Modes and progression (no timer-only dispatch)
* **ADAPTIVE** (entity data and position verified): the pack polls the NPC with the chosen selector; early REACHED, cancel-while-MOVING, abort when the NPC is lost. REACHED needs the NPC within 1.6 blocks **and stationary for 3 polls**.
  It advances by itself only on that in-game evidence (REACHED + settle, STOPPED_AFTER_CANCEL, NO_MOVEMENT_AFTER_REJOIN, ALL_REACHED) or when nothing was dispatched (BLOCKED).
  `TIMEOUT`, `STALLED`, `NEVER_MOVED`, `CANCEL_NOT_TRIGGERED` enter **AWAIT**. `/function npcortex_g5:strict_on` makes every scenario await the gate.
* **OWNER_GATED** (no selector kind works): the same commands run at fixed ticks; after the observation window every scenario enters **AWAIT**. Nothing else is read in-game.
* **AWAIT**: the pack stops, prints `[G5] AWAIT <id>`, keeps one M03 snapshot per 5 s and waits. It never dispatches, spawns, despawns or advances.
* **Gate**: `scripts\g5-gate.ps1 -WorldName <w> -Watch` reads M03 snapshots + logged C2 terminal lines and prints **GO** only when the actor is terminal (journal COMPLETED/FAILED/CANCELLED/INTERRUPTED, and for COMPLETED/FAILED the logged `result=` line) **and** stationary
  (3 snapshots over at least 7 s, spread <= 0.5). It copies `/function npcortex_g5:gate/<id>` to the clipboard. The pack refuses the command unless that scenario is the current phase **and** awaiting (`#settle=-2`).
  `p11`: GO means the NPC is still mid-goal (the gate then starts the Save&Quit checkpoint; `MISSED` = it already finished, re-run `only/p11_savequit_checkpoint`). `p12`: GO needs 15 s of no movement after the rejoin.
  Stuck beyond 8 game-minutes: decision `HUNG`; the explicit safe checkpoint is `/function npcortex_g5:cancel_current` (cancels the G5 NPCs' goals), then the script judges again. A stale/repeated gate command is refused.
* Press **F3+P** once so the game does not pause when the window loses focus; otherwise the script reports stale snapshots.

Stale protection: `setup` clears the scheduled loop and all run state; only the last `[G5] START` is analysed by script and collector; `rejoin` needs a pending checkpoint; gate and `cancel_current` are refused outside AWAIT.
Exactly-once and correlation (scenario / goal id / action id / journal states / terminal lines / gate state) are unchanged in the collector. A scenario that ended without a recorded gate release is a FAIL (timer-only progression);
one still awaiting is PENDING; a release without a GO recorded in `g5-results/g5-gate-log.jsonl` is reported in the notes.

## Minimal Owner instructions
1. World `NPCortex_G5_Run1` (superflat, cheats on, no other player/NPC). `powershell -ExecutionPolicy Bypass -File scripts\install-g5-datapack.ps1 -WorldName NPCortex_G5_Run1 -Update`
2. Backend + client up. In game: `/reload`, `/function npcortex_g5:setup`, press **F3+P**, then **`/function npcortex_g5:probe`**. Send the 10 `[G5] PROBE ...` chat lines (or just `latest.log`).
3. `/function npcortex_g5:start`. In a PowerShell window: `powershell -ExecutionPolicy Bypass -File scripts\g5-gate.ps1 -WorldName NPCortex_G5_Run1 -Watch`.
   ADAPTIVE: you only act when the script prints GO (T, Ctrl+V, Enter) after an `AWAIT`. OWNER_GATED: the same for every scenario (about 21 times).
4. At `CHECKPOINT`: Esc > Save and Quit to Title, re-open the world, `/function npcortex_g5:rejoin`. At `DONE`: `powershell -File scripts\collect-g5-evidence.ps1 -WorldName NPCortex_G5_Run1`, send `g5-results\NPCortex-G5-TestResults-*.zip`.

## Scenario coverage matrix
Verdict rules are those of the collector (unchanged in substance); AUTO = executed by the pack, judged from independent evidence; SEMI-AUTO = owner checkpoint or best-effort timing. Gate = what g5-gate.ps1 requires before GO.
| Id | Scenario | Class | Verdict rule | Gate | Limit |
|---|---|---|---|---|---|
| p00 | probe v2 | AUTO | mode decision logged + M03 snapshot at (2,2); ADAPTIVE needs entity data, position and a chosen selector | none | first real evidence of selector behaviour |
| p02-p06 | navlong 20/40/80/150, diag 60 | AUTO | final M03 position <= 1.6 from target, one goal/action id, one `result=COMPLETED` | terminal + still | LongState not observable |
| p07 | small wall detour | AUTO | as above | as above | |
| p08 | 161-wide wall | AUTO | NPC short of target, one `result=FAILED` | FAILED + still | failure reason not logged -> UNVERIFIED |
| p09 | cancel while walking | AUTO | state MOVING in the cancel-tick snapshot, >=2 post-cancel snapshots, spread <= 0.75, no COMPLETED | CANCELLED + still | gated mode cancels at a fixed poll; wrong state -> PENDING |
| p10 | cancel during quiescence | SEMI-AUTO | state REPLANNING at cancel | as p09 | probabilistic; re-run after 3 min |
| p11 | Save&Quit checkpoint | SEMI-AUTO | snapshot at the checkpoint shows an active goal | mid-goal | the quit is MANUAL; walks 150 blocks |
| p12 | rejoin: no replay | SEMI-AUTO | >=2 post-rejoin snapshots, spread <= 0.75, fenced/terminal journal | 15 s still | dedicated-server shutdown UNVERIFIED |
| p13-p15 | navlong after recovery, `c2 navigate`, `c2 move` | AUTO | as p02 | terminal + still | |
| p16-p17 | follow start / stop | AUTO | within 6 blocks of the operator / stationary | still | |
| p18 | E3 task-move | SEMI-AUTO | displaced and stopped -> PENDING | displaced + still | acceptance needs `taskmovefinalize` + DB review |
| p19-p22 | 1/3/5/10 NPC lanes | AUTO | every NPC <= 1.6 from its lane target, N distinct goals COMPLETED once, M03 performance record | all lanes terminal + still + performance record | no G0 baseline |

## Known limitations / pending
* Real Minecraft behaviour is UNVERIFIED: whether the boxed `@e[type=player]` / typeless selectors, `tag` and `data get entity` work on the C1 ServerPlayer (this is exactly what p00 v2 tests), function output/permission behaviour, snapshot cadence under load (queue of 32; `HOLD: evidence queue full` shows in chat and the collector then reports PENDING).
* Selector overhead (MSPT) not measured. The gate script's terminal rule relies on journal states `COMPLETED/FAILED/CANCELLED/INTERRUPTED` seen in M03 snapshots; any other state is treated as active.
* Not observable without a production log line: LongGoal failure reason, LongState/leg counts, FIFO waits. G0/G4 baselines, dedicated-server shutdown: PENDING.
* An Owner can release the gate by hand without a GO; the collector records that. Offline checks (`VALIDATION-RESULTS.md`) prove harness logic on a mock world, synthetic fixtures and real log excerpts only.
