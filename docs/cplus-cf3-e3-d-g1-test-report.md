# NPCortex C-F3 Option E — E3-D G1 Test Report

**Baseline:** E1-E Gate 2 Full Repository Candidate, SHA-256 `dc320c8113bad8f45bd4761d89205adf262cbe6dc66241643ffbc6bdf5819831` (clean extraction).

**Decision:** E3-D before E3-E. Scope here is **G1 only**. An operator may request a typed local `move_to` PREVIEW. No live navigation, command-to-C2 dispatch, task FIFO movement admission, world mutation or Qwen auto-execution.

## Implementation

- Forge: new `taskmovepreview` operator command accepts bounded integer target XYZ; validates live/grounded actor, session via C-F2 snapshot, C1 motion receipt, C2/Follow busy state, unresolved C2 journal and loaded/local target; all on Minecraft server thread.
- HTTP: reuses bounded single-worker local-auth route of C+ coordinator; strictly correlates response's requester/request/task/attempt/world/actor/dimension/session/observation/target and verifies `PREVIEW_ONLY`, `can_execute=false`, `motion_started=false`.
- Python: `cplus_e3_preview.py` validates exactly the expected typed payload and stores immutable preview identity in an additive SQLite table. It is deliberately isolated from E1 FIFO/E2 leases, never used as authority to move.
- Backward compatibility: preserves production C1/C2 motion/follow/inventory, C-F1/F2 and E1/E2 implementations; E3 changes limited to opt-in command, coordinator preview handler and new backend route/module/tests/docs.

## Tests verified in local environment

| Group | Result | Evidence |
| --- | --- | --- |
| E3 G1 Python unit/HTTP auth | 9/9 PASS | `python -m unittest discover -s backend/tests -p 'test_cplus_e3*.py' -q` |
| E-P0/E1/E2 plus E3 suites | 43/43 PASS | `-p 'test_cplus_e*.py'` |
| C-F0 contracts | 15/15 PASS | `test_cplus_f0_contracts.py` |
| C-F1 bridge | 9/9 PASS | `test_cplus_f1_bridge.py` |
| C-F2 observation | 16/16 PASS | `test_cplus_f2_observation.py` |
| **Unique focused total** | **83/83 PASS** | No errors in selected tests |
| Full backend suite | PENDING | Did not complete within execution timeout; no PASS claim |
| Forge compile | PENDING | Gradle Wrapper cannot download gradle-8.8 distribution in this environment; not equivalent to Java compilation PASS |
| Windows Minecraft G1 | PENDING | Requires user gameplay test |

## Safety/limitations

G1 does not verify a traversable path, guarantee C2 admission, or confirm a completed move. Target loaded-range checks prevent unsupported remote preview, but there is no new planner. The Preview SQLite table is *not* the FIFO task queue. G2 must design the task-to-C2 connection with separate C2 owner acknowledgement and exclusive motion fencing; G3 must verify terminal C0+C1+C2 results and durable cancellation. G1 records are never replayed as live actions.

All earlier E2 fault/performance gates still PENDING unless independently tested. No GitHub operations.
