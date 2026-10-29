# X0 one-NPC vanilla chunk-tracking experiment: evidence report (2026-10-29)

TEST-ONLY. No production C1/C2/PAP change, no live world or DB, no Git/GitHub/Graphify, no ZIPs. Statuses: MEASURED / BLOCKED / NOT TESTED. Raw data: `docs/pap/x0-evidence/<run>/` (`*.jsonl` = one `X0DATA` line per measurement, `*.region-oracle.json` = independent region-file count, `*.gradle.log`).

## Harness (what the numbers are and are not)
- Forge `runGameTestServer` (a headless `MinecraftServer`, NOT a `DedicatedServer`), namespace `npcortexchunkx`, fresh world per case (`scripts/pap/x0/run-x0.ps1`; wipes only `forge-mod/run-gametest/world`), no human player, heap capped at 3 GB, wall timeout, new-chunk cap 2500, free-disk precheck, before/after signature of `forge-mod/run/saves`, `run/config`, `backend/data` (unchanged in every run).
- The harness has view/simulation distance 0; the test sets both to **10** (`X0_VIEW`/`X0_SIM`) to emulate a default server. All per-NPC figures below depend on that setting.
- Test code: `forge-mod/src/test/java/.../ChunkTrackingExperiment.java` (+ `data/npcortexchunkx/structures/arena.nbt`). The hook loaded (`hook_loaded` line in every case, `smoke` case first).
- The headless server ticks faster than 20 TPS (about 100 ticks per wall second here) while chunk generation runs on worker threads in real time: **wall time is the reliable generation figure, tick counts are not.**
- Terrain is natural overworld generation, 640 blocks from the world spawn (spawn chunks and the GameTest arena are out of range). One chunk is force-loaded to bootstrap the spawn, then released.
- The stand-in model server (`x0_stub_model.py`) only echoes the goal's own `move_to`; **no Qwen/Ollama was contacted** and no claim is made about model behaviour.

## Phase 1: chunk mechanics (instrumentation, not gameplay)
| ID | Result | Status |
|---|---|---|
| Baseline, no avatar | after the bootstrap chunk is released: 25 loaded, then 0 within 1 tick; stays 0 (600 ticks) | MEASURED |
| Spawned avatar, **never moved, hook OFF** (H1) | the avatar immediately holds a full vanilla player ring: 46 chunks at once, then **625 loaded / 441 FULL+entity-ticking** after about 7.7 s wall (about 1700 ticks), about **600 new FULL chunks generated**, 462 chunk-watch events. **Confirms the audit hypothesis: every spawned/restored NPC already holds this ring today.** | MEASURED |
| Despawn (ring complete) | 625 loaded at +1 tick, 0 at +20 ticks, 0 through +1200 ticks; one-tick stall up to **307-333 ms** when 625+ chunks release at once | MEASURED |
| Despawn mid-generation (+100 ticks) | no new FULL chunk after despawn; 0 loaded by +100 ticks; partial chunks stay on disk | MEASURED |
| Hook ON, 24 controlled section crossings (+384 blocks), readiness gate: next chunk FULL and a lead window of 4 chunks FULL+ticking | every crossing was ready (0-74 ticks wait, under 0.5 s wall); ring follows: 441 chunks within 10 again after the last crossing (about 10 s wall); trailing chunks unload (`events_unload` rises by 25 per step); `move()` call itself 0.1-1.0 ms | MEASURED |
| Hook ON, crossings every about 5 ticks (outrunning generation) | the ring lags (441 to 248 within 10 at arrival) and recovers when the avatar stops | MEASURED |
| **Hook OFF control**, same position changes without `move()` | ring stays at the spawn point (625 retained, 0 unloads); after 7 crossings the readiness gate blocks (`NOT_READY_AFTER_1500_TICKS`): without the gate the avatar would step into unloaded terrain | MEASURED |
| Readiness states | FULL is not entity-ticking: an item 12 chunks away (loaded, not ticking) did not age; items 2/5/8/10 chunks away aged 100 in 100 ticks | MEASURED |
| Mob spawning, eligibility first | avatar listed in the player map (1 player within 9 chunks), not spectator, `anyPlayerCloseEnoughForSpawning` true; at night 70-74 monsters within 128 blocks after 1200 ticks, with the hook ON **and** OFF (creature counts differ because worldgen places animals) | MEASURED (observed, not a definitive negative control) |
| Packet creation | avatar chunk-watch events 462 (ring) to 972 (24 crossings); same-constructor microbenchmark 21.6-23.2 us per `ClientboundLevelChunkWithLightPacket` (64 chunks), about 20 ms CPU over the whole run. Not a count of packets sent; no counter can be attached without touching vanilla networking | MEASURED (indirect) |
| MSPT | ring held, hook ON: p50 about 2.4 ms, p95 5.3 ms, max 27.6 ms; spawn window with about 130 mobs: p50 2.1 ms; nothing loaded: p50 0.1-0.3 ms | MEASURED (1 NPC) |
| Region-file oracle (fresh ring) | +3207 chunk entries and +20.8 MB over the no-avatar world, of which +1129 FULL: the ring pre-generates more FULL chunks than it keeps loaded, and each FULL chunk comes with about 1.8 neighbour/proto entries | MEASURED (run-to-run baseline differs by about 300 entries) |
| Restart | identity-active avatar is restored by `C1P0Runtime.onServerStarted` at its saved position and rebuilds the full ring in about 4 s wall; no ring exists before restore; despawn afterwards releases it | MEASURED |
| Leak check | after every despawn: 0 loaded, `events_unload` equals loads; protected state unchanged | MEASURED |

