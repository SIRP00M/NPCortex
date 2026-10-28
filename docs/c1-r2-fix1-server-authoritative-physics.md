# C1-R2 Fix #1 — Server-authoritative Combat Physics (Targeted B)

## Why this fix exists

The user's **real Windows Minecraft** log on 2026-10-15 contains `C1-R2 COMBAT_IMPULSE reason=VANILLA_KNOCKBACK` with nonzero horizontal velocity. The user reports that Zombie hits sometimes visibly move Yui, but Player sword and Knockback II hits deal damage **without moving the NPC**. `/npcortex c1 motion` recorded the *same server position* before/during/after the sword combat session. Thus the event/force is real; a robust server-authoritative movement step is missing for this clientless `ServerPlayer` in at least that route.

This is not a change to `Player.attack`, the knockback equation, Forge attack events, or the Vanilla damage cooldown. It does **not** fake force on every damage event.

## What changed

- During the existing R2 combat phase (server `END` tick), capture Velocity and position **before** `avatar.travel(Vec3.ZERO)`.
- Measure how far Vanilla `travel` actually moved the avatar. If horizontal knockback had meaningful incoming velocity but `travel` did not move horizontally and did not report `horizontalCollision`, apply that **same unmodified incoming X/Z velocity** through `avatar.move(MoverType.SELF, ...)`. Do the same for unconsumed upward knockback or airborne vertical movement, avoiding a second downward ground step. If Vanilla moved an axis or hit an obstacle, don't apply that axis again.
- Vanilla `Entity.move` performs the actual collision resolution; the fallback does not call teleport, `setPos`, a custom force formula, or a second Physics Tick.
- The pre-existing bounded Combat Session, falling checks, velocity packets, R0 Coordinator, R1 death and poof, P1 Inventory/UUID and P0 two-phase tracking are unchanged.
- A short `COMBAT_STEP` trace is logged for the first **three** ticks only. `collisionFallbackSteps` and `travelOnlySteps` are available in `/npcortex c1 combat` while the session is live.

**Evidence limitation:** This fix is an **implementation candidate**, not evidence that `Player.travel` is the sole cause or that the rendered knockback now works. The Windows Forge build and in-game tests must establish that. If server `actualDelta` changes but visual motion does not, stop and audit Client Entity Tracking. If `travelDelta` and fallback `actualDelta` stay zero, stop and check true collision/velocity mutation; do not add arbitrary teleportation.

## Windows runbook

1. Back up the test world; extract the full repository. Use JDK 17.
2. Run the isolated tests and compile:

```powershell
cd F:\AI\NPCortex
.\scripts\test-c1-r2-combat.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-r0-foundation.ps1
.\scripts\test-c1-r1-death.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

3. **Baseline:** flat unobstructed safe ground, a stationary Yui, no armor or knockback resistance. Stand where you can see the avatar. Run `/npcortex c1 motion yui`, `/npcortex c1 combat yui`. Make sure no Motion Session is active from earlier tests.
4. **Real player sword:** hit Yui once with an ordinary diamond sword, then observe visible movement; run `/npcortex c1 motion yui` and `/npcortex c1 combat yui` immediately. Inspect server `COMBAT_IMPULSE` and `C1-R2-FIX1 COMBAT_STEP` in `latest.log`. Require **nonzero `actualDelta`** over the early combat ticks and a real change in `pos`/visible location. Do **not** infer success just from `combat=IMPULSE`.
5. **Knockback II sword:** run Minecraft 1.20.1 command `/give @s minecraft:diamond_sword{Enchantments:[{id:"minecraft:knockback",lvl:2s}]} 1`. Wait for attack cooldown, repeat on flat ground. Verify visibly larger/appropriate knockback than a normal sword without imposing a numeric threshold absent actual game observation.
6. **Zombie regression:** let a Zombie hit Yui on safe ground. Verify original Mob knockback still works and does not create double displacement or unnatural launch.
7. **Moving NPC:** `/npcortex c1 move yui forward 8` then hit before movement ends. Must respond to the impact, avoid phasing and eventually resume/settle according to bounded session logic.
8. **Collision:** strike Yui toward a solid wall. Must never phase inside/through blocks; compare `horizontalCollision` trace. Test impact near an edge in a disposable world and verify the R1 fall-damage/death lifecycle stays correct.
9. **R1/R0/P1/P0 Regression:** `/gamerule keepInventory true`, give Yui 3 bread, kill, observe fall + poof, respawn, inspect Inventory and sync; Save & Quit/rejoin; verify existing UUID, rotation, spawn/repair. Repeat targeted cases for Yui/Hina/Ai. Do not infer multiplayer/load compatibility from this test.

Filtered trace in PowerShell:

```powershell
Get-Content F:\AI\NPCortex\forge-mod\run\logs\latest.log |
  Select-String 'C1-R2 COMBAT_IMPULSE|C1-R2-FIX1 COMBAT_STEP|motion_done' |
  Select-Object -Last 60
```

### Interpretation

- `travelDelta` nonzero: Vanilla `travel` moved the NPC; expected fallback `(0,0,0)` on that axis (no double-move).
- `travelDelta` zero + `fallback` nonzero + `actualDelta` nonzero: Vanilla collision-backed fallback moved the NPC. Verify client and persistence separately.
- `travelDelta` zero + `fallback` zero despite incoming force: inspect collision flags and guard thresholds.
- `fallback` nonzero + `actualDelta` zero: actual blocking collision or other server movement issue; do not declare a pass.

### Stop criteria

If the NPC clips a wall, falls through terrain, becomes stuck in `COMBAT_IMPULSE`, moves twice for one hit, loses saved inventory, duplicates a death drop, or becomes invisible after rejoin, **stop**, save the trace and send logs/screenshots for a source audit before any further fixes.

## Quality Gate and limitations

Local isolated Java regressions and focused Backend C0 tests passed; Gradle wrapper cannot fetch the distribution here. The source changes to Forge-owned types **have not compiled or run on Windows in this environment**; user Windows `build` + in-game acceptance is required. The existing architecture C remains in place. No R3 Portal or additional AI/mob-targeting functionality added.
