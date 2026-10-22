# NPCortex — C-3D Persistent Mixed Locomotion / Windows Gameplay Checklist

Use **Java 17**, Forge 1.20.1 and the ZIP Candidate in a clean working copy. Don't overwrite your known-good baseline until this build and Gameplay smoke tests pass.

## Build and isolated tests

```powershell
.\scripts\test-c2-persistent-3d-c.ps1
.\scripts\test-c2-persistent-follow-c.ps1
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

If Forge fails compilation, send the full compiler output before editing code. Pure-Java PASS is not a Forge build PASS.

## In Minecraft (replace `YourName` with actual player name)

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui YourName
/npcortex c2 followstatus yui
/npcortex c1 motion yui
```

Observe `mixed3DStarts` in F1 and `mixedStage=... kind=...` in C1 while traversing a mixed route. A value of zero can be a valid safe fallback, not necessarily a build failure. A mixed sequence must not report success until the avatar is grounded at its certified destination.

| ID | Fixture / action | PASS criterion | Result |
|---|---|---|---|
| 3D-01 | Flat 30–50 blocks follow | Persistent flat behavior remains smooth; no new grid zigzag | PENDING |
| 3D-02 | Walk → full +1 jump → Walk, safe landing | Same-token mixed stage when eligible; physically grounded at correct Y | PENDING |
| 3D-03 | Walk → bottom/top slab or stair +0.5 → Walk | Verified STEP_UP; fallback allowed when mixed lease cannot be proven | PENDING |
| 3D-04 | Walk → one-block −1 SAFE_DESCEND → Walk | Verified grounded, no fall-through or fake receipt | PENDING |
| 3D-05 | STEP_DOWN −0.5, supported fixture | Verify specifically; historically NOT YET VERIFIED | PENDING |
| 3D-06 | Multiple mixed turns and adjacent obstacles | No unnecessary long pauses; never pass through walls | PENDING |
| 3D-07 | Block/replace a landing before reaching it | Reject unsafe next stage, no forged success | PENDING |
| 3D-08 | Hazard/berry/cobweb, plus unloaded-chunk edge | Risk policy maintained, no force loading | PENDING |
| 3D-09 | `unfollow` while walking and mid-jump | Stops commanded motion, no auto resume, Vanilla landing behavior preserved | PENDING |
| 3D-10 | Zombie/combat/knockback mid-follow | C1 combat priority retained; no parallel movement commands | PENDING |
| 3D-11 | Save & Quit / rejoin, NPC death/respawn | No stale session replay, UUID/lifecycle unaffected | PENDING |
| 3D-12 | 1 / 3 / 5 / 10 NPC active follow | Gather MSPT, CPU, latency and ownership isolation | PENDING |
| 3D-13 | After unfollow `/npcortex c2 navigate` and `cancel` | Baseline C2 navigation and transactional recovery unchanged | PENDING |

**Debug when paused:**
```mcfunction
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
```
Capture F1 phase/reason, C1 stage kind, final token phase, server logs and actual block coordinates. Any `MIXED_*` terminal other than `MIXED_COMPLETE` must not be counted as a successful 3D traversal.
