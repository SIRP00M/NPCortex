# NPCortex — C2-F2 Turn-Aware Path Planning + Safe Smoothing B

**Candidate date:** 2026-10-21  
**Baseline:** `NPCortex_C2_GeneralizedBlockTraversal_C_FullRepository_CANDIDATE.zip` (provided by user)  
**Scope:** Follow-only turn-aware A* and verifiable any-angle flat-WALK smoothing, including necessary bridge safety-contract extension and isolated tests. Static-goal operator Navigation/Hierarchical A* is not replaced.

## What changed

- New `C2TurnAwarePlanner.java`: bounded, deterministic Follow-only A* over `(graph node, incoming direction)`, adding a small non-negative angular penalty on top of the **existing directed physical and hazard edge cost**. Fixed 3000-expansion ceiling **per candidate plan**; Follow still caps goal candidates at 4 and global planning batch at 1/tick. Separate `FOLLOW_TURN_AWARE` source; no claim to replace exact static Navigation hierarchy.
- `C2FollowPlanner.java`: Follow ring and rolling goal candidates use Turn-Aware route selection; zero-step HOLD and avoidance/preferred-goal policy retained.
- `C2F2SafeShortcut.java`: bounded, conservative **flat, same-height, player-standing support** certificate for any-angle shortcut. All grid cells inside the connecting rectangle must exist at the same full approved standing level, with safe bidirectional cardinal edges and **no added risk cost**. MAX 3 cells on each axis.
- `C2F2Locomotion.java`: Follow converts A* zigzag segments to greedy safe any-angle shortcuts where the proof passes, otherwise keeps original adjacent route. No vertical transition is skipped.
- `C2WaypointExecution.java`: Follow-only any-angle WALK is accepted only in bounded form; every shortcut is **re-proven against a fresh directional graph** at each ticket start/extension. Legacy C2 navigation still restricts itself to its previous semantics.
- `C2FollowRuntime.java`: injects the live, observed graph into safe smoother; **does not bypass** `C2WaypointBridge`.
- `C2WaypointBridge.java`: unchanged. Before actual C1 action it retains player-sized *swept world collision + hazard* validation (including from the NPC's actual position), loaded chunk checks and exclusive C1 ticket verification.
- Updated standalone legacy Java test scripts to add the new pure-Java dependency; new `scripts/test-c2-turn-aware-b.ps1`.

## Observed tests (local container)

- `javac --release 17`: compiled 21 pure-Java classes and 14 self-test classes using installed JDK 21 **targeting Java 17** (not Temurin 17 runtime). **PASS**.
- **14/14 pure-Java suites PASS — 2,352 assertions**, comprising 2,331 preserved baseline assertions and 21 new turn-aware/smoothing assertions.
- New assertions cover turn penalty order, bounded planner, real graph edge cost, risk-aware priority, pure-Java safe any-angle reduction, false-proof rejection on changed/unsupported terrain, rejecting new soft hazards, zero-step HOLD, unsafe target and pure static A* unchanged.
- ZIP integrity verification: listed separately when packaging completes.

## Not certified / PENDING

1. **Forge Gradle compile: BLOCKED**. `forge-mod/gradlew --version` attempted to download `https://services.gradle.org/distributions/gradle-8.8-bin.zip` but the environment returned `java.net.UnknownHostException: services.gradle.org`. This is **not** a Forge build PASS.
2. **Actual gameplay**: Walking on 20+ block flat grass, zigzag and 90° corners, near walls and holes, slabs/stairs/jump/descent, unpredictable moving-target retargeting, cancel/combat/cross-dimension/rejoin; needs Windows Minecraft 1.20.1 + Forge.
3. **1/3/5/10 NPC** Forge tick-time and stability benchmarks, including bounded planning under target movement.
4. **Modded-block collision/risk** compatibility and live hazard changes (not covered by pure-Java graph fixtures).
5. Existing Navigation Full Quality Gate from parent PENDING_TESTS.txt remains outstanding unless explicitly confirmed by user.

## Engineering limitations

- The pure-Java shortcut proof is deliberately conservative: walls/hazards even in the shortcut's bounding rectangle can prevent smoothing, so it **does not promise a universally straight-looking path** around all obstacles.
- Only Follow is turn-aware; normal C2 static Navigation remains unchanged.
- Continuous steering still targets verified waypoints (no unverified corner blending). Target replan timing is unchanged, so zigzag caused by repeated target relocation may remain in some gameplay cases.
- *No Git operations performed.* User owns Minecraft Gameplay Acceptance and release/push decisions.
