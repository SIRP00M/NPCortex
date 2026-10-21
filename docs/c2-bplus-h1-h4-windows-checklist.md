# NPCortex B+ H1–H4 — Windows Minecraft 1.20.1 Forge Gameplay Checklist

Build using Temurin 17 and run Forge client. Report `PASS`, `FAIL`, or `NOT TESTED` individually; screenshots/videos + timestamped logs help distinguish path/steering/replan.

## Setup

PowerShell from the repository root:

```powershell
.\scripts\test-c2-bplus-adaptive-steering.ps1
.\scripts\test-c1-full-locomotion-c.ps1
.\scripts\test-c2-turn-aware-b.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

For singleplayer Minecraft (replace `<YourName>` with your actual player name):

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui <YourName>
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
```

To stop safely:

```mcfunction
/npcortex c2 unfollow yui
```

## Must-pass acceptance cases

| ID | Fixture / action | Expectation | Actual |
|---|---|---|---|
| H1-01 | Player stationary 8–10 blocks away on flat stone, Yui follows | Moves along simple direct diagonal, no repeated left-right target switching | NOT TESTED |
| H1-02 | Player walks diagonally in both X and Z for 25 blocks | Turns smoothly in arbitrary 360° heading, no alternating grid zigzag on clear floor | NOT TESTED |
| H1-03 | Player walks straight, pauses, reverses direction | Follow-ring hysteresis respected; no rapid target flip or unintended contact | NOT TESTED |
| H1-04 | Rapid target movement > local radius, staying in loaded area | Bounded rolling replan, max 4 candidates; no force-loaded chunks/teleport | NOT TESTED |
| H2-01 | Flat open diagonal with flower/short/tall grass and ferns | Treats pass-through grass like player, does not detour due grass Collision | NOT TESTED |
| H2-02 | Thin safe diagonal lane with wall well outside avatar width but within line's enclosing rectangle | Follows certified safe shortcut without crossing any wall | NOT TESTED |
| H2-03 | Place a solid wall/obstacle directly into the lane during follow | Rejects unsafe shortcut/halts or bounded replan; no wall clip | NOT TESTED |
| H2-04 | Put water, lava, berry bush, web or datapack hazard into corridor | Handles each according to current risk policy; no hazard falsely made free | NOT TESTED |
| H2-05 | Edge of unsupported floor, unloaded chunk, world border | Fails closed; no invisible floor traversal, force-load or teleport | NOT TESTED |
| H3-01 | 90° corridor corner with wall | Does not cut unsafe corner; turn only with actual clearance | NOT TESTED |
| H3-02 | U-shaped wall / intentionally inaccessible stationary target | Bounded recovery or `PAUSED_UNREACHABLE`, no infinite circular walk | NOT TESTED |
| H3-03 | Walk at 30°, 60°, 120° diagonal courses | Smooth arbitrary heading, correct final arrival vs 0.35 tolerance | NOT TESTED |
| H4-01 | `/npcortex c2 unfollow yui` mid-motion | Motion stops safely and never resumes without new follow request | NOT TESTED |
| H4-02 | Combat / knockback during follow | C1 combat priority unchanged; no foreign Motion Ticket cancellation | NOT TESTED |
| H4-03 | Jump up +1, safe descend −1, slabs/stairs mixed | 3D primitives remain safe, separately verified; no accidental flat shortcut | NOT TESTED |
| H4-04 | `c2 navigate`, then `c2 cancel`; ordinary navigation after unfollow | Static C2 graph, ownership and transactional cancellation unchanged | NOT TESTED |
| H4-05 | Save & Quit / reload with follow active | No automatic restart of old Follow motion; UUID/state valid | NOT TESTED |
| H4-06 | 1, 3, 5, 10 simultaneous NPC Follow | Record server MSPT, plan latency, stutter, ownership isolation | NOT TESTED |

## Capturing failure evidence

Before approaching Yui to reset an apparent stuck state, run:

```mcfunction
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
```

Save logs around `WALK_TELEMETRY`, `F1=`, `generation=`, `failures=`, `waypoint=` and route terminal phases, and a short video showing the oscillation. `generation` alone is not a rate; record wall-clock duration across two samples.

Test reports must separate Pure Java/Backend PASS from Forge Build, live Minecraft gameplay and server performance acceptance. Do not push GitHub until user signs off.
