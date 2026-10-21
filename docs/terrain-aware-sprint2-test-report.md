# Sprint 2 — Candidate implementation test report

Architecture C: Verified Vanilla 3D Motion Primitives. Base: Sprint 1 Full Repository Candidate. **Test observations must not be extrapolated into Forge Physics acceptance.**

- Pure Java 17 `C1S2TraversalPlanSelfTest`: 64 assertions PASS (type bounds, elevation and X/Z validation, grounded evidence, jump/fall airborne evidence, cancel, combat interruption and malformed arguments).
- Selected isolated Java regression: 2,371 unique assertions including Sprint 2 contract (no duplicated suites); PASS. Test types: Terrain geometry, P1-D replanning, P1-C waypoint and cancellation, B3/B2/B1 planner, P1-A navscan. These are pure Java simulations, **not Forge MSPT**.
- Backend full regression: 201 tests OK (36.248 s in final verbose run). No Backend source code changed.
- Forge Gradle compile in sandbox: NOT VERIFIED (external Gradle dependencies unavailable); Windows verification required.
- Minecraft runtime/Vanilla motion tests: NOT VERIFIED — use `terrain-aware-sprint2-windows-checklist.md`.
- Performance: no TPS/MSPT claims. Multi-NPC performance remains PENDING.

`/npcortex c1 traverse` is **operator-only** and does not prove `/npcortex c2 navigate` can traverse elevation. That graph/execution integration is explicitly Sprint 3.
