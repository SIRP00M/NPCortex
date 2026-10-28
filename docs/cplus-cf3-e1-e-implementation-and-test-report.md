# NPCortex C-F3 Option E — E1-E Extended Coordinator / Gate-1 Candidate

Date: 2026-10-23. **Gate-1 focused local Python PASS; overall Gate-1 PENDING Windows Forge, Minecraft gameplay and full-suite repeatability; Gate-2 NOT IMPLEMENTED.**

## Build Fix #1 — compiler mismatch found in actual Windows JDK 17 build (2026-10-23)

- **Observed user evidence on previous Gate-1 ZIP:** `:compileJava FAILED`, two Java compiler errors in `CPlusECoordinatorRuntime.java` line 88/93: `idOperation(CommandSourceStack,String,String,boolean)` called with an extra `String` route argument. JDK 17 Temurin check itself passed. Two unrelated deprecated-API **warnings** do not cause the failure.
- **Source-confirmed root cause:** `status()` and `cancel()` provided redundant `"e1/status"` / `"e1/cancel"` despite `idOperation()` already switching routes on its `boolean cancel` parameter.
- **Narrow corrective change:** replace `idOperation(src,name,taskId,"e1/status",false)` with `idOperation(src,name,taskId,false)`, and `idOperation(src,name,taskId,"e1/cancel",true)` with `idOperation(src,name,taskId,true)`. Leave method signature, callback, route logic, authorization and C1/C2 untouched.
- **Verification:** 61/61 focused Python regression PASS after change, clean ZIP CRC PASS. These Python checks do not compile Forge/Java. **Forge Build Fix #1 = PENDING Windows rerun**, Minecraft acceptance PENDING; full backend suite repeatability remains PENDING. Gate-2 remains disabled.
- **Expected next command on clean extract:** `cd F:\AI\NPCortex; .\scripts\dev-forge.ps1 -Task build`.

## Baseline and scope

- Source: `NPCortex_GWI_CF3_E_P0_DurableReadOnlyTaskGraph_FullRepository_CANDIDATE(1).zip`, 426 entries, ZIP CRC OK, clean extraction.
- User approved **E1-E** in two consecutive gates. A higher-level approval does not waive the requirement to pass Gate-1 before starting Gate-2.
- E1 changes are strictly additive: `cplus_e1_tasks` and `cplus_e1_nodes` in the existing backend SQLite database; `cplus_e_tasks` E-P0 and C-F1, C1, C2, inventory and SavedData remain unchanged. No modifying world, no C2 movement, no agent automatic dispatch.
- Operator-only `/npcortex debug cplus tasksubmit|taskqueue|taskstatus|taskcancel` commands go through authenticated loopback; `getPlayerOrException()` supplies a real requester UUID. Existing `/npcortex` root already requires permission level 2. Multi-player FIFO tests on Windows thus need **two operators** in a disposable multiplayer/LAN test, not ordinary users. This is deliberate fail-closed behavior until player request permission design is approved.
- Graph node types are strictly read-only: `observe_cells`, `report_coverage`, `report_permission`. The stored task graph is validated, bounded and topologically ordered (up to 8 nodes), but **no E1 graph execution or world effects** are advertised.
- Admission serializes via SQLite `BEGIN IMMEDIATE` and `UNIQUE(world_id,actor_id,sequence)`; dedup hashes requester+snapshot+graph. Queue is limited to 16 outstanding tasks per actor. Successful E1 requests enter `QUEUED`; there is NO background execution/replay. A requester can cancel QUEUED or HOLD tasks. Session matching during real dispatch remains E2/E3 pending.
- Stored payload/graph/identity/node corruption becomes HOLD and is never interpreted as verified work. Existing backend-local authentication and server-derived identity remain necessary; the backend cannot independently authenticate a client-supplied `SERVER_WORLD` claim from an untrusted source.

## Source Map / Dependency Matrix

