# NPCortex C2-F2 Fix #2 — Test Report

**Development Candidate; NOT a complete quality gate.** Baseline: C2-F2 Fix #1 Full Repository Candidate.

## Executed locally

| Suite | Outcome | Evidence |
|---|---|---|
| Pure Java regression | **PASS — 29 self-test classes / 5,068 assertions** | Local javac `--release 17` on JDK 21; output reproduced by individually running all 29 Java self-test mains. Includes 42 new Fix #2 assertions. |
| New F2 telemetry/look-ahead/extension | **PASS — 42 assertions** | `C2F2MotionSmoothingFix2SelfTest`, including wrong-token, changed graph edge, no stale extension, 10 independent actor sessions. |
| Follow Runtime / Waypoint Bridge mock API compilation | **PASS (MOCK ONLY)** | javac `--release 17`; stubbed Minecraft/Forge types for type checking (not equivalent to real Forge). |
| Backend unchanged full Python unittest suite | **PASS — 201 tests in 37.103 seconds** | Rerun with verbose unittest runner; previous redirects timed out, then verbose run reached `Ran 201 ... OK`. Backend source unmodified. |
| Gradle Forge actual Build | **BLOCKED / NOT VERIFIED** | Wrapper attempts to download Gradle 8.8, returns `java.net.UnknownHostException: services.gradle.org`; source Forge compile not reached. |
| Windows gameplay, visual smoothness, MSPT | **PENDING** | User must run candidate on actual Windows Forge 1.20.1 world. |

## Known limitations

- Within an already verified flat route, WALK handoff can extend same C1 ticket. Independent local follow routes still complete and can create a short stop; do not claim continuous following without visual evidence.
- Controller telemetry is server-side and neither confirms client frame interpolation nor path-following quality on its own.
- Real combat/knockback, low-clearance diagonals, dynamic terrain, long follow, 1/3/5/10 NPC concurrent MSPT, save/rejoin/crash and offline cases are pending.
- Changed backend contract and Java17 Forge build are not demonstrated by this mock test. Windows owner must run them.

## Scope

No GitHub operations, no backend/schema edits, no C1 Vanilla physics replacement, no PathfinderMob or multi-chunk global following, no Qwen/interaction/mining/building additions.
