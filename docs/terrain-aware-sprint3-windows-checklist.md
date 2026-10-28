# NPCortex Sprint 3 — Windows / Minecraft Forge Gameplay Checklist

**Candidate only.** Automated unit tests are not Forge build, live physics or MSPT evidence.
Use Java 17 / Minecraft 1.20.1 Forge, a disposable **Single Player** test world,
and the Sprint 3 Full Repository. User performs all GitHub operations.

## Preparation (PowerShell)

```powershell
cd C:\path\to\NPCortex
.\scripts\test-terrain-s3.ps1
.\scripts\dev-forge.ps1 -Task build
# Terminal 2:
.\scripts\dev-backend.ps1
# Terminal 3:
.\scripts\dev-forge.ps1 -Task runClient
```

Confirm the Backend health, spawn/inspect Yui, choose a **loaded** fixture no
more than six horizontal blocks and two support-block levels from her origin.
Use `/npcortex c1 info yui` to determine actual feet Y, not just the block Y.
The `navigate`, `navhier`, and `navplan` commands accept **fractional footY**
(e.g. 69.5 for the top of a bottom slab). Goal position is always x+0.5/z+0.5.

## Scenario checklist (report PASS / FAIL / NOT TESTED)

| Scenario | How to exercise | Required evidence |
| --- | --- | --- |
| Flat regression | `c2 navhier` then `c2 navigate` on level terrain | WALK path, `C2=COMPLETED` with `verified=true`, no duplicate C1 ticket |
| 1-block pit escape | Yui in a safe 1-deep opening with a clear landing surface | `navhier PATH_FOUND`; `navigate` selects JUMP_UP, grounded final position and verified C0 action |
| Slab +0.5 | bottom slab adjacent to a full supported start | typed STEP_UP, footY .5, terminal receipt, completed goal |
| Slab -0.5 | start on upper surface, landing bottom slab | typed STEP_DOWN; verify **separately** (still unverified after Sprint 2) |
| Safe descend -1.0 | two-block-wide, cleared, lower full-block landing | typed SAFE_DESCEND, grounded targetY; no fake completion |
| Stair partial footprint | vanilla stair orientation(s), unobstructed 0.5-footprint support | `terraininspect STAIR_TRANSITION` only when clearance passes; physical traversal remains to verify |
| Mixed route | 2–4 different typed segments plus flat walking | correct Segment Ticket changes, no duplicate goal / action IDs |
| One-way / unsupported jump | blocked ceiling or 2-block wall | no invented JUMP edge, `NO_PATH` or safe failure |
| Changed terrain BEFORE next segment | place block in an approved edge, keep safe detour | edge rejected; bounded replan where possible, no stale movement |
| Cancel while traversing | `c2 cancel yui` during or shortly after jump | settle/stop under C1 physics, backend `CANCELLED`; subsequent `navigate` needs no `reset` |
| Combat / knockback | run with suitable local hostile mob scenario | preserve actual Vanilla impulse; never fabricate terminal verification |
| Unloaded chunk boundary | use an unloaded neighboring chunk fixture | no force-load, no UNKNOWN promoted to traversable |
| Performance | test 1/3/5/10 NPC where practical | record navscan/navhier latency, server MSPT; do not substitute pure Java timings |

### Commands

```mcfunction
/npcortex c1 info yui
/npcortex c2 terraininspect yui 80 68 -62
/npcortex c2 navhier yui 81 70 -62
/npcortex c2 navigate yui 81 70 -62
/npcortex c2 status yui
/npcortex c2 cancel yui
```

Replace coordinates with **actual prepared terrain**. For a bottom slab surface,
pass the fractional target footY, e.g. `69.5` if its support block is Y=69.
Do not test on unsafe cliffs, lava, missing support or unexamined structures.
If `LANDING_COLLISION` / `LANDING_HEIGHT_MISMATCH` occurs, confirm the **whole
player AABB footprint** and Yui's yaw/position before assuming implementation
failure; keep logs. The 3D waypoint bridge preflights the exact live world.

A result showing `PATH_FOUND` proves planning only. Gameplay PASS additionally
requires movement, the matching typed C1 receipt, grounded landing and final
`C0 verified=true`. The full Quality Gate remains pending until reported.
