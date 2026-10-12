# NPCortex v0.8.5.5-alpha — Ground Pickup & Navigation Affordances

## Observed real-game failure from v0.8.5.4

A Qwen decision mined one oak log but `stored=0/1`. Subsequently it chose `approach(b1)` and Forge reported no traversable route to one candidate standing position. The no-progress watchdog paused at `stalls=5/5`. Six observed backend requests returned HTTP 200, so this run is not an HTTP failure. **We do not know from those screenshots whether the spawned log remains on the ground or the player picked it up.**

## Improvements (no fixed task sequence)

1. **Ground navigation** in `BrainSkills.moveToReachableStand` uses empty collision shapes and dry feet/head instead of `isAir()`; non-colliding tall grass and flowers no longer invalidate safe walking spots. Tests a bounded 11×11×7 region around a server-observed target, capped at 24 real pathfinding candidates. Standing positions must be within the task home range, with a sturdy support face, and the interaction must be visible. No teleport/flight or unrestricted world edits.
2. **Physical item pickup** is within 1.5 blocks (distance squared ≤2.25), regardless of creative reach. Both `WorldActions.reachableGatherDrop()` observation and pickup execution use the same server-owned distance predicate. `approach_drop` searches for nearby *pickup-range* walking spots, not only ordinary block interaction-range spots. This is closer to walking to the item, not telekinesis.
3. **Tool affordance masks in backend**: once `mined >= goal_count`, Qwen is offered no further `mine`, `approach`, `clear_leaf` or `climb` skills. It can still select `approach_drop`, `pickup`, `wait`, `pause` and, where valid, `descend`/`finish`. A `pickup` tool is offered only if the current world snapshot contains a physically reachable drop; `approach_drop` only if at least one observed drop needs navigation. Invalid choices fail closed. This is **not a hardcoded command sequence**; Qwen still picks the available skill, while server verifies its preconditions and effects. The server never counts a mined block as an inventory item.
4. **Diagnostics**: `/npcortex task status` includes `observedDrops` and `pickupReady` from the latest frozen server snapshot for the model request. `stored` continues to use the actual NPC bag.

## Upgrade

Patch **v0.8.5.4-alpha** with `scripts/install-v0855.ps1 -UpdateZip <path> -ProjectRoot F:\AI\NPCortex` after closing Minecraft and backend. Installer does not touch SQLite, saves, custom packs or `.env`; copies replaced files to a timestamped backup. Re-run `scripts/test-agentic-v085.ps1`, `scripts/build-agentic-v085.ps1` and open `scripts/dev-forge.ps1 -Task runClient`; start `scripts/dev-backend.ps1` separately. Both Forge-linked Java and Python changed.

## Small and reproducible in-game test

- Use a *copy* of the world with flat terrain and exactly one accessible oak log. Place NPC next to it, player at least 4 blocks from the log so the player does not auto-pick up the drop. Disable competing tasks.
- `/npcortex task cancel` (if paused), then `/npcortex task gather minecraft:oak_log 1`, then `/npcortex task status` after a few seconds.
- Expected sequence is **NOT guaranteed**; check that Qwen selects feasible actions independently. Verify `mined=1`, then `observedDrops>=1`, `pickupReady` transitions with walking, and finally `stored=1/1`. Completion must be inventory-verified. If `observedDrops=0` after mining, inspect whether the player picked it up or game drop spawning was suppressed; do not alter planning policy to mask missing world evidence.
- Repeat on sloped grassy terrain to check navigation. If no path, observe `last=` and `chosen=` and take backend/Forge logs.

## Test limits

- Python backend unit/API tests and standalone pure Java policy self-test can run in a non-Forge environment. These do **not** compile Forge-specific sources or prove walkability in Minecraft.
- Forge build / Qwen live request / real in-game tests must be run locally; do not claim they passed without actual execution.
- Pickup uses a guarded 1.5-block radius as a prototype approximation; live collision pickup tuning is pending.