## Phase 2: C2 integration
- Accepted suites, hook off (the experiment class is a separate namespace): `gradlew build` all self-tests, native GameTests 28/28, native real-backend 5/5 (`-Port 18770`), PAP real GameTests 14/14, DB audits LEDGER OK. **PASS (executed).**
- Real C2 walks (navigate / navigateLong, real backend, stand-in model), on a flat stone strip built in the disposable world (a first run on natural terrain was rejected by the planner with `INVALID_START` / `LONG_NO_CANDIDATE`; that is a terrain/planner finding, not chunk behaviour; kept in `x0-c2-hook1`):
  - **Hook ON:** 5, 20, 40, 80, 80, 80 blocks (**305 blocks**, beyond the original 160-block ring) all `COMPLETED verified=true`, final error 0.01-0.09 blocks, **0 ticks in an unloaded chunk**, loaded-within-10 never below 441.
  - **Hook OFF:** 5, 20, 40, 80 completed inside the stale ring (loaded-within-10 fell to 294); the next two 80-block legs were skipped because the target chunk was not loaded (C2 itself also refuses an unloaded target).
  - No 500-block, fresh-terrain or unprepared-terrain expedition PASS is claimed. Natural-terrain C2 walking is NOT TESTED beyond the first rejected attempt.

## Not tested / blocked
- 1/3/5/10 NPCs (out of this approval), real `DedicatedServer`, integrated server with a human, zero-player dedicated mode, real client or network cost, MSPT at default mob/entity load, other view/simulation distances, modded worldgen, bounded-quota disk-full fault (BLOCKED: no isolated quota without installing anything), Forge-ticket (option A) window benchmark (only a 1-chunk vanilla forceload proxy was measured: 25 loaded, 1 entity-ticking).

## Reading for the options (Owner and GPT-6 decide; no production budget is selected here)
- **B works as hypothesised without PlayerList:** one `ServerChunkCache.move(avatar)` per section change gives a following player-style ring, generation, entity ticking, spawn eligibility and release on despawn.
- **Cost is fixed per registered NPC and set by the server-wide view/simulation distance** (here 625 loaded, 441 entity-ticking, about 600 generated FULL chunks, about 20 MB of region data when fresh, MSPT p50 about 2.4 ms). Not tunable per NPC; the only measured way to release it is despawn (and that releases in one 300 ms tick).
- **Levers that exist and were exercised:** when `move()` is called (lead gating kept 4 chunks ahead of a paced walker), and despawn. Stopping C2 does not release anything (hook OFF control).
- **Option C (B plus admission) is supported by the evidence; option A was not benchmarked.** The one-chunk forceload proxy suggests a much smaller footprint per window (25 loaded, 1 ticking) but gives no walker-following evidence.
