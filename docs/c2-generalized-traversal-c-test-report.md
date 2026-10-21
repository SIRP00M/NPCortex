# NPCortex — Generalized Block Traversal C: Test Report

**Date:** 2026-10-21  
**Status:** IMPLEMENTATION CANDIDATE — NOT GAMEPLAY VERIFIED  
**Baseline:** `NPCortex_C2-F2_AdaptiveFollow_B_FullRepository_CANDIDATE.zip`  
**Scope:** C1 Generalized Passability, C2 Independent Risk, C3 Risk-Aware Paths, C4 Follow/C2 Regression. No GitHub operations.

## Code changes

- `C2BlockTraversalWorld`: Minecraft server-world risk overlay for player-sized volumes. **All physical collision remains `level.noCollision(npc, aabb)`** with native `BlockState.getCollisionShape` for supports. No hardcoded passability whitelist; short grass, tall grass, ferns and flowers are accepted whenever vanilla player collision allows them.
- Risk tier `CLEAR` / `COSTLY` / `FORBIDDEN|UNKNOWN` kept separate from physical clearance. Soft: cobweb, berry bush, soul sand, honey block, and optional `npcortex:traversal_costly` datapack block tag. Hard: existing fluid, fire, cactus, magma, powder snow, campfire, wither rose exclusions and optional `npcortex:traversal_forbidden` tag. Unknown/unloaded remains fail-closed. This is a **conservative travel policy**; hard-hazard exclusions are intentional even if players can physically enter them.
- `C2NavGrid.Cell.riskPenalty` carries per-stand risk (including floor-level slowing effects). `C2TerrainObservation.Risk.AVOIDABLE` distinguishes traversable penalties from hazards. The existing 5-argument Cell constructor remains available.
- `C2TraversabilityGraph.build3DWithRisk`: introduces separately priced, directed edges. Every edge must still pass the old swept player collision proof. Risk penalty is the maximum of swept corridor and endpoint-floor evidence; diagonal no-corner-cut and directional 3D checks remain. The original `build` and `build3D` APIs retain zero-risk adapters for regression fixtures.
- `C2GraphWorldProbe`, `C2WaypointBridge`, `C1P1MotionRuntime`, `C2AgentRuntime`: read the same hard-hazard policy during planning, segment preflight and C1 Follow motion; COSTLY is allowed in physics/replanning without replacing C1 `ServerPlayer` motion ownership. Danger and unreadable terrain continue to reject.
- Traversal tags allow mod packs to add **recognized** slow/dangerous blocks with datapacks; modded block physics/unknown effects cannot be inferred from tags automatically.

## Automated validation executed in this environment

- **PASS**: Compiled with `javac --release 17` and ran on OpenJDK 21, 13 isolated pure-Java suites. Their logged assertion counts sum to **2,331** (including 23 new C tests). Tests cover weighted costs, weighted A* and hierarchical agreement, rejection of forbidden edges, stale snapshot bounds and prior Follow/Navigation/Terrain contracts.
- **BLOCKED**: Actual Forge Gradle `compileJava` attempted, but wrapper cannot download Gradle 8.8 (`java.net.UnknownHostException: services.gradle.org`). Not a Forge build pass; no actual Forge class loading or Java17 runtime acceptance was performed.
- **NOT RUN**: Full Python backend suite (backend unchanged); complete Forge/Windows gameplay, Minecraft collision/AI parity, datapack mod compatibility, 1/3/5/10 concurrent NPC MSPT, world saves, cancellation/combat stress, modified/unloaded chunks. No performance conclusions can be made from isolated JVM timings.

## Known behavior / safety boundaries

1. Support surfaces still require existing full/verified stair footprint; this is **not** generalized new movement through doors, ladders, fluids, or over arbitrary gaps.
2. `C2BlockTraversalWorld` recognizes standard vanilla hazards and datapack-tagged modded hazards. Unknown modded status effects remain unclassified unless explicitly tagged; collision is still authoritative for passability, not for damage/safety.
3. The current Follow Controller remains route/ticket-based. This change does not replace it with `PathfinderMob`, teleporting or arbitrary live-ticket retargeting. Naturalness improvements from Adaptive Follow B still require Windows confirmation.
4. Risk/collision safety is snapshot-based and is rechecked at route dispatch/tick points; rapidly changing terrain and modded effects require real gameplay regression.
5. Default C1 Physics, combat impulse, packet tracking and transactional cancellation code are otherwise preserved; **not yet verified on real Forge runtime**.

## Repro commands (Windows PowerShell, Temurin 17)

```powershell
.\scripts\test-c2-generalized-traversal-c.ps1
.\scripts\test-c2-f2-adaptive-follow-b.ps1
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Complete the in-game fixtures from `docs/c2-generalized-traversal-c-windows-checklist.md`. Record PASS/FAIL per fixture, MSPT, and any exception/telemetry. No user-confirmed acceptance is asserted here.
