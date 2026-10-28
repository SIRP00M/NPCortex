# Build Fix #1 (2026-10-21) — Minimal Compile Scope Repair, Option A

**Baseline:** this report's original C-S1–C-S4 Persistent Follow C Candidate. A real Windows JDK17 Forge build of that original candidate failed at `C2FollowRuntime.java:223`, unable to resolve `extended`. This section documents the narrowly approved repair; the original implementation report follows below unchanged.

**Cause:** `boolean extended` was declared inside an `if` and referenced by a later sibling `if`, where it was out of lexical scope.

**Source delta (only `C2FollowRuntime.java`):** declare `boolean extended=false` immediately before the existing first extension conditional; replace the declaration inside that conditional with `extended=C2WaypointBridge.extendFollowWalkChain(...)`. No other runtime code, feature logic, conditions or ownership code modified.

| Check | Result | Interpretation |
|---|---|---|
| Source diff check | **PASS** | Exactly two line-level source edits; doc updates only otherwise. |
| Pure-Java Regression (`javac --release 17`) | **PASS** | 17/17 suites, 2,490 assertions, JDK21 targeting Java17; Forge-bound `C2FollowRuntime` excluded. |
| Focused backend C2 | **PASS** | 16/16 tests; no backend changes. |
| Forge 1.20.1 `compileJava` of **patched** ZIP | **PENDING** | Forge Gradle 8.8 dependencies unavailable locally; the user must rebuild with JDK17 on Windows. |
| Windows Minecraft Gameplay | **PENDING** | No post-fix gameplay evidence yet. |
| Concurrent 1/3/5/10 NPC MSPT | **PENDING** | No real Forge metrics yet. |

**Windows retest** (from `F:\AI\NPCortex`, after replacing with the patched repository):

```powershell
.\scripts\test-c2-persistent-follow-c.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Once `:compileJava` passes, test `c2 follow`, motion continuity, `persistentAppends`, cancellation and hazard/3D regression as documented in `docs/c2-persistent-follow-session-c-windows-checklist.md`. Leave all Forge/gameplay quality gates pending until verified. The old `ModLoadingContext.get()` removal warning is unchanged and is not this compile error. No GitHub operations performed.

---

# NPCortex C-S1–C-S4 — Persistent Follow Motion Session C: Test Report

Date: 2026-10-21. Baseline: B+ H1–H4 Full Repository Candidate. Scope approved explicitly; NO GitHub operations.

## Source changes and key invariants

- **C-S1 Persistent same-token window:** C2 Follow Session remains ephemeral/in memory. Flat C1 Continuous WALK may be extended from its validated endpoint without terminating/restarting the C1 owner. The bounded C1 window is now **600 ticks, 64 waypoints and 96 blocks of cumulative approved edges**; extensions are refused in the final 80 ticks to allow a clean verified terminal handoff; C2 still enforces its own 380-tick no-extension segment watchdog (reset **only** after a successful verified extension), 800-tick route watchdog, failure budget and physical receipts. A new ticket is still necessary at window exhaustion or a typed 3D transition. This is NOT an unbounded motion permit.
- **C-S2 Live rolling continuation:** The next planner runs at most once globally per server tick, and a given Follow Session retries an extension at most once per **10 ticks**. It plans from the existing **committed endpoint** (which must lie in a freshly captured, loaded local graph). Only WALK, same-Y, adjacent/short smoothed edges are eligible. The additional links are independently graph-certified (including support/risk) and swept with the live player-sized collision/hazard probe. C1 token must still match and be active, non-combat; C1 extension is committed before C2 extends its receipt target, all on the server thread. If any check fails, the current C1 movement is unchanged; terminal handoff/replanning remains the safe fallback. No C0 ownership or new entity/physics owner.
- **C-S3 Zero-forward preflight:** Nonmoving heading alignment does not probe a speculative .4-block forward travel vector; residual horizontal velocity is cleared and `Player.travel(Vec3.ZERO)` still ticks Vanilla gravity/physics. Every **nonzero** forward input still receives its prior loaded-chunk/world-border/terrain/swept collision validation. Typed Jump/Step/Descend logic unchanged. Vanilla's existing yaw/head/body presentation is retained.
- **C-S4 Safety / diagnostics:** C2 `followstatus` reports `persistentAppends` and `rollingMisses`. C1 token correlation, foreign owner rejection, combat priority, terminal receipt, distance-gated pause, chunk/load fence, no Save/Quit autoreplay and server shutdown cleanup remain. A same-route lookahead extension is accounted for separately from newly planned route appends; no false Gameplay PASS.

## Actual test evidence

| Check | Result | Scope / limitations |
|---|---|---|
| Pure-Java `javac --release 17` | **PASS** | 17/17 isolated suites, **2,490 assertions**; executed with JDK 21 targeting Java 17 bytecode. |
| New C-S1–C-S4 self-test | **PASS** | 68 assertions: dynamic route append proof/receipt/cancel and 96-block bounded lease, token fencing, zero-forward policy, rejected graph corridor, policy throttle/failures. Not Forge integration. |
| Prior B+ and C2 Java regression | **PASS** | 16/16 suites, 2,422 assertions. |
| Backend C2 focused | **PASS** | `python -m unittest discover -s backend/tests -p 'test_c2_*.py' -q` — 16 tests, OK. Backend unchanged. |
| Forge Gradle 8.8 build | **BLOCKED / NOT VERIFIED** | Missing Gradle distribution; Gradle wrapper HTTPS download fails in sandbox. Must compile/run on Windows with JDK17. Forge API compatibility is **not certified**. |
| Forge Windows real gameplay | **PENDING** | Follow continuity, live appends, zero-forward recovery, 3D/cancel/combat, 1/3/5/10 NPC metrics, client smoothness. |
| Full backend suite | **NOT RERUN** | Only focused tests above verified for this Candidate. |

## Explicit limitations / risks

- Continuations only happen when the current C1 walk has **1–2 queued targets left**, the committed endpoint is within a *fresh loaded* local snapshot, the target is still beyond the follow-ring resume threshold from that endpoint, the verified continuation is flat and the C1 bounded motion window has capacity. Terrain transitions, unreachable goals, moving obstacles or max budget can still cause a visible safe pause.
- Future-node proof must be live in Forge: pure Java suite cannot establish real `ServerPlayer.travel`, `noCollision`, modded block hazard semantics, ordering of server END ticks, network rotation, MSPT or tracking. A direct Java compile is not a substitute for Forge compilation.
- C1 `stopHorizontalVelocity` is **preserved** at actual terminal/cancel; this change does not allow unowned inertia or cancellation bypass.
- `PENDING_TESTS.txt` is the authoritative outstanding checklist; no quality-gate completion inferred from source-level changes.
