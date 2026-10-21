# C2-F1 Reliable Follow Player — Candidate Test Report

Baseline: NPCortex Terrain-Aware 3D Sprint 4 Fix #1 Candidate.

## Implementation summary

Follow Session and audit-only SavedData journal, safe C2/Follow ownership exclusion, bounded hysteresis and moving-target planning ring, exact C1 token correlation, no teleport or C0 action spam, no auto-replay after restart, operator commands `follow/unfollow/followstatus` and `cancel` alias.

## Tests actually executed in isolated development environment

- Java-target 17 compile + run **26 pure-JVM SelfTest classes / 4,923 assertions PASS**, including new Follow Policy 188 + Follow-ring Planner 29 assertions. Includes older C1 Motion/Combat/Lifecycle, Terrain, B2/B3, C2 cancellation, P1-D, S3 and S4 regressions. (Run on Java 21 runtime with `javac --release 17`, not JDK 17 runtime.)
- Forge-facing Java runtime/waypoint bridge and command tree **syntax/contract mock compile PASS** using locally generated mock API types. This is not a substitute for ForgeGradle compile or runtime behavior.
- Backend unchanged: full Python unittest discovery **201 tests / OK (36.593s)**. Backend test harness prints expected mock negative-test error traces, not failing tests.
- ForgeGradle real compilation **NOT VERIFIED**: Gradle 8.8 distribution could not download (`UnknownHostException: services.gradle.org`) before compiler invocation.
- Windows Forge Build, Minecraft following behavior, actual target tracking, multiplayer logout/dimension, Save/Quit/rejoin, crash, C0 journal coexistence and real 1/3/5/10 NPC MSPT **NOT TESTED**.

## Scope guarantee

No Backend schema/tool, C1 Vanilla physics, global multi-chunk navigation, teleport, Qwen natural language, survival skill, or GitHub operations were introduced. Retain one `CHANGELOG.md` and one `PENDING_TESTS.txt`.
