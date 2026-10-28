# Transport E Windows acceptance — handoff diagnostic candidate (2026-10-25)

**Purpose:** Finish Transport E quality gates before the first real GWI world action. The new operator diagnostics show safe error categories instead of the ambiguous `reserve rejected/offline` message. This change does **not** alter retry policy, motion ownership, durable records, backend schema, world effects, Qwen dispatch or automatic recovery.

**Safety first:** Run in a *copied* Creative test world, and use a test backend database. Keep the existing world `data/npcortex` directory **intact inside the copy**; never clear old SQLite attempts, outbox records, `.tmp`, or world data just to free an actor. Do not experiment on your valuable original world. Record any pre-existing `RESERVED`/`HOLD` before the test. If a prior attempt is fenced, test with another copied world rather than forcibly resetting it.

## Gate A — Windows build and automated checks (independent from gameplay)

In PowerShell, use the extracted repo root containing `scripts/`, `backend/`, `forge-mod/` (change the example path if needed):

```powershell
cd C:\path\to\NPCortex
java -version
javac -version
python --version
.\scripts\test-transport-e.ps1
```

Expected: Temurin/JDK **17**, backend `Ran 396 tests ... OK`, and Gradle `BUILD SUCCESSFUL` for `build verifyTransportE verifyTransportOutbox verifyG2Identity verifyG3TerminalContract`. A missing Gradle distribution or Forge type-check failure is a **FAIL/PENDING**, not a passing gate. If Forge is temporarily blocked, isolate backend using:

```powershell
.\scripts\test-transport-e.ps1 -BackendOnly
```

Capture exit code, complete failing test traceback and Gradle output. Do not report earlier candidate's Forge build as a pass of this ZIP.

## Gate B — Clean-world status & backend HTTP

Terminal 1 (backend):

```powershell
cd C:\path\to\NPCortex
.\scripts\dev-backend.ps1
```

Terminal 2 (Minecraft):

```powershell
cd C:\path\to\NPCortex
.\scripts\dev-forge.ps1 -Task runClient
```

In a disposable Creative world with cheats/operator permission:

```text
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 status yui
/npcortex debug cplus taskmovestatus yui
/npcortex debug cplus taskmovejournal yui
```

Skip `spawn` if Yui already exists. With a fresh world/actor and no prior attempt, expect E3 state `NONE` (no 500), C2 idle and no new action. Backend should log `POST /v1/cplus/e3/status` with HTTP 200. If status is HOLD/RESERVED on an existing test world, **retain** it and capture provenance rather than trying to force NONE.

## Gate C — Two independently verified completed moves

Pick a *flat, loaded, safe* target no more than 6 horizontal blocks and 3 vertical blocks from **Yui's actual block position**. Do not blindly paste these placeholders:

```text
/npcortex debug cplus taskmovepreview yui <x> <y> <z>
/npcortex debug cplus taskmove yui <x> <y> <z>
/npcortex c2 status yui
/npcortex debug cplus taskmovestatus yui
/npcortex debug cplus taskmovejournal yui
/npcortex debug cplus taskmovefinalize yui
/npcortex debug cplus taskmovestatus yui
```

After first `taskmove`, **wait for** `C2_ACCEPTED`, and observe Yui walking to the target. Before `taskmovefinalize`, C2 must be `COMPLETED` with matching goal and a valid grounded/live terminal witness. Expect a durable `COMPLETED` terminal ACK, then status `COMPLETED` / `completion_verified=true` and actor fence released. Do **not** call finalize just because Yui is visually nearby. Repeat at a *different* safe, loaded target and confirm different task/goal identifiers and second durable completion; two moves cannot share one goal proof. If G2 prints `HOLD` or remains fenced, **stop** and record backend HTTP code and G2 code; do not fire a new move blindly.

## Gate D — Cancel while actively moving, then reconcile

Use a separate copied test-world/session and an active G2 move (within the existing 6-block constraint):

```text
/npcortex debug cplus taskmovecancel yui
/npcortex c2 status yui
/npcortex debug cplus taskmovestatus yui
/npcortex debug cplus taskmovereconcile yui
/npcortex debug cplus taskmovestatus yui
```

Expected: matching C2 goal gets an acknowledged, scoped cancel; backend `CANCEL_PENDING` preserves fence until C2/C0 terminal confirmation and C1 motion stopped/grounded. `taskmovereconcile` then produces durable `CANCELLED`; no unrelated Follow or other NPC is cancelled. Run reconcile **only after** cancellation terminal evidence exists. For a lost cancel-intent ACK, repeating `taskmovecancel yui` with the same online requester is explicitly supported **while the original goal is active**; do not use generic `/npcortex c2 cancel` to release the G3 fence.

