# C2-F2 Fix #1 — Continuous Player-like Locomotion (Architecture C)

**Baseline:** C2-F2 Natural Player-like Locomotion Full Repository Candidate.
**Scope:** Follow-only horizontal WALK motion handoff, safeguarded continuous steering, rotation interpolation, and regression. No new C0 actions; no backend or Qwen changes, multi-chunk route, teleport or PathfinderMob.

## Implementation summary

1. `C2WaypointBridge.beginFollowWalkChain`: a Follow-only entry point selects at most **6** successive flat WALK targets and **7.75 blocks total**. Every graph edge (including the internally skipped up-to-3-block edges), directed movement type, world-border/chunk availability and physically swept player-sized AABB are checked against a fresh loaded graph and world collision probe before motion starts. No vertical transition is coalesced.
2. `C2WaypointExecution.startedWalkChain`: one immutable C1 Motion Token can cover these consecutively verified flat segments. Only the matching terminal `COMPLETE` **and** physically reached final target advances the full chain; foreign token, `BLOCKED`, cancelled, wrong endpoint and unknown status fail closed. Standard C0 navigation and typed 3D route remain single-segment owners.
3. `C1P1MotionRuntime.moveC2ContinuousWalk`: one in-memory, server-thread-only C1 `Session` and `C2_COMMAND` ownership throughout the horizontal chain. It runs bounded forward input via the existing Vanilla `Player.travel()` loop, rotates yaw/head/body during travel with <=18 degrees/tick, and does **not** zero horizontal velocity on intermediate verified flat nodes. C1 travel/jump/fall/knockback primitives were not replaced. Last endpoint stops and produces an ordinary correlated terminal receipt.
4. `C1F2ContinuousWalk`: pure Java, bounded targets/ticks/stall checks, nonfinite/height rejection, shortest-angle yaw handling, lower forward speed for sharp turns (zero input for unsafe near-reversal), stop/cancel terminal guards. Every tick, the real player's immediate heading corridor is checked using loaded chunks/world border, fresh local floor risk/support, and swept AABB collision. On failure, close the ticket safely and let Follow's established bounded replanning policy handle it.
5. **Combat > Commanded WALK**: an actual knockback cancels the continuous chain; Vanilla knockback gets priority and the stale Follow target cannot resume automatically. Follow can replan after a verified terminal C1 ticket. Unfollow/stop owns and cancels ONLY its exact C1 token; no stale C0 work or motion on server reload. Existing S4 journaling/recovery unchanged.
6. `C1P0ClientSync`: only the first rotation packet in a *verified viewer session* resets the `*RotO` render interpolation endpoints. Later revisioned updates preserve previous-frame endpoints for client interpolation. Frequent per-revision rotation ACK/sync logs moved from INFO to DEBUG; viewer token/revision checks are unchanged. Explicit sync during a WALK chain is limited to every second server tick, aside from normal Vanilla tracking packets.

## Tradeoffs and exclusions

- A Follow chain still stops at its last verified local target and on typed vertical transitions; it is not a global perpetual motion controller or unconditional obstacle avoidance.
- Dynamic floor/obstacle change may fail/stop the local chain; safety and route correctness take precedence over uninterrupted movement. Planner cooldown/failure budget still apply.
- No custom player walk animation changes. Client smoothness requires Windows Forge visual acceptance. No real 1/3/5/10 NPC MSPT measurements in this environment.
- Current C2-F2 terrain and navigation Full Quality Gates remain pending; this fix does not close them.

## Source files

`C1F2ContinuousWalk.java` (new); `C1P1MotionRuntime.java`; `C1P0ClientSync.java`; `C1P0Runtime.java` (logging); `C2WaypointBridge.java`; `C2WaypointExecution.java`; `C2FollowRuntime.java`; `C2F2ContinuousFollowFix1SelfTest.java` (new); `scripts/test-c2-f2-fix1-continuous.ps1` (new).
