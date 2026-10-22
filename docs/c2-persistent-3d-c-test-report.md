# NPCortex C-3D — Fully Integrated Persistent 3D Follow (Candidate)

Date: 2026-10-21. Baseline: **Persistent Follow C Build Fix #1 A** Full Repository ZIP, the latest user-tested build. User authorized Option C after reporting long pauses near elevations and obstructions. **Do not change GitHub.**

## Scope implemented in this Candidate

- Introduced `C1Persistent3DChain`, a **bounded in-memory mixed-stage lease** consisting of directed WALK, STEP_UP, STEP_DOWN, JUMP_UP and SAFE_DESCEND links. Maximum six stages and 420 ticks per C1 lease; C2’s 380-tick segment watchdog still provides an earlier fail-closed bound.
- C2 `beginFollowMixed3DChain` tries a mixed C1 ticket when the already-planned Follow route includes an elevation edge and two or more stages. Require a fresh loaded local graph, matching physical source node, and proof for *every* directed typed edge. If the mixed route cannot be proven/started, the original standalone WALK/vertical C1 execution remains available. No change to normal `/npcortex c2 navigate`.
- C1 rotates toward typed traversal headings by bounded 18° increments while retaining the same token; when aligned, it **revalidates live terrain again** before applying travel or Vanilla jump. This avoids a newly introduced instant 180° snap.
- C1 `moveC2Persistent3D` arms exactly **one C1 motion owner/token** and rechecks graph, dynamic collision, chunk, support, landing and headroom for *each* stage before arming its Vanilla `Player.travel()` movement / `jumpFromGround()`. No invented physics, teleport or path success; no separate controller touching movement.
- At each stage, retain the same C1 token **only after actual verified grounded progress**. Vertical stages use the existing `C1S2TraversalPlan.verify()` plus live safe landing check; any mismatch/changed terrain exits with non-success. Final route receipt is `MIXED_COMPLETE` only after every stage succeeds and the final avatar pose is checked by C2's normal receipt logic.
- Explicit cancellation returns `CANCELLED`, even mid-sequence, so C2 will not mistake it for an ordinary replan failure. Combat phase remains the exclusive highest-priority physics owner. The per-stage watchdog and total C2 watchdog remain bounded; no post-rejoin auto-resume. C1 read-only `motion` status adds `mixedStage=N/M kind=...`, C2 `followstatus` adds `mixed3DStarts`.
- This is **not** a new general navigation action, a modded traversal solver, a freeform jump planner, or authorization to skip a dangerous transition. Mixed route start can be refused if the graph is out of local range, actor is off-center, path changes, or a primitive exceeds C1’s bounds. Fallback may still visibly pause.

## Test evidence (as performed in this environment)

| Test | Status | Notes |
|---|---|---|
| Java `javac --release 17` | PASS | 18 isolated pure-Java suites, **2,524 assertions** (17 existing / 2,490 plus 34 new). Does not compile Forge-bound classes. |
| New mixed-chain contract tests | PASS | Bounds, connected edges, WALK→JUMP→WALK→DESCEND sequence, unchanged token, invalid-token rejection, no false success on failure. |
| Java 17 syntax parse | PASS | Five changed/new Forge-side Java files parsed with Java 17 javac parser. Not a Forge compile. |
| Focused Python backend C2 tests | PASS | 16/16; no backend changes. |
| Forge Gradle Build | BLOCKED / PENDING | Gradle wrapper cannot resolve `services.gradle.org` for Gradle 8.8. Windows/JDK17 `compileJava` and `build` REQUIRED. |
| Real Minecraft Player.travel / Jump / Collision | PENDING | No Forge gameplay environment available here. **Do not claim Gameplay PASS**. |
| Real cancel midair, combat, dynamic hazard, multiplayer 1/3/5/10 NPC MSPT | PENDING | Must test on Windows. |

## Risk review and acceptance requirements

- Mixed leases still perform one genuine C1 travel step per tick. The transition to the next primitive occurs only at a grounded, verified physical receipt, not during an airborne estimate.
- Source audit indicates that the mixed contract introduces C1 stage transitions and therefore is **higher risk** than the previous Walk-only session. Pure Java tests do not exercise Forge `ServerPlayer`, `level.noCollision`, the order of `onServerTick` physics, live chunk changes, or world lifecycle. If any of those fail, mark FAIL and do not push.
- Preserve all outstanding tests in `PENDING_TESTS.txt`. Passing a short flat-follow test does not prove mixed terrain, cancel, or 10 NPC throughput.
- No GitHub operations performed.
