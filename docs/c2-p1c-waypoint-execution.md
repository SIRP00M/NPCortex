# NPCortex C2-P1-C — Transactional Waypoint Execution (Architecture C)

## Scope, intent and ownership

Implements user-approved P1-C only. `/npcortex c2 navigate <name> <x> <feetY> <z>` (operator-only via existing command root) computes a bounded B3 hierarchical path from the NPC's feet block to the integer target feet block. Unlike `navhier`/`navplan`, `navigate` attempts **actual C1 Vanilla walking**; it requires a running Ollama/Qwen C0 Backend. The `navplan`/`navhier` commands remain read-only.

- C0 receives **one** `move_to` goal/action for the authorized final coordinate; Qwen does NOT drive every intermediate waypoint.
- Route: immutable ordered B3 nodes; **1..64**, flat cardinal WALK only, scan radius <=6 and B3 bounded search budget 12000.
- Each segment uses only **C1P1MotionRuntime.turn + moveC2Precision / Vanilla `Player.travel`**; no teleport, second physics owner or PathfinderMob.
- The C1 session has a transient UUID movement ticket; a `COMPLETE` receipt from an old or unrelated session cannot advance the P1-C route.
- After each receipt the Forge server checks the observed position near the correct waypoint (0.35 blocks); immediately before the next segment, it resamples loaded-world Graph/Collision, validates the exact edge and checks current support/position. New obstructions FAIL closed; **no autonomous replanning**.
- Distinguish final Goal Tolerance (0.35 blocks) from intermediate Waypoint Tolerance (0.35 blocks). C0 performs the final verification on a post-movement observation; both local and C0 checks must pass.
- Ownership: one active C2 goal per actor; cancellation interrupts, C1 movement is stopped if owned. The actor may not be dead, despawned, in another dimension or undergoing an active unrelated C1 movement. C1 Combat impulse has priority; C2 does not write velocities.
- On server shutdown, unresolved running journal entries become INTERRUPTED or remain uncertain on an unclean crash, and they **are never auto-replayed**. Manual `/npcortex c2 reset` is allowed only when no active C2/C1 movement, **after** operator examination of the old C0 goal; it is not automatic cross-runtime exactly-once reconciliation. `SavedData` is not fsync/durable transactional storage.
- One Route hard limit 2400 server ticks; per segment watchdog 380 ticks. Fail-closed instead of infinite retries.

## Limitations

P1-C remains a **bounded local WALK slice**. No Step Up/Down, Jump, Drop, Doors, cross-chunk global navigation, multi-region streamed maps, automatic Retry/Replan, or P1-D features. This sprint does not address B3's potential planning performance overhead and never replans every tick. The two older P1-A tests remain explicitly NOT YET TESTED: real Unloaded Chunk Safety and per-position Partial Block/Terrain Accuracy. B1 per-edge collision semantics in real Minecraft and 1/3/5/10 NPC simultaneous MSPT likewise remain unverified. Forge client/daemon validation is required before claiming this candidate works.

## Diagnostic flows

- `/npcortex c1 info yui` to get actual saved position; `feetY` is a **block coordinate**, not a fractional player eye height.
- `/npcortex c2 navhier yui 80 69 -62` to preview a safe candidate path before enabling movement.
- `/npcortex c2 navigate yui 80 69 -62` (uses target standing-block CENTER, e.g. x=80.5,z=-61.5; adjust your target to a safe block reachable inside the current scan).
- `/npcortex c2 status yui` during execution to inspect phase, waypoint index, C1 status.
- `/npcortex c2 cancel yui` during movement to stop and require reconciliation.
- `/npcortex c2 reset yui` **only after** stopped and after inspecting the old Backend action. Not an ordinary retry mechanism.

## Safety review expectations

1. Live-world Graph snapshots and segment ticket receipt checking must be verified in Minecraft before acceptance. Stub compiling is NOT Forge Build.
2. Construct a two-block wall with a safe passage, preview `navhier` before `navigate`; verify Yui walks around the wall instead of through it.
3. Change the path by placing a block while a segment is active or between segments, observe safe failure. Without P1-D, do not expect detours.
4. Test C1 knockback, cancel, death, despawn, Save & Quit/rejoin separately. `INTERRUPTED` should never auto-run old actions.
5. Keep `PENDING_TESTS.txt` explicitly open until user has verified corresponding real-world scenarios.
