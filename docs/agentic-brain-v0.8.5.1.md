# NPCortex v0.8.5.1-alpha — navigation/pickup diagnostic fix

This small update applies **on top of v0.8.5-alpha**, which must already be installed.
It preserves the v0.8.5 Brain API and agent task data. The fix does not change the goal
planner into a hardcoded gathering sequence.

## In-game report motivating the change

On a forest hillside Yui mined 3 oak blocks, stored 0, then paused after 7/40 LLM
requests and 3/8 skill failures with `pathfinder cannot start safe approach`.
That message comes from the Java low-level `BrainSkills.approach` executor; it is
not evidence by itself of Qwen refusal or of broken Minecraft physics.

## Changes

- `BrainSkills.approach`: evaluate up to 24 candidate safe positions in ascending
  proximity; each candidate must have a real Minecraft `Path` with `canReach()`.
  This avoids choosing a single geometrically close but unpathable point.
- `BrainSkills.approachDrop`: plan to a reachable stand within item pickup range
  rather than always pathing toward an ItemEntity's (possibly floating) position.
- Share physical pickup eligibility between server `WorldActions.pickupForGather`
  and `BrainObservation`; backend validates new boolean `pickup_reachable` for
  each drop. AI can distinguish drops that can be picked up immediately vs ones
  requiring navigation. Physics and bag transfer remain server-authoritative.
- `/npcortex task status` now includes last selected skill as `chosen=` for
  troubleshooting (no AI text is used to validate completion).
- Additional backend validation test for forged pickup reach metadata.

## Limitations

`Path.canReach` and `moveTo` are vanilla pathfinding, not guarantees of motion
on all terrain. A failed route returns feedback for Qwen to select another skill
or target. Remaining caveats: hand-picked standing surfaces must be full solid
blocks, destination search is bounded to 24 path candidates, and Qwen may make
poor decisions. No successful Forge compile or in-game run is claimed here.

## Windows install

Extract update ZIP to a temporary directory. Run:

```powershell
.\scripts\install-v0851.ps1 -UpdateZip 'C:\Downloads\NPCortex_v0.8.5.1-alpha_UpdateOnly.zip' -ProjectRoot 'F:\AI\NPCortex'
cd F:\AI\NPCortex
.\scripts\test-agentic-v085.ps1
.\scripts\build-agentic-v085.ps1
```

Shut down Minecraft and backend before updating. Installer checks that the
repository version is exactly `0.8.5-alpha`, backs up replaced sources and
updates files only; it does NOT change worlds, SQLite or custom character packs.
After successful rebuild, replace the mod JAR, restart backend, and restart game.

## Suggested acceptance tests

1. On flat grass, NPC standing 2–3 blocks from exposed oak log: mine and pickup 1.
2. On a hillside with several safe walkable positions: navigate to a log not initially
   in line of sight, and observe a real path. Verify `chosen=approach(bN)`.
3. For a dropped oak log behind a small obstruction, confirm model can select
   `approach_drop(dN)` followed by `pickup`; log count in bag increases only when
   physically collected.
4. Invalid/blocked route: fail cleanly within budget, no teleport; cancel and
   reissue task only after moving to a clearer area. Test backup world, not main save.
