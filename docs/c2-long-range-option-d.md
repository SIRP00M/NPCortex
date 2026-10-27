# C2 Long-Range Navigation — Option D (bounded legs), integrated candidate

**Status: implementation-side gates done; Windows gameplay PENDING. Not a complete pathfinder.**
G0 baseline was NOT measured: Owner-authorized sequencing exception, not a G0 PASS.

## Design (one logical C2 Goal)
`/npcortex c2 navlong <name> <x> <feetY> <z>` (operator-only; target <= 160 blocks, chunk must already be loaded).
One `Run`, one goal ID, one action ID, one C0 decision, one terminal `result()`. The first leg is planned in
`C2AgentRuntime.navigateLong` and started through the existing `startInternal`; later legs are planned inside the same
Run by `tickLegPlan`. `LongState`: NONE / LEG_ACTIVE / LEG_QUIESCING / FINAL_ACTIVE. `C2LongGoalPolicy.onRouteComplete`
maps a finished route: LEG_ACTIVE -> LEG_DONE (quiesce, no result), FINAL_ACTIVE -> FINAL_VERIFY (existing result path).
Leg target = lowest of distance-to-final + 6 per visit of its 4x4x4 cell, over the nodes reachable in the current
13x13 window (no hard exclusion, no blacklist). Final reachable in view wins. Plans use `C2HierarchicalPlanner`.
Plan slots (leg and replan) are granted FIFO by `C2PlanQueue`; the one-plan-per-tick limiter is kept.

## Limits (conservative defaults, not tuned from measurements)
MAX_DISTANCE 160, MAX_LEGS 40, MIN_LEG_LENGTH 3, per-leg 600 ticks, total min(8000, 1200+40*distance) ticks,
search budget 120000, 6 legs without a new best distance (min gain 2.0) = NO_PROGRESS, >3 visits of a cell = OSCILLATION.
Timeouts end the Run via the existing `interrupt` (CANCEL_PENDING fence); progress/limit/plan failures via `result(false)`.
The 3 failure replans of `C2ReplanningPolicy` are shared by the whole Run; starting a leg does not consume one.

## Not done / limits
Large barriers, mazes and detours longer than the window end with LONG_NO_PROGRESS / LONG_OSCILLATION. No chunk loading,
no unknown-terrain crossing, no automatic retry after FAIL_CLOSED, no durable route (restart cancels and reconciles C0 as before),
no backend/prompt/E3/navigate/guardedNavigate change. Segment starts (`beginSegment` full capture) are not covered by the limiter.
UNVERIFIED: Qwen copying far coordinates exactly (executor rejects any mismatch), 150-block chunk residency, real MSPT.

## Windows G5 sheet (operator; test world; walls via /fill are the operator's own test setup)
Setup: `/npcortex c1 spawn yui` (more: `spawn ann`, `bob`, ...). `/npcortex c1 info yui` gives X/Y/Z; use feetY = its Y.
1. Distances (same heading, loaded chunks): `/npcortex c2 navlong yui <x+20> <y> <z>` then 40, 80, 150. Poll `/npcortex c2 status yui`
   (shows long=<state> leg=<n>). Expect COMPLETED, NPC within 0.35 of target, one `goal=` id for the whole walk.
2. Obstacle: build a wall x=+8 spanning z-20..z+20 (`/fill`), navlong 40 beyond it. Expect FAILED with LONG_NO_PROGRESS or LONG_OSCILLATION (not a hang).
   Small wall (<= 8 wide): expect success by detour.
3. Cancel mid-leg: `/npcortex c2 cancel yui` while moving; again right after a leg (status shows long=LEG_QUIESCING). Expect CANCEL_PENDING then reconcile; no further motion.
4. Save & Quit mid-walk, rejoin: NPC must not resume. `c2 status yui` shows CANCEL_PENDING/terminal row. Then a new `navlong` and a `c2 navigate` must work.
5. Multi-NPC: repeat 1 with 3, 5, 10 NPCs started in the same tick (`/npcortex c2 navlong` per name). All complete or fail cleanly; watch `/debug start` ... `/debug stop` MSPT.
6. Regressions: `c2 navigate yui <x> <y> <z>` (within 6), `c2 follow yui <player>` / `followstatus` / `unfollow`, `c2 move`, E3 `/npcortex debug cplus taskmove yui <x> <y> <z>` (within 6/3/6), C1 spawn/despawn/motion.
7. Unsafe target: navlong to a wall/air column within 6 blocks: expect immediate "UNSAFE_TARGET" rejection; to an unloaded chunk: "target chunk not loaded".
Evidence to send: `logs/latest.log` (search `LONG`, `NPCortex C2`, `P1-D`, `ROUTE_REPLACED`), `c2 status` before/during/after, journal rows (status after the run),
backend log for the goal, `/debug` profiler output for G0/G4 (mean/p95/p99/max per NPC count), screenshots of failures.