| Source | Role / changes |
|---|---|
| `backend/src/npcortex_backend/cplus_e_graph.py` | E-P0 base, **unchanged** |
| `backend/src/npcortex_backend/cplus_e_coordinator.py` | **NEW** E1 durable typed task admission, FIFO, dedup, corruption HOLD, requester-scoped cancel |
| `backend/src/npcortex_backend/server.py` | Authenticated `/v1/cplus/e1/{submit,status,queue,cancel}` only |
| `forge-mod/.../CPlusECoordinatorRuntime.java` | **NEW** server-thread C-F2 capture, player UUID, bounded async HTTP, bound reply validation; no motion/world calls |
| `forge-mod/.../CPlusF1DiagnosticRuntime.java` | Add admin E1 diagnostic commands only |
| `forge-mod/.../NPCortex.java` | Add E1 worker shutdown hook; C1/C2 remains intact |
| `backend/tests/test_cplus_e1_coordinator.py` | FIFO, duplicate/replay, graph validation, SQLite corruption, isolation |
| `backend/tests/test_cplus_e1_http.py` | Local auth, HTTP FIFO, unauthorized cancel, forged allow rejection |

## Local Gate-1 tests

- New E1 tests **11/11 PASS** (9 initial + 2 corruption tests).
- Existing E-P0 **10/10 PASS**.
- C-F2 read-only **16/16 PASS**.
- C-F1 bridge **9/9 PASS**.
- C-F0 contracts **15/15 PASS**.
- **Total focused Python: 61/61 PASS** with `-W error::ResourceWarning`.
- Forge compile **BLOCKED/UNVERIFIED**: environment has JDK 21 and Gradle 8.8 wrapper cannot resolve `services.gradle.org` (UnknownHostException). No Windows game session available here.
- Full backend suite had an earlier local run **295/295 PASS in 45.848s** (`python -W error::ResourceWarning -m unittest discover -s backend/tests -q`, exit 0; retained log). **A separate repeat on the same Gate-1 candidate later timed out without a final unittest summary (over 200 seconds)**. Thus full-suite consistency is **PENDING** until the stall is diagnosed and repeated successfully on Windows. Mocked Ollama/provider tracebacks are expected in some negative tests but a timeout is not a PASS. The independently rerun focused suites remain **61/61 PASS**. This is not a Forge/Minecraft acceptance result.

## Gate-1 remaining acceptance on Windows

1. JDK 17 Forge compile, isolated clean extract, `scripts/test-cplus-e1-extended.ps1`, optional full backend suite.
2. With backend and disposable world, spawn Yui and test E-P0 world/epreview; submit two tasks with distinct real player UUIDs (both ops); queue should be #1 then #2; no owner-priority and no world/inventory changes.
3. Restart backend and world, verify stable task IDs and queued states; no automatic execution; cancel own queued task and reject cancellation by wrong player.
4. Backend-offline -> safe HOLD message, no retry; invalid token, duplicate/conflicting request, malformed graph, database corruption should fail closed in isolated copied files.
5. C1 movement/Follow, C2 navigation/cancel, inventory/save/rejoin, C-F1 sync/audit, C-F2 observation unchanged. Actual performance/MSPT 1/3/5/10 NPC must be measured in-game, not inferred from synthetic tests.

## Gate-2 hold (not implemented in this deliverable)

After explicit **Gate-1 PASS on Windows**, proceed with E2 resource leases and bounded read-only Dispatcher, collision/fencing/expiry, server-current-session checks, terminal cancel ACK, trusted fresh read-only receipts, and fault-injection/recovery. Do not register `taskdispatch`, `e2/dispatch`, C2 `move_to`, or world-mutation APIs before the gate is closed.

## Security / Risks

- Shared secret is limited to loopback and may be readable by local applications in the same user context. End-user requests must originate from authenticated Forge Commands, not public C0 endpoints.
- A response arriving after Save/Rejoin is never fed into a world executor; E1 merely shows status. No active execution means no claims of verified world effects.
- SQLite task rows and world SavedData are not a global atomic transaction. Future E2 needs independent session fencing and authoritative fresh perception.
- E-P0 preexisting status `READ_ONLY_VERIFIED` means verified immutable snapshot evaluation only; it is **not** a world mutation receipt.
- No GitHub operations performed.
