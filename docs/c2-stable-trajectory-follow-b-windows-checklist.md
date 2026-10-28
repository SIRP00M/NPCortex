# NPCortex Stable Trajectory Follow B — Windows Forge Gameplay Checklist

Baseline: C-TN Smooth Follow Hardening C S1–S4. Candidate: Stable Trajectory Follow B.
Do not push to GitHub until tests pass. Use Java 17 / Minecraft 1.20.1 Forge.

## Build and scripted tests

```powershell
cd F:\AI\NPCortex
.\scripts\test-c2-stable-trajectory-follow-b.ps1
.\scripts\test-c2-smooth-follow-hardening-c.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**Must see** `BUILD SUCCESSFUL`; the pure Java tests alone do not count as Forge compilation.

## Minecraft commands

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui YourName
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
/npcortex c2 unfollow yui
```

Substitute your Minecraft username for `YourName`. If Yui is already spawned, skip spawn.

## Core acceptance (mark PASS / FAIL / NOT TESTED)

| ID | Fixture and action | Expected | Result |
|---|---|---|---|
| ST-B-01 | Flat 50-block **cardinal** straight chase | No unnecessary alternating steering on straight terrain | NOT TESTED |
| ST-B-02 | Flat 30-block **diagonal** chase | No repeated crossing of the same centerline | NOT TESTED |
| ST-B-03 | Hold position while Yui approaches 2.5 block stop zone | Settle without repeated backtracking/orbiting; proper pause | NOT TESTED |
| ST-B-04 | Start with Yui slightly off centerline (<0.12 block) | Small cross-track perturbation does not trigger alternating corrections | NOT TESTED |
| ST-B-05 | Approach a target then move player back 2–3 blocks | No invented final waypoint success; motion recovers as designed | NOT TESTED |
| ST-B-06 | 90-degree wall corner and narrow passage | No unsafe shortcut or wall penetration | NOT TESTED |
| ST-B-07 | Short/tall grass, fern, flowers | Follow remains passable when vanilla player can pass | NOT TESTED |
| ST-B-08 | Slab / stair +0.5, JUMP +1 and SAFE_DESCEND -1 | Landing verified, no regression | NOT TESTED |
| ST-B-09 | Ravine / missing floor / hazard / chunk edge | Fail-closed; avoid unverified support/hazard | NOT TESTED |
| ST-B-10 | Cancel/unfollow while walking and during 3D move | No stale ticket motion afterward | NOT TESTED |
| ST-B-11 | Knockback/combat during follow | Combat ownership priority preserved | NOT TESTED |
| ST-B-12 | Save & Quit / rejoin | No unauthorized automatic resume | NOT TESTED |
| ST-B-13 | 1 / 3 / 5 / 10 NPC | Compare MSPT and per-NPC heading stability | NOT TESTED |
| ST-B-14 | `/npcortex c2 navigate` baseline | Unchanged navigation path/transaction behavior | NOT TESTED |

## Evidence to save

- 10–20 s side-view gameplay clip for straight and arrival cases, preferably with F3 and coordinates.
- Before/after `followstatus yui`, `c1 motion yui`; C1 `WALK_TELEMETRY`, `lastFailure`, `smoothWaitTicks`, `persistentAppends`.
- Forge build output and MSPT for 1/3/5/10 NPC.
- Explicitly report any remaining stop, lateral swing, or BLOCKED/PAUSED_UNREACHABLE with fixture coordinates and surrounding blocks.

**Passing one fixture does not imply that all terrain, multi-NPC, or modded blocks passed.**
