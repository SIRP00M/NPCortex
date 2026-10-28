# NPCortex — C2-P0 Precision Movement (Architecture C, approved fix candidate)

**Baseline:** C2-P0 Architecture C Event-Driven Embodied AI candidate ZIP.  
**Scope:** User-approved C — short-distance precision movement + per-goal configurable completion tolerance. This is NOT a new navigation/pathfinding sprint.

## Source audit

The previous `/npcortex c2 move` command rejected targets within 1.5 blocks (`C2AgentRuntime`) while the C0 goal allowed a 1.0-block radius, and C1 normal motion rejected sub-0.5-block travel. That left valid 0.5–1.5-block goals unable to move even when they were not yet achieved. The user observed Yui at X≈79 being unable to move to X=80 despite walking to X=90 and back without problem. The obstacle failure in a previous test was a separate and expected result.

## Implemented

- **C2-only precision movement:** `C1P1MotionRuntime.moveC2Precision` enters the SAME server-owned Vanilla `travel()` / session scheduler as normal C1 movement; it permits a bounded 0.10–8.0 block segment without weakening the operator-facing C1 0.5–8.0 minimum. `C2MoveGeometry` continues to cap single horizontal target displacement at 7.5 blocks and same-height at ±0.65 Y.
- **Tunable goal tolerance:** `/npcortex c2 move <name> <x> <y> <z> [tolerance]`. Default tolerance **0.35 blocks**; explicit limits **0.20–0.75 blocks**. It is a three-dimensional Euclidean radius and is evaluated using live server position. A target already inside tolerance returns `Already within C2 goal tolerance`, without creating a new Backend Goal/Action.
- **Contract alignment:** C2 goal `success_criteria.position_near.distance` and C2 local action verifier use the SAME chosen radius. The C0 action contract derives its matching `move_to` expected-effect radius from the validated Goal Criteria rather than unconditionally using 1.5 blocks. Unrelated C0/headless goals without a matching destination criterion retain their legacy 1.5-block fallback.
- **Safety remains:** Qwen-selected destination remains checked against operator-authorized exact coordinates (±0.05), with loaded chunk, same dimension, C1 single motion owner, lifecycle checks, fail-closed journal, no teleport, no force-loading, no multi-step pathfinding.

## Windows test commands (PowerShell)

```powershell
cd C:\path\to\NPCortex
.\scripts\test-c2-p0.ps1
.\scripts\dev-forge.ps1 -Task build
# Terminal 1: .\scripts\dev-backend.ps1
# Terminal 2: .\scripts\dev-forge.ps1 -Task runClient
```

In Minecraft: ensure Yui is on a flat loaded surface and inspect her position before each test.

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 move yui 80.0 69.0 -65.98
/npcortex c2 status yui
# Optional strict goal radius, 0.20 blocks:
/npcortex c2 move yui 79.6 69.0 -65.98 0.20
/npcortex c2 status yui
```

These coordinates reproduce the user-observed scenario only if Yui is at the corresponding nearby same-height location; ALWAYS substitute live coordinates. For precision, pick target **0.4–1.2 blocks from Yui** on an unobstructed same-height surface, then verify final position and `COMPLETED`/`verified=true`. For already-at-goal, give a target within 0.20 blocks of Yui and confirm no new Action is created. For rejection, test tolerance 0.19 / 0.76 (Brigadier should reject). For negative case, block the way and verify `FAILED`/`verified=false`. Re-run normal C1 move/jump/knockback/death/network regression, C2 blocked/cancel/rejoin and 1/3/5/10-NPC performance checks as applicable before full P0 Quality Gate.

**Known limits:** vanilla player movement is discrete per tick, so sub-block goals have practical accuracy limits; a strict 0.20 radius may fail when the avatar does not land sufficiently close. The code does NOT do micro-replanning, overshoot correction, obstacle routing, dynamic Y movement, or arbitrary-fine positioning. Observe actual Minecraft gameplay before accepting precision. Cross-runtime crash reconciliation, dedicated-server compatibility, and measured performance still need separate tests.

## Test report (local code candidate)

- Pure JVM target Java 17: 11 C1/C2 SelfTests, **1,224 assertions PASS** (`javac --release 17` executed on JDK 21 Linux host). Includes **42 C2 Geometry/Event/Scheduler** assertions and **33 C1 MotionPlan** assertions.
- Python Backend full `unittest` discovery: **193 tests PASS** (includes 8 C2 contract tests). Mock Ollama, not live AI.
- Gradle `./gradlew --offline compileJava`: **BLOCKED BEFORE COMPILATION** because `services.gradle.org` could not be resolved to fetch Gradle 8.8. No Forge compile success claim.
- Windows Forge build, actual live Qwen decision, collision gameplay, precision movement, multiplayer, performance: **NOT RUN** for this candidate.
- No GitHub operations performed. The user must complete Windows acceptance before calling the C2-P0 Quality Gate complete.