**Completion/cancel race:** If the goal finished during cancel, `taskmovecancel` may reject; inspect C2 journal/status and backend state. A live C2/C0 completion witness with backend `CANCEL_PENDING` can take `taskmovefinalize` instead. Exactly **one** durable terminal state must win (`COMPLETED` or `CANCELLED`); neither may overwrite the other. If the witness is missing after Save/Rejoin, HOLD rather than claim completion.

## Gate E — Fail closed, recovery and connectivity

1. Shut down backend **before** issuing a new G2 reservation on a copy. `taskmove` must never start movement, and must show an explicit code such as `[CONNECT_FAILED]`/`[HTTP_TIMEOUT]` (exact code depends on the failure). Inspect status after restoring backend; a previous durable HOLD is **not** grounds to clear/delete records.
2. `tasktransportrecover yui` only runs explicit **metadata** reconciliation, never C2 navigation. If the durable reserve was **proven not handed off** and backend agrees, it may abandon the reservation. A reserve with ambiguous or possible handoff **HOLDs**, even if this is inconvenient. A new move requires a separate explicit `taskmove` and a free actor.
3. For lost *report*, *finalize* or *cancel-ack* HTTP response, fault injection should be done only using a disposable copy and a suitable local HTTP test proxy/harness. Run `/npcortex debug cplus tasktransportrecover yui`, then `taskmovestatus`. Expect the same task/attempt and idempotent metadata only, **never another C2 goal/movement**. Do not fabricate a 200 ACK or manually edit transport JSON.
4. Backend restart, Minecraft Save/Quit/Rejoin, orphan `.tmp`, file lock/write failure and corrupt records must preserve evidence and fail closed (HOLD). Never delete an orphan file to claim this gate passed. Check the copied world's `data/npcortex/transport` directory read-only.
5. Multi-user requester mismatch must be rejected. Dedicated-server and 1/3/5/10 NPC native server MSPT remain PENDING until measured on Windows.

**Error codes from this candidate:** `HTTP_<status>` means backend replied with a non-200; `HTTP_TIMEOUT` = request timed out; `CONNECT_FAILED` = network connect failed; `OUTBOX_CONFLICT`/`OUTBOX_UNSAFE`/`OUTBOX_FILESYSTEM`/`OUTBOX_IO` = local durable record/disk problem; `TRANSPORT_UNKNOWN` = other failure. These codes deliberately exclude backend response bodies, secret tokens and filesystem paths. `HTTP_409` is a conflict **not** proof of backend offline or bad pathfinding. `HOLD` is a safe result, not permission to reset data.

## Gate F — Regression and evidence to return

After the transport gates, in a *separate* safe regression test world, confirm C1 spawn/lifecycle; inventory/equipment read + previously supported explicit inventory tests; `/npcortex c2 status yui`; `/npcortex c2 follow yui <player>` then `/npcortex c2 followstatus yui` and `/npcortex c2 unfollow yui`; ordinary C2 navigation and cancel. Ensure testing Follow does not overlap an active G3-owned goal. Record operator name + world/session IDs only if needed for diagnostics; redact credentials and unrelated personal data.

| Test | Expected evidence | Your result |
|---|---|---|
| A Build + all automated | Python 396 OK + Forge BUILD SUCCESSFUL + 4 Gradle tasks PASS | PENDING |
| B Fresh status | HTTP 200, NONE, no movement | PENDING |
| C Move #1 | C2 verified arrival, G3 COMPLETED durable ACK | PENDING |
| C Move #2 | new IDs, second G3 COMPLETED | PENDING |
| D Cancel | matching goal CANCELLED durable ACK | PENDING |
| D Race | exactly one terminal, no false cancellation | PENDING |
| E Offline/response loss | diagnostic code and metadata-only, no replay | PENDING |
| E Save/Rejoin/disk | old records retained, HOLD when uncertain | PENDING |
| F C1/C2/Follow/Inventory | no regression | PENDING |
| F Multi-NPC/MSPT | measured 1/3/5/10 in real Minecraft | PENDING |

**Send back**: (1) Gate A final output / Gradle failure section, (2) screenshots of in-game chat for each gate and movement, (3) `forge-mod/run/logs/latest.log` from the actual session, (4) backend terminal HTTP log/status codes around each `task_id`, (5) precise reproduction steps and whether records were previously HOLD. Avoid sending local-auth token, `.env`, or raw private credentials. If sharing SQLite/transport data is necessary, agree on a sanitized copy first and keep original world/DB unchanged.

## Next architecture milestone (not approved in this candidate)

Once Transport E passes, audit the source again before authorizing the next vertical slice: fresh read-only observation and permissions; guarded one-block `equip_item` + `break_block` via Forge on the server thread; separately verify block break and inventory ownership; `pickup_item` with verified actual inventory increase; durable cancel/recovery without duplicating items or reverting another player's pickup; then a bounded "obtain 8 oak logs" task. `place_block` and `use_item` come later. **None of these are implemented by this transport patch.**
