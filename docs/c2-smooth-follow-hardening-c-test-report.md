# NPCortex — C-TN Smooth Follow Hardening C: Test Report

**Baseline:** `NPCortex_C-TN_PersistentTerrainNativeFollowController_C_FullRepository_CANDIDATE.zip` (last user-confirmed terrain recovery in Minecraft). This new ZIP is a separate implementation candidate, not Windows-accepted.

## Scope & implementation

| Phase | Implementation | Guard |
|---|---|---|
| S1 latency diagnostics | Per END-tick counters for MOVING, waiting, intentional HOLD, largest continuous wait; handoffs and hint hits/misses | Read-only, no C1 movement operation |
| S2 predictive continuation | One speculative path from exact committed C1 endpoint, computed while C1 moves (near WALK terminal or bounded mixed stage); consume only after verified physical receipt | Same token, exact support endpoint, target delta below 1.5 blocks, age <=100 ticks, no speculative motion; fresh C1 world safety checks before executing |
| S3 adaptive recovery/corridor stability | Keep chosen side across small target retargets (<6 blocks), reset on large relocation or normal corridor | Original fail-closed graph, fallback and recovery budgets unchanged |
| S4 regression/performance | New 31-assertion pure JVM suite; combined 20 suites and Windows checklist | Actual Forge and multi-NPC MSPT pending |

### Completed automated evidence

- 20/20 Pure Java suites PASS, **2,591 assertions** with `javac --release 17` / Java21 runtime. Compile to Java17 bytecode, **not** full Forge compilation.
- Backend C2 focused `python -m unittest discover -s tests -p 'test_c2*.py'` PASS, 16/16.
- Java17 syntax parse of 5 modified/new Java files PASS. Pure parser cannot verify Minecraft/Forge type linkage.
- One synthetic fixed-graph microbenchmark: 169 nodes / 1200 edges, 200 plans after warm-up, about 1.86 ms/plan on this container. Not a prediction of MSPT, frame timing, or 1/3/5/10 concurrent NPC behavior.
- Gradle wrapper attempted with `./gradlew build --offline --no-daemon` but cannot access required Gradle distribution (tries download); Forge compile/launch **BLOCKED** locally.

### Required Windows proof (not yet done)

1. `scripts/test-c2-smooth-follow-hardening-c.ps1` on the user's Temurin17 environment.
2. `scripts/dev-forge.ps1 -Task build` and `-Task runClient` (send `:compileJava` output if failing).
3. Compare baseline C-TN and this candidate on the same safe flat/3D fixtures: record `smoothWaitTicks`, `maxWaitTicks`, `ticketHandoffs`, `preparedHits`, `preplanProbes`; values are cumulative since `/npcortex c2 follow` and include all session behavior (HOLD counted separately).
4. Risk & stop safety: no player-sized collision clipping, unsafe falls, invalid mixed landing, stale C1 token execution or automatic replay after cancel/rejoin.
5. 1/3/5/10 NPC real Forge MSPT & plan latency; document any regression.

### Limitations and potential regressions

A speculative plan is only a hint. If player moves >=1.5 blocks from prepared position or plan expires, it is discarded, so there is no guaranteed hit rate. C1 still needs one server tick to claim the next checked motion and may legitimately stop to validate safety. A persistent session does not bypass C1 ticket max budgets. No CI/GitHub operations were performed.
