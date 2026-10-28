# NPCortex C2-P1-C Fix #1 — Post-Cancel Recovery

**Status:** Candidate. Requires **Windows Forge build and Minecraft gameplay acceptance**.
**Scope:** Transactional cancellation and safe post-cancel recovery only. No P1-D dynamic replanning.

## Why the original implementation needed manual Reset

The C2 Action Journal persisted `INTERRUPTED` and prevented new tasks to avoid duplicate execution while the C0 pending action remained unresolved. `/npcortex c2 reset` cleared only the local Forge journal, not the backend.

## Updated protocol

1. On `/npcortex c2 cancel <npc>`, the server thread requests C1 motion stop, releases the active route owner and persists `CANCEL_PENDING` in SavedData (except cancellation before C0 goal creation is even attempted).
2. An asynchronous reconciler waits for the C1 motion receipt to become inactive. It POSTs `/v1/agent/goals/{goal_id}/cancel` with `world_id`, `actor_id` and optional `action_id` using the existing allowlisted local C2 HTTP bridge.
3. C0 locks the goal against simultaneous decision/result, validates world/actor/action identity, and atomically marks a pending action `failed` with `operator_cancelled` verdict, marks the goal `failed`, and clears `pending_action_id`. No execution success is claimed. Repeated cancel calls return the same terminal outcome.
4. Forge checks that acknowledgement goal/actor/action identifiers match, goal is terminal, and no backend action remains pending, **and** checks C1 motion is inactive again. Then it persists `CANCELLED` or a truthful pre-existing terminal outcome (`COMPLETED`/`FAILED`). **Only now is a new navigation action allowed.**
5. When C0 is offline, timed out, or its response is malformed, remain `CANCEL_PENDING` and retry no more frequently than once per 100 server ticks (roughly five seconds at 20 TPS). The retry state is rebuilt from SavedData on Save/Quit/rejoin. There is no blocking HTTP call inside a server tick.
6. Previous P1-C journal rows labelled `INTERRUPTED` **specifically by operator cancel** are eligible for automatic reconciliation. Other kinds of `INTERRUPTED` remain fail-closed.

The existing `/npcortex c2 reset` command remains a manual recovery tool for unrelated unresolved states, but is **refused while `CANCEL_PENDING`** to prevent bypassing backend reconciliation.

### Backend contract

`POST /v1/agent/goals/<goal-uuid>/cancel`

Request object: `{"world_id":"<uuid>","actor_id":"<uuid>","action_id":"<uuid optional>"}`.

Response: `goal_id`, `actor_id`, `action_id`, `status` (`failed` or `completed`), `terminal=true`, `pending_action_id=null`, `cancelled`, `replayed`. Old successful terminal goals are not rewritten as cancelled. Cancellation endpoint is idempotent by goal identity; a repeated cancellation cannot create another action.

### Safety and limitations

- C0 runs on an unauthenticated localhost prototype. Do not expose the port publicly.
- C1 still owns Vanilla physics, combat impulse and final settling; this change does not touch movement integration.
- `CANCEL_PENDING` deliberately blocks new work until acknowledgement. If Ollama is busy or the backend is offline, there may be a short waiting period (or longer when offline), but **no manual Reset is required for ordinary successfully reconciled cancellations**.
- If a cancellation happens exactly while goal creation is in flight and that creation never arrives at C0, automatic reconciliation may remain pending until the backend can conclusively answer; no speculative reset is performed. This unusual boundary remains a separately tracked acceptance case.
- SQLite action/goal writes are transactional; this is not proof of cross-process crash-atomicity or SavedData fsync. Power loss and network partition faults require injection testing before any exactly-once guarantee.
- P1-D replanning, jump/drop edges, new Skills, multiplayer scheduling and changes to other C1 systems are outside this scope.

## Windows acceptance checklist

1. Backup the functioning world/repository; extract this Full Repository ZIP.
2. From PowerShell in `C:\path\to\NPCortex`, run `./scripts/test-c2-p1c-fix1.ps1` and `./scripts/dev-forge.ps1 -Task build` (PowerShell `\.\scripts\...` also works).
3. Start `./scripts/dev-backend.ps1` and `./scripts/dev-forge.ps1 -Task runClient` separately.
4. Spawn/inspect Yui; select an accessible destination 3–6 blocks away, run `/npcortex c2 navigate yui <x> <feetY> <z>`, then `/npcortex c2 cancel yui` while walking.
5. Run `/npcortex c2 status yui`. `CANCEL_PENDING` may appear briefly; wait for `CANCELLED` (or a truthful already-terminal state). **Do not issue `/npcortex c2 reset yui`**.
6. Immediately run another `/npcortex c2 navigate yui <reachable-target>` once the previous status is terminal. Verify movement and normal completion.
7. Repeat cancel twice to confirm no extra movement/action. Confirm previous goal is terminal in C0 (optional GET `/v1/agent/goals/<goal_uuid>`).
8. Start a route, stop Backend, cancel, verify Yui stops and state remains `CANCEL_PENDING`; restart Backend, wait, and verify automatic reconciliation and new task acceptance.
9. Save/Quit while `CANCEL_PENDING`, reopen the world with Backend available and verify the old route does not restart, the journal eventually reconciles, and a fresh route can begin.
10. Repeat baseline waypoint path, obstacle detour, blockage and C1 combat/physics regressions. Record any server thread warnings and MSPT if available.

**Expected:** no manual reset for ordinary cancellation, no duplicate action, no success claim for cancelled action, and fail-closed behavior under ambiguity.
