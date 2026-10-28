# NPCortex — C2-P1-D Reliable Transactional Dynamic Replanning

Windows Forge 1.20.1 / Java 17 — **Implementation candidate, not yet gameplay-certified**.

## Before launching

1. Back up the last user-confirmed C2-P1-C Fix #1 repository and world.
2. Use PowerShell at `C:\path\to\NPCortex` with Eclipse Temurin **17**.
3. Run `./scripts/test-c2-p1d.ps1` and `./scripts/dev-forge.ps1 -Task build`; both must pass before gameplay.
4. Start `./scripts/dev-backend.ps1`, then in a different terminal `./scripts/dev-forge.ps1 -Task runClient`. Confirm Ollama/Qwen health.
5. Use a flat, already loaded area **within radius 6 blocks** of Yui. Current P1-D handles only B1 cardinal, flat WALK edges. NPCs must be grounded. No new movement/terrain/portal/multi-chunk capability is included.

## Minecraft Commands (one at a time)

    /npcortex c1 spawn yui
    /npcortex c1 info yui
    /npcortex c2 navhier yui <X> <FEET_Y> <Z>
    /npcortex c2 navigate yui <X> <FEET_Y> <Z>
    /npcortex c2 status yui

`<X> <FEET_Y> <Z>` are the actual coordinates of a reachable foot-position block; don't paste angle brackets. The target must be in the initially scanned area. Use `/npcortex c1 info yui` to choose appropriate test positions. The `navhier` command remains read-only. `/npcortex c2 navigate` creates a single C0 Goal/Action even if the route gets replaced.

## Mandatory acceptance scenarios

- [ ] **Baseline regression:** empty corridor → Yui walks to goal. `COMPLETED`, `verified=true`.
- [ ] **Pre-existing wall:** wall already there, safe detour exists → Yui navigates around, as in P1-C.
- [ ] **Mid-route obstruction, detour exists:** after navigation starts, place a 2-block-high dirt wall in the UPCOMING route within the loaded scan area, leaving a safe detour. Inspect `/npcortex c2 status yui`: may show `REPLANNING`, `generation=1`, `replans=1` before `MOVING`. Yui must use a NEW route, and finish only if within final goal tolerance. **No Teleport**. If route executes too quickly to insert a wall, use a winding test path or a second player/helper to place the wall; don't claim pass without actually changing the world during motion.
- [ ] **Mid-route obstruction, no detour:** close ALL reachable paths to a still-safe goal. Three bounded replan attempts, spaced by at least 20 server ticks; eventual `FAILED`, never fake `COMPLETED`. Some scenarios return `UNSAFE_TARGET` immediately instead.
- [ ] **Open a route during retry:** when the controller is `REPLANNING`, remove a blocking wall before the final attempt; expect path replacement and movement to resume. If too hard to time manually, leave NOT TESTED.
- [ ] **Cancel while replanning:** issue `/npcortex c2 cancel yui` during `REPLANNING`, then `/npcortex c2 status yui`; expect C0 terminal reconciliation, no old route re-execution, and a new `/npcortex c2 navigate` accepted **without `/npcortex c2 reset yui`**.
- [ ] **Multiple world edits / loop bound:** fast alternating place/break should stop after at most 3 replacement attempts for the same Goal, no unbounded search/spam.
- [ ] **Safety:** NPC death/despawn, unloaded chunk, or C1 foreign ticket while replanning must not create an additional movement owner. These need controlled scenarios/logs and may remain pending.
- [ ] **Restart:** Save/Quit during `REPLANNING` must not auto-execute old route on rejoin; journal remains fail-closed. Do not manually reset unless deliberately testing legacy recovery.
- [ ] **Regression:** C1 combat knockback/death presentation; pre-existing `cancel -> new navigate without reset` still works.

## Performance evidence (not replaced by pure Java microbenchmarks)

For 1 / 3 / 5 / 10 NPCs, record scenario, `status` / generation / attempts, per-route elapsed time, Forge F3 Integrated Server MSPT (baseline, sustained, and peak), and any tick spikes. P1-D limits global B3 replanning to ONE planning attempt per server END tick, and at most 3 replans per Goal. This guard is not a guarantee of any particular MSPT; on your desktop 5 NPC is a reasonable first smoke test before increasing to 10.

## What is NOT an acceptance pass

- A pre-existing wall does not prove mid-route dynamic replanning.
- A failed motion is not enough to prove a successful detour.
- `PATH_FOUND` from `navhier` is read-only and does not prove waypoint execution.
- `STATUS=FAILED` on an unsafe target is not a test of `NO_PATH` for a reachable safe target.
- Java isolated tests with stubbed Forge types do not prove a real Forge build.
- P1-A unloaded chunk and partial slab/stair per-coordinate gameplay remain NOT YET TESTED until explicit evidence.
