# C2 — Generalized Block Traversal C: Windows Gameplay Acceptance

## Preconditions

- Use the **new** Candidate ZIP. Start backend only if required for the C2 mode you are testing. Use Java 17/Forge 1.20.1. Back up the Minecraft world.
- Recommended fixtures: broad flat stone/grass floor with a short-grass patch; a tall-grass/fern/flower patch; a cobweb and berry-bush patch with an open detour; a full-height stone wall; stairs/slabs/platforms; loaded/unloaded chunk boundary. Keep all fixtures within local NavGrid radius 6 as appropriate.
- Use **support-block** Y in `terraininspect`, i.e., the solid floor underneath feet: if the floor is at Y=68 and the grass is at Y=69, pass Y=68. Exact coordinates below are illustrative, not fixed world coordinates.

## PowerShell gates (before Minecraft)

```powershell
.\scripts\test-c2-generalized-traversal-c.ps1
.\scripts\test-c2-f2-adaptive-follow-b.ps1
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

## Game commands

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 navscan yui 6
/npcortex c2 terraininspect yui 80 68 -62
/npcortex c2 navgraph yui 6
/npcortex c2 navplan yui 82 68 -62
/npcortex c2 follow yui YourMinecraftName
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c2 unfollow yui
```

Replace the sample positions and player name. For the exact walk goal use its **support-block** Y. Avoid calling read-only terraininspect/navgraph while active motion owns the actor; stop Follow first.

## Acceptance fixtures — fill PASS / FAIL / NOT TESTED

| ID | Fixture | Expected result | Result |
|---|---|---|---|
| C-01 | Short Grass on full flat blocks | `terraininspect WALKABLE`, `risk=NONE`, riskPenalty=0; `follow` goes through without unnecessary detour | NOT TESTED |
| C-02 | Tall Grass, fern, flowers, dead bush on flat support | Same collision parity and direct crossing (no special allowlist) | NOT TESTED |
| C-03 | Modded noncolliding decorative plants | Walk through according to native collision, without claiming modded hazard detection | NOT TESTED |
| C-04 | Cobweb with safe alternate aisle | `terraininspect` at cobweb-bearing **floor** shows `WALKABLE` + `risk=AVOIDABLE`, positive `riskPenalty`; plan favors clear aisle | NOT TESTED |
| C-05 | Sweet Berry Bush with alternate aisle | Same risk-aware detour; not treated as solid stone wall | NOT TESTED |
| C-06 | Soft hazard is only available physical route | A route may be planned but slow/damage implications are reported; physical player behavior must be observed and safety monitored | NOT TESTED |
| C-07 | Soul Sand underfoot vs normal floor | Surface risk penalty positive; prefer normal floor where reasonably available | NOT TESTED |
| C-08 | Full stone wall / corner | No through-wall graph edge; detour only if valid | NOT TESTED |
| C-09 | Fire/lava/cactus/wither rose | Forbidden hazard never routed through, even when physically passable for some blocks | NOT TESTED |
| C-10 | Out-of-scan or unloaded chunk | No force load, false path claim, crash, or blind move | NOT TESTED |
| C-11 | Stair, slab, STEP_UP / SAFE_DESCEND | Previously verified physical traversal remains; no unsafe elevation shortcut | NOT TESTED |
| C-12 | Modify grass -> wall during Follow | Fresh clearance prevents walking through new wall; replans or fails closed | NOT TESTED |
| C-13 | Pause/unfollow and C2 cancel during motion | No stale C1 ticket/owner or unintended resumed movement | NOT TESTED |
| C-14 | Save & Quit/rejoin, damage/knockback | No lifecycle/combat physics/identity regression | NOT TESTED |
| C-15 | 1, 3, 5 and 10 NPCs | Record server MSPT, navgraph elapsedMs, movement/follow quality, no cross-NPC interference | NOT TESTED |
| C-16 | Custom datapack tags with modded blocks | Opt-in costly/forbidden policy honored without incorrectly changing Minecraft collision | NOT TESTED |

### Debug capture

- During the issue, grab `/npcortex c2 followstatus yui` and `/npcortex c1 motion yui` **before moving closer**. On an idle/unfollowed NPC, inspect terrain at the offending spot with `/npcortex c2 terraininspect yui <x> <supportY> <z>`.
- Capture server `WALK_TELEMETRY`, status `phase`, `failures`, `avoided`, `waypoint`, `motion` and any `blocked` reason.
- For C-15 compare before/after using the same world and fixtures. Record measured MSPT rather than inferring it from pure Java elapsedMs.

## Modded hazard tags

A datapack can extend `data/npcortex/tags/blocks/traversal_costly.json` (soft penalty) or `traversal_forbidden.json` (hard avoidance) with mod block IDs. **Tags affect route risk only and never override player collision**. World reload/rejoin behavior must be tested. A modded block with damage/effects but no tag cannot be assumed automatically safe.

## Acceptance status

**PENDING.** Do not mark actual Forge build, in-game collision parity or performance PASS without user-provided evidence.
