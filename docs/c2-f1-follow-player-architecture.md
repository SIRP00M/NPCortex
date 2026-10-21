# C2-F1 — Reliable Follow Player (Architecture C)

## Scope / operator commands

- `/npcortex c2 follow <npc> <onlinePlayerName>` starts an **ephemeral**, UUID-bound Follow Session. This command is operator-only, not Qwen/NL.
- `/npcortex c2 unfollow <npc>` stops only the C1 movement ticket owned by this Follow Session. `/npcortex c2 cancel <npc>` also stops follow if no static C0 navigation is active.
- `/npcortex c2 followstatus <npc>` displays session ID, target UUID, state, bounded route generation/failure count and reason.
- Follow **does not** create a C0 Goal/Action or call Ollama every tick. This is a bounded server-local C2 navigation skill, not a new C0 backend tool. C0 static `navigate`/`move` remains exclusively owned and unchanged.

## Architecture / isolation

1. Follow Session claimed by **C1 actor UUID**; player tracked by **ServerPlayer UUID**. C2 static Navigate and Follow cannot own the same NPC simultaneously. An unresolved C0 Journal stops follow from starting.
2. Tick on server END after C1 Vanilla Physics and C2 static runtime; no custom physics, teleport, or forced chunk loads.
3. Hysteresis: hold within 2.5 blocks **3D**, resume when at least 4.0 blocks apart. For target displacement >=1.5 blocks, replan at a safe C1 motion terminal, never mid-ticket. Plan no more than one NPC's batch per server tick, cooldown 20 ticks.
4. Fresh local 3D graph radius 6, typed `WALK/STEP/JUMP/DESCEND` edges. Candidate nodes must be 1.8–3.35 blocks **horizontally** from the tracked player with footY within 1.1 blocks, i.e. cannot use the player's exact cell as the goal. Up to 4 candidate B3 plans, <=3,000 expansions each; choose verified path with least cost among those found.
5. Execute via the existing `C2WaypointBridge`/`C2WaypointExecution` / `C1P1MotionRuntime` authoritative motion ticket. Each segment validates a fresh edge and exact token. A foreign live ticket or unsupported/ambiguous state is fail-closed.
6. At most 3 consecutive failed movement/planning attempts until the target moves materially or the operator restarts Follow; merely finding another path cannot reset failure counts. Route and segment watchdogs also bound execution.
7. Target logout/death, dimension mismatch, excess local range, or too-high elevation => PAUSED with no owned movement. Return into a loaded, reachable area can resume under the same in-memory session; operator may stop at any time. Unloaded world remains unknown, not force-loaded.
8. `C2FollowJournal` is an **audit-only** SavedData. Restart never replays old Follow movement; active journal records are displayed as `STOPPED_AFTER_RESTART`, explicitly restarted by operator only. The journal does **not** imply durable exactly-once C0 commits.

## Limits

This Sprint does **not** include following across chunks far beyond the radius, Qwen conversation control, multi-dimensional portal follow, server-client packet feature changes, mining/building, or replacing Vanilla C1 Physics.

C1 static navigation/C0 Cancellation Fix #1, S4 Fix #1 Replanning Recovery and its PENDING_TESTS remain in force. The actual Forge build, singleplayer/multiplayer gameplay, Save/Quit behavior, and MSPT are acceptance tests **NOT** proven by pure-Java unit tests.
