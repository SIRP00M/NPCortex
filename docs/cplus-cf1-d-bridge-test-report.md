# NPCortex C-F1 D+ Transaction Bridge D1–D5 — Test Report

**Baseline:** SQLite Windows Fix #1 Full Repository Candidate (383 entries). No GitHub activity. No live World Mutation / C1/C2 ownership changes.

## Changes delivered

- D1: Binary journal v1 → bounded JSON wire with event identity, sequence, epoch and Java SHA-256 frame-chain parity. Python verifies the **actual Java DataOutputStream modified-UTF binary payload hash**; rejects altered or skipped records.
- D2: Loopback-only + shared-secret C+ HTTP routes, additive SQLite inbox/checkpoint/receipt tables with `synchronous=FULL`, duplicate event receipt handling and idempotent receipt confirmations after Forge delivery checkpoint.
- D3: Operator-triggered Forge async bounded delivery (8 events/sync on pre-existing 32-slot IO queue); local cursor force + atomic replace only after ACK. No automatic world replay; backend network failures leave journal healthy and delivery in HOLD.
- D4: Read-only per-world action-state classification; `PREPARED`, `PARTIAL`, `IN_DOUBT`, `VERIFIED`, `CANCELLED`. No world/inventory observation; synthetic journal states only.
- D5: New Python HTTP/SQLite tests; Java wire/restart/offline fail-closed cases; additive Windows test runner and user checklist.

## Results observed in this Linux container (2026-10-22)

| Test | Result |
| --- | --- |
| Java C-F1 Wire/Transport | **12 checks PASS** (`javac --release 17` on JDK 21) |
| Java C-F1 Async Diagnostic | **16 checks PASS** |
| Java C-F1 D+ Synthetic | **35 checks PASS** |
| Java C-F0 Contract | **71 checks PASS** |
| Java / Python direct ledger golden parity | **3/3 Event frames PASS**, including Unicode Modified UTF-8 |
| Real loopback Java HTTP → Python Backend SQLite + confirmed ACK | **PASS** for 3 events, subsequent sync 0 events; Backend CANCELLED=1, confirmations pending=0 |
| New Backend Bridge | **9/9 tests PASS** with ResourceWarning elevated |
| Synthetic 1/3/5/10 actor SQLite delivery isolation | **PASS** (not a Minecraft MSPT measurement) |
| Existing Backend D+ | **15/15 PASS** |
| Existing Backend C-F0 | **15/15 PASS** |
| Existing Backend C2 | **16/16 PASS** |
| Existing Backend Persistence | **6/6 PASS** |
| Follow/Navigation pure Java | **22/22 Suites PASS** |
| Forge Gradle Build | **BLOCKED**: Gradle 8.8 download via services.gradle.org DNS unavailable in this container |
| Windows JDK17/Python 3.13 + Minecraft sync | **PENDING user test** |
| Real process-kill/power loss and Disk Full | **PENDING**; tests cover synthetic rejection / offline / corruption but NOT true OS crash durability |
| World Mutation crash recovery | **NOT IMPLEMENTED** under approved C-F1 scope |
| Measured 1/3/5/10 NPC MSPT | **PENDING** (not inferred from isolated performance checks) |

## Safety boundaries

- No `break_block`, `pickup_item`, `place_block`, or container mutations registered, no Saga executor, no automatic action replay; C1 Player/Inventory and C2 Navigation/Follow/Cancellation unchanged.
- Backend `ACKED` is **delivery durability**, not Minecraft action completion. A synthetic `VERIFIED` history may never be treated as an authoritative world-state result without future C-F2/C-F4 evidence.
- Reconciliation is journal-state classification only. When identity, chain, epoch, cursor or ACK disagree, the bridge HOLDS; it never resets silently.
- Existing staged `CPlusF1Projection` remains separate; additive bridge tables do not replace old C2 or Inventory journals. Storage durability is not atomic across Forge Journal, Minecraft World Save and Backend SQLite.

The Candidate is **implementation complete for the staged diagnostic bridge** but NOT production-accepted until Windows/Forge gates are verified. Additional automatic scheduler integration or real-world effect recovery requires a new source audit and explicit approval.
