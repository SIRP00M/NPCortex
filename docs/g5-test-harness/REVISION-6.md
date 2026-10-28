# G5 test harness — Revision 6 (development verification)

Revision 6 adds bounded cancellation coverage, clearer movement diagnostics, and more conservative result collection. It is **test tooling** and does not expand NPC navigation or grant permission to change Minecraft blocks.

## Changes

- **Cancellation (`p10`):** a per-tick watch attempts at most five cancels while the NPC remains sufficiently far from the target, with explicit timeouts and cleanup. A cancel is not automatically proof that it happened during `LEG_QUIESCING`.
- **Result diagnostics (`p08`):** one read-only C2 diagnostic is written when a long-running action ends; another captures the state when cancellation is requested. Production movement decisions are unchanged.
- **Save/rejoin (`p11`):** the collector chooses the nearest appropriately correlated snapshot to the checkpoint rather than the first snapshot in the time window.
- **Guarded movement (`p18`):** successful navigation is not enough to accept the task; explicit operator finalization, status and matching durable terminal evidence are required.
- **Collector:** improves Windows file sharing, incomplete-log handling, cancellation and recovery evidence, result provenance and suspicious timing flagging. Performance remains `MEASURED_NOT_ACCEPTED` without a suitable baseline/repeated profiling.
- **TAB names:** a scoped server-side Forge TAB display-name formatter uses the logical NPC name without changing the saved GameProfile identity. The rendering still requires in-game confirmation.

## What tests do not prove

The previous Windows single-player G5 run reported 18 passed, 3 pending, 1 unverified and 0 failed scenarios (22 total). The 1/3/5/10 NPC functional lanes passed in that environment, but the full milestone and MSPT baseline were **not accepted**. Revision 6 is a new test harness; old evidence does not imply its cases now pass.

`p08` requires a matching diagnostic failure reason; `p10` requires actual evidence of cancellation during `LEG_QUIESCING`; `p11` requires a mid-goal save/quit snapshot; `p18` requires the operator's G3 finalize/ACK and matching durable terminal. None can be accepted from a static test alone.

## Offline verification

Run the relevant G5 PowerShell build, collector, gate, isolation and validation scripts on Windows. See [G5 harness guide](README.md) and [Revision 5 history](REVISION-5.md). Always use a copied Minecraft world for live acceptance tests.

This guide intentionally excludes raw run logs, local databases, saved worlds, personal paths, and operator-specific test records.
