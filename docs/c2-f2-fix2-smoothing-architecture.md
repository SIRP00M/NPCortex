# C2-F2 Fix #2 — Player-like Motion Smoothing (C)

**Baseline:** C2-F2 Fix #1 Candidate. **Operator-only Follow**; single authoritative C1 Custom ServerPlayer physics owner. No PathfinderMob, teleport, world force-load, new backend goals, or client-side movement authority.

## Source audit

The user confirmed Fix #1 WALK Chain and typed JUMP_UP/SAFE_DESCEND executed, but visual locomotion still feels segmented relative to Vanilla PathfinderMob. Original chain uses direct aim at one node with a final-approach slow and zero velocity at final ticket. Follow waits for motion receipts to select a subsequent route. This patch improves **intra-route** handoff and near-corner steering, not a new global continuous navigation engine. C1 network interpolation and the actual client animation remain Windows gates.

## Gate 1 — Diagnostics and deterministic contracts

`C1F2MotionTelemetry` (pure Java, per C1 ticket) records samples, near-stationary samples (movement <0.008 blocks/tick), commanded zero-forward samples, nontrivial yaw updates, committed waypoint count, extension count and cumulative horizontal delta. `WALK_TELEMETRY` appears **once at ticket completion**, not every tick. `/npcortex c2 followstatus yui` includes the live owned C1 WALK progress record; no NPC, player or backend IDs are sent externally. These metrics are **server-side** and cannot alone establish client FPS/render smoothness; Windows video/packet inspection remains required.

## Gate 2 — Short-path look-ahead

`C1F2ContinuousWalk` steers towards a bounded blended look-ahead point near the *next already-approved WALK node*, up to ~1.10 blocks ahead; yaw still changes at most 18° per authoritative tick. Sharp reverse turns retain zero forward input until aligned: walking backwards without collision proof is forbidden. All player motion continues through the original `Player.travel()` code path, with fresh loaded-chunk / hazard / support / swept AABB checks each tick. Near the final endpoint, forward input decelerates to avoid overshoot.

## Gate 3 — Opportunistic same-ticket handoff

The current Follow `C2WaypointExecution` may precommit additional flat WALK nodes **before** its existing C1 ticket ends. C2 tests a fresh 3D directed graph, validates *each new edge* and full-player swept corridor. Only the precise original C1 ticket (`profile UUID` + active C1 token + origin `C2_COMMAND`, no combat) can atomically accept these targets. The route's terminal receipt endpoint/count extend under that same token. Limits: <=6 added per call; <=24 queued targets, <=32 committed total blocks, <=240 steering ticks per C1 session; never extend a vertical primitive or an already-cancelled/terminal session. Attempt a costly fresh-graph extension at most once per committed endpoint while near the end (no re-probe every Tick on failure). If the target player has moved substantially since the plan, skip extension and replan after the ordinary terminal receipt instead of prolonging a stale direction.

**Important limitation:** this is not seamless across *different* B3/Follow routes, and does not replace the live path on each changing player pose. A terminal handoff (and speed reset) is still expected between independent local routes. This limitation and any residual jitter must be judged on Windows before claiming Visual PASS; do not increase the scope without a fresh audit and approval.

## Gate 4 — Safety/integration contract

No edits to underlying Vanilla combat formulas or C0 Backend. FOLLOW exclusively owns `C2_COMMAND` ticket. Combat cancels stale continuous motion; stale/cancelled token cannot be extended. Other C0 navigation, operator C1 movement and typed STEP/JUMP/DESCEND still use their previous ownership, physics and landing receipts. The extension is processed only by the Forge server thread, never async. Old sessions are never auto-resumed after restart.

## Why not PathfinderMob

PathfinderMob integrates pathfinding and continuously updated navigation with a dedicated mob MoveControl, but changing the NPC entity would break user-selected player-based inventory, networking and mod compatibility architecture. This patch instead incrementally improves Follow-specific steering on the retained ServerPlayer substrate.
