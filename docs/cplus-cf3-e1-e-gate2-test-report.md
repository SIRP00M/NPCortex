# NPCortex C-F3 Option E — E1-E Gate 2 Test Report

**Baseline:** `NPCortex_GWI_CF3_E1E_Gate1_BuildFix1_FullRepository_CANDIDATE.zip` (433 entries; CRC passed). Clean extraction before edits. **No GitHub operations.**

## Scope / architectural boundaries

- Additive backend `cplus_e2_dispatcher.py`, SQLite `cplus_e2_leases`, `cplus_e2_runs`, `cplus_e2_receipts`, `cplus_e2_cancel_acks` tables. No C1/C2 implementation edits; no C1 inventory or world writes.
- Read-only reservation key: world + dimension + `READ_CELL` + exact sampled center. Exclusive coordinator-level read reservation; **not** Forge claim permissions, block mutation locks, or a substitute for a future multi-resource GWI world lease.
- Only operator-authenticated, local `taskdispatch <npc>` may claim FIFO head, with current `SERVER_WORLD` C-F2 v2 snapshot. Only `taskverify <npc>` may verify against a *new* current capture and finish/read-release. Operator must be a real player. No server-tick dispatcher, Qwen auto-submission or automatic recovery.
- Persistent held attempts enter `HOLD` on Backend restart; stale lease generation cannot release a different owner. Strictly no world action/replay. `READ_ONLY_VERIFIED` means coverage/permission summary *observation* confirmed, **not a Minecraft action receipt**.
- Cancel while DISPATCHING: backend checks original requester, writes a durable read-only cancellation ACK and releases its own coordinator lease atomically. This is **not** a C2 cancellation ACK and does not stop movement (none starts).
- Existing E1 queued tasks from a previous Minecraft session may enter `HOLD` when dispatched with a different session ID. Start new tasks in the current session for E2 acceptance.

## Verified local automated tests (Python 3.13.5; Linux isolated copy)

| Gate | Result | Evidence |
|---|---|---|
| E2 Dispatcher (11 tests) | PASS | FIFO, receipt tamper -> HOLD, cross-NPC READ_CELL collision, generation fencing, expiry -> HOLD, restart fencing, cancellation ACK, auth/identity rejection, read-only 1/3/5/10 synthetic actors |
| E2 HTTP (2) | PASS | loopback secret, 403 wrong token, staged dispatch/verify, forged permission rejected |
| E1 Coordinator + HTTP (11) | PASS | original E1 admission/cancel/idempotency tests |
| E-P0 (10) | PASS | prior read-only graph contract |
| C-F2 (16) | PASS | observation v2 permission policy |
| C-F1 (9) | PASS | event bridge tests |
| C-F0 (15) | PASS | ownership contract tests |
| **Focused total** | **74/74 PASS** | `scripts/test-cplus-e2-readonly.ps1` equivalent Python unittest suite |
| Full Backend | **307/307 PASS on pre-final additional 1 synthetic test** | `/mnt/data/npcortex_gate2_full_backend.log`; final 308-test repeat pending unless separately documented |
| Java/Forge Windows JDK 17 build | **PENDING** | This environment has JDK 21 only; must run on user's Windows JDK 17 |
| Windows Minecraft Gate-2 gameplay | **PENDING** | commands and expected responses in checklist |
| C1/C2/Inventory/Follow and C-F1/F2 in-game regression | **PENDING for this candidate** | previous Gate-1 success is not automatic Gate-2 acceptance |
| Backend Offline/Online & interrupted E2 dispatch | **PENDING in Minecraft** | Python unit tests cover restart, not live Windows/process crash |
| Real 1/3/5/10 NPC MSPT | **PENDING** | synthetic multi-actor is not an in-game profiler |
| Multi-resource all-or-none locks | **NOT IMPLEMENTED / PENDING future scoped extension** | this Gate-2 only leases one deterministic READ_CELL key per read task |

## Integrity notes

Source review compared the extracted candidate against the clean Baseline. E2 Java edits are additive in `CPlusECoordinatorRuntime` and `CPlusF1DiagnosticRuntime`; new HTTP endpoints use the existing loopback/token controls and bounded Forge worker. Existing C1/C2 controllers, inventory authority, and C-F1/F2 production source files remain unchanged.

## Known limitations

- `taskdispatch` and `taskverify` intentionally require manual operator commands; no automatic background Task Graph processing or live C2 movement. This proves the guarded coordinator boundary, **not** a fully autonomous NPC.
- Resource reservation is one `READ_CELL` key per task; no general multi-resource hierarchy or mod claim manager. This is *not* proof that World Mutation is safe.
- Interruption after dispatch or backend response loss may leave `DISPATCHING` until operator cancellation, timeout or Backend restart. **Do not retry or promote to COMPLETED blindly.**
- Forge build/Java check was not performed here; never claim PASS from Python tests. On a compile/gameplay failure, stop Gate 2 and fix within scope before considering E3.
