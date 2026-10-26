# NPCortex — E3-D G2 Guarded C2 Navigation: Implementation & Test Report

Date: 2026-10-23
Status: **CANDIDATE — Windows build/gameplay not yet accepted.**
Baseline: `NPCortex_GWI_CF3_E3D_G1_TypedMovePreview_FullRepository_CANDIDATE.zip` (445 entries; SHA/CRC validated before clean extraction). No GitHub operations.

## Scope executed

- Retained G1 preview, E1 FIFO, E2 read-only dispatcher, C1/C2 ownership, C-F1/C-F2 existing implementations.
- New Forge operator commands: `/npcortex debug cplus taskmove yui <x> <y> <z>`, `taskmovestatus yui`.
- New `CPlusE3G2Runtime` with bounded worker, server-thread actor/session/motion rechecks after HTTP, and tagged reserve/ack.
- Additive typed scheduler-backed `C2AgentRuntime.NavigationAdmission` wrapper: C2 still owns planning/physics and goal creation. This is not a second controller.
- Local authenticated backend routes `/v1/cplus/e3/reserve|report|status` and additive `cplus_e3_g2_moves` SQLite record with request/attempt/actor/world/session binding. No automatic replay.
- Explicit head-of-line protection: any pending E1 QUEUED/HOLD/active task prevents G2 reservation. E2 active read-only attempt prevents G2.
- One-node G2 move attempt is **not** yet multi-node E1 FIFO auto execution. Operator explicitly starts one movement; automatic DAG is E3-E.

## Correctness boundaries

- `RESERVED`: durable authorization only, no C2 motion claimed.
- `C2_ACCEPTED`: C2 scheduler accepted new typed goal ID; may still be awaiting C0 planning; **not proof of movement or arrival**.
- `HOLD`: uncertain/rejected/stale; no automatic retry or forced reset.
- `completion_verified` is always false and `motion_started` stays false in G2 durable projection. G3 must inspect authoritative C1/C2/C0 results before changing that.
- No World Mutation, Break/Place/Use/Pickup/Container, inventory changes, Qwen autonomous dispatch, claim adapter or `UNKNOWN -> ALLOW` escalation.
- **Important testing limitation:** until G3 reconciles C2 terminal receipt and clears a durable attempt safely, one G2 run per actor remains blocking even if C2 itself later finishes. Test on a disposable world, not valuable save. Do not delete database as a workaround.

## Tests run in this environment

| Group | Result |
|---|---|
| New E3-G2 + G1 Python tests | 22/22 PASS |
| E2 Python regression | 13/13 PASS |
| E1 Python regression | 11/11 PASS |
| C-F2 World Observation | 16/16 PASS |
| C-F1 bridge | 9/9 PASS |
| C-F0 contracts | 15/15 PASS |
| **Focused total** | **86/86 PASS** |
| Java 17 grammar parse (JDK21 compiler parser) | 4 touched files, 0 syntax errors; **not Forge compile** |
| ZIP integrity / clean extract | Checked at delivery packaging |
| Local Forge Compile | **BLOCKED** — Gradle wrapper needs unavailable gradle-8.8-bin.zip |
| Full backend suite | **PENDING** — trial did not finish within time window |
| Windows JDK17 Forge Build | **PENDING** |
| Windows Gameplay | **PENDING** |
| 1/3/5/10 real-NPC MSPT | **PENDING** |

## Design risks & gates

- Async `reserve` ACK loss may leave a G2 task `RESERVED`, and C2 success followed by report ACK loss may leave C2 moving while backend lacks trusted start receipt: always fail closed (no replay). G3 required for coordinated reconciliation.
- Concurrent Follow/C2 after original request but before backend reserve returns: Forge rechecks ownership on server thread and refuses.
- C2 local acceptance can precede actual C0 decision, local movement and backend result; no success claimed until G3.
- Snapshot permissions remain C-F2 UNKNOWN for mutation; local navigation is not authorization to break blocks.
- Full server stoppage and power-loss recovery, wrong-position lifecycle, dedicated server compatibility, latency and multi-NPC game stress are not proven.

## Instructions

Use clean extraction and see `docs/cplus-cf3-e3-d-g2-windows-gameplay-checklist.md`. Do **not** unzip over current worlds, production SQLite, secrets or configs. Commands separated for Build, Focused Test, Backend, Run Game.
