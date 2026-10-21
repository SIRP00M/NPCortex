# NPCortex — C2-F2 Turn-Aware + Safe Smoothing B: Windows Checklist

## Preflight (Windows PowerShell, Temurin JDK 17)

```powershell
java -version
javac -version
.\scripts\test-c2-turn-aware-b.ps1
.\scripts\test-c2-generalized-traversal-c.ps1
.\scripts\test-terrain-s4-fix1.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**If build fails, capture the first Java compiler error (not only the Gradle summary) and stop.**

## Minecraft commands

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui <YourMinecraftName>
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c2 unfollow yui
```

Replace `<YourMinecraftName>` with your actual player name; the angle brackets are placeholders, **not** syntax to enter literally. The NPC must be alive, grounded and in the same dimension as the player.

| ID | Scenario | Required observation | Status |
|---|---|---|---|
| B-01 | Flat grass 20+ blocks | NPC takes mostly smooth direct line; walks through short/tall grass | PENDING |
| B-02 | Long shallow diagonal on level empty ground | No 1-block left-right stair-step oscillation; live movement reaches follow ring | PENDING |
| B-03 | 90° corner around wall | Does not shortcut through wall or stall indefinitely | PENDING |
| B-04 | Thin gap/1-block obstacle | Risk-free support and collision maintained throughout shortcut | PENDING |
| B-05 | Floor hole/ledge adjacent to apparent shortcut | No unsafe support skipping or fall | PENDING |
| B-06 | Berry bush + cobweb alternative | Prefers safer path and does not cut a risk-cost rectangle | PENDING |
| B-07 | Alter block during moving ticket | Fresh graph or live C1 collision fails closed; does not pass through block | PENDING |
| B-08 | Slab, stairs, jump +1, safe descend -1 | Original 3D typed moves remain usable, no fabricated flat shortcuts | PENDING |
| B-09 | Player changes direction repeatedly | Target replan remains bounded; no infinite oscillation; generation recorded | PENDING |
| B-10 | Unfollow during move | Ticket cancels; no stale follow resumes | PENDING |
| B-11 | Combat/knockback during follow | C1 Combat ownership respected; no duplicate C1 ticket | PENDING |
| B-12 | Save/quit/rejoin, player death or dimension change | Fail-closed lifecycle, no unsolicited resume | PENDING |
| B-13 | 1, 3, 5, 10 NPC | MSPT, CPU and visible motion acceptable; no actor cross-talk | PENDING |
| B-14 | Legacy C2 navigate + cancel | Navigation regressions absent | PENDING |
| B-15 | Modded no-collision plants vs modded hazard | Parity and configured hazard tags remain correct | PENDING |

### Debugging zigzag

While the NPC zigzags, **before** approaching it to trigger HOLD, repeat:

```mcfunction
/npcortex c2 followstatus yui
/npcortex c1 motion yui
```

- `generation` stable + waypoint advancing: path geometry/steering likely.
- `generation` increasing rapidly: moving-target replan/goal switching likely.
- A `BLOCKED`, `TIMED_OUT`, `PAUSED_UNREACHABLE` or `CANCELLED` terminal requires corresponding logs.

Send observations with relative position, time, block fixture, and relevant log lines; actual gameplay must be marked PASS or FAIL by the operator, never inferred from this test report.
