# C2-P1-D Implementation Candidate — Test Report

**Basis:** C2-P1-C Fix #1 Transactional Cancellation & Recovery Full Repository Candidate.
**Scope:** Flat-cardinal WALK edge bounded dynamic replanning; one C0 Goal and Action throughout route replacements. No C1 Vanilla Physics modification. No multi-chunk, jump, step/drop or P1-D+ feature expansion.

| Test | Evidence | Status |
|---|---|---|
| P1-D pure-Java ReplanningPolicy | 116 assertions, `--release 17` | PASS (local) |
| P1-D synthetic terrain + B2/B3 replan integration | 27 assertions, `--release 17` | PASS (local) |
| P1-C Waypoint Protocol | 261 assertions | PASS (local) |
| Java suite including C1/C2 planner and cancellation regressions | 2,429 **unique** assertions (`--release 17`), including 143 new P1-D assertions | PASS (local) |
| Forge-facing Agent Runtime mock API compile | `javac --release 17` with non-Forge mock classes | PASS (syntax/interface smoke only) |
| Backend complete regression | `python -m unittest discover`, 201 tests | PASS (local) |
| Full baseline Java regression | Existing C1/C2 isolated Java self-tests rerun locally (included in 2,429 unique assertions) | PASS (local, Java 17 target); Windows script still required |
| Forge 1.20.1 Gradle compilation | Gradle 8.8 not cached; `UnknownHostException: services.gradle.org` | BLOCKED (environment) |
| Windows Forge Build / real game | User to execute | NOT YET TESTED |
| Concurrent replan Forge MSPT | User to measure | NOT YET TESTED |
| Real-world offline/rejoin/crash cases | User to test selectively | NOT YET TESTED |

The mock API compilation is not a replacement for Forge compilation. Recorded timing of five synthetic Java B3 plans is not a real server tick benchmark.

## Critical invariants

1. Per Actor: one active `Run`, one C0 Action, at most one C1 motion ticket. No Qwen restart for each detour.
2. Only matching terminal C1 motion receipts can cause route recovery; foreign/late tickets fail closed.
3. Every replacement uses fresh server-thread collision, Graph, Snapshot and B3 plan; the C1 bridge verifies each actual WALK edge again.
4. `REPLANNING` is journaled as uncertain across restart. Never automatically replay incomplete C0 action.
5. One expensive hierarchical replan attempt globally per server END tick; max 3 attempts per Goal with 20 tick cooldown and aggregate B3 expansion budget 36,000.
6. Cancel always outranks replan, and uses Fix #1 C0 terminal reconciliation before unlocking new work.
7. No user-facing success without final Minecraft position and C0 verification.

## Open risks / acceptance limits

- B3 planning on the main thread can produce visible MSPT spikes despite the one-plan-per-tick cap; requires real stress tests.
- Three unsuccessful attempts use the same original goal but rescan fresh state. This supports manually opening a path during retry but is not global navigation or proactive continuous map watching.
- Unloaded chunks, partial slabs/stairs, per-edge clearance, crash persistence, multi-NPC timing and restart recovery require independent proof; see `PENDING_TESTS.txt`.
- Never interpret `NO_PATH` caused by local radius limits as a world-scale absence of a route.
