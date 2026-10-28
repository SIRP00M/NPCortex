# NPCortex — C2-P1-A World Representation (Architecture C)

**Status: IMPLEMENTATION CANDIDATE; NOT FULLY ACCEPTED.**

Approved parent roadmap: C2-P1 Architecture C, staged P1-A world representation, P1-B 3D A*, P1-C waypoint execution, P1-D bounded dynamic replanning. The user specifically requires passing each Quality Gate before advancing. **Only P1-A is implemented in this package.** Do NOT infer P1-B/C/D or autonomous obstacle routing exists.

## Source audit and reason for stage isolation

C2-P0's trusted `C2WorldSensor.snapshot()` has `blocks: {}`. `C2MoveExecutor` converts a single Qwen `move_to` world target into a single same-height C1 turn/motion, max 7.5 blocks. C0 goal `max_decisions=1`, and the C0 snapshot contract supports <=128 block entries / 6000 bytes. An unbounded 3-D map cannot safely be pushed into that JSON snapshot. C1 Player.travel(), knockback, inventory, lifecycle, and network tracking already have accepted basic gameplay behaviors and must not be replaced.

### Implemented P1-A

- **`C2NavGrid.java`:** pure Java 17 bounded, immutable 3-D candidate surface grid keyed by `(x, supportY, z)`. Records measured foot surface height and classification `WALKABLE`, `OBSTRUCTED`, `PARTIAL_SUPPORT`, `UNSUPPORTED`, `HAZARDOUS`, `UNLOADED`. Multiple Y-level candidates per x/z, no silent flattening. Unknown/unloaded is never treated as walkable.
- Maximum horizontal scan radius **6 blocks** (default 4); scan each loaded column at **5** support levels (avatar foot-support ±2); at most **845 candidate cells**. Unloaded columns produce a single sentinel and are not probed. No chunk ticketing, chunk loads, unsafe world writes, model calls, or per-tick scanning.
- **`C2NavigationSensor.java`:** reads Forge/Vanilla server block collision shapes, conservative horizontal player-footprint support, standing bounding-box collision, dangerous blocks/fluids, loaded chunk and world border. Potentially navigable slabs are represented using surface height; partial stairs/complex shapes are conservatively classified `PARTIAL_SUPPORT` and NOT promoted to safe walkable. This is world observation, not reachability proof.
- **`/npcortex c2 navscan <name> [radius]`** (operator-only via existing `/npcortex` permission 2): reads the live NPC on the server thread, refuses dead/dimension-mismatched/moving NPCs; reports bounded counts and measured scan duration. Does not create goals, issue movement, or change Backend schema. `C2NavigationSensor.capture(npc,radius)` returns the reusable immutable grid for the future P1-B planner.

### Deliberately unchanged / excluded

- P0 `/npcortex c2 move` remains one straight, same-height segment. A wall still returns FAILED; **no autonomous A*, jump selection, waypoint movement or replanning yet**.
- No C0 / Ollama API changes or large raw grid serialization. P1-B must design a compact, authorized navigation plan/skill contract before connecting it to Qwen. `WALKABLE` means a momentary standing candidate, **not** a reachable node. Dynamic changes can invalidate it immediately.
- No repeated on-tick rescans, force-loading, unsupported blocks treated as air, new NPC physics, PathfinderMob, or Google/GitHub actions.
- Partial support (stairs, fences, scaffolding, thin/fractional surfaces) is intentionally conservative in this sprint. Water, lava and hazards are avoided by classification. A planner must revalidate before execution and respect loaded-chunk boundaries.

## Local tests (tool environment)

- `javac --release 17` on Linux JDK 21 host: **P1-A Grid SelfTest 27 assertions passed** (bounds, unknown sentinels, multiple elevations, malformed probes, immutable snapshot, cap). Pure grid 200 synthetic maximum-sized scans ~= 18 ms total on THIS machine (not Forge tick measurement).
- Additional P0/C1 dependency-free regressions: 10 more SelfTests, **1,195 assertions passed** (so total with P1-A **1,222**). These are not Forge compile tests.
- Backend full Python unittest discovery: **193 tests passed**, exit 0; mock Ollama, not real Qwen.
- Actual Forge `compileJava` **BLOCKED BEFORE COMPILATION**: wrapper cannot fetch Gradle 8.8 (`UnknownHostException: services.gradle.org`). This is NOT a passing Forge build.
- Actual Windows Minecraft navscan, collision/slab/stair gameplay, safety, 1/3/5/10 NPC MSPT benchmarks, multiplayer and crash recovery: **NOT RUN in this environment**.

## Windows build and Acceptance checklist

Unzip the entire repository into `F:\AI\NPCortex`, keeping a backup of the current verified version. Open PowerShell with Temurin Java 17:

```powershell
cd F:\AI\NPCortex
.\scripts\test-c2-p1a.ps1
.\scripts\test-c2-p0.ps1
.\scripts\dev-forge.ps1 -Task build
# For gameplay, keep Backend and Client in separate PowerShell windows:
.\scripts\dev-backend.ps1
.\scripts\dev-forge.ps1 -Task runClient
```

Minecraft single player with cheats (OP) enabled:

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 navscan yui
/npcortex c2 navscan yui 6
/npcortex c2 navscan yui 1
/npcortex c2 status yui
```

Acceptance must check:

1. **Build:** Forge `build` exit 0 on Windows Java 17; fix compile errors first if any. Gameplay tests must not substitute for failed compilation.
2. **Open flat field:** navscan reports nonzero `walkable`, finite bounded `elapsedMs`, `candidates <= 845`, `columns <= 169`. Repeat radius 1, 4, 6 on a loaded field.
3. **Vertical terrain:** stairs/slabs/hills show safe conservative `partial` or appropriate walkable heights, without crashes. `partial` is not a promise of path viability.
4. **Water/lava/hazard:** dangerous block/fluids are not counted as safe walkable. Avoid placing NPC itself in hazards to test; scan near safely in Creative.
5. **Chunk boundary:** test near unloaded chunk; no chunk load/teleport should occur. Unknown must remain `unloaded`, not safe.
6. **Lifecycle/Thread safety:** `navscan` refuses dead/despawn/dimension-mismatched or moving NPC, and never changes its position or inventory. Server remains responsive.
7. **C2-P0 regression:** normal `/npcortex c2 move` near/far, blocked path FAILED, correct position tolerance, cancel/save-rejoin; C1 move/rotation/collision/knockback/death/inventory and tracking unchanged.
8. **Performance:** record repeated `navscan` `elapsedMs`, server MSPT with F3/Alt+F3 or profiler on 1/3/5/10 NPC; note radius, world complexity, tick load, Java heap. No absolute benchmark has been certified yet.
9. **Dedicated server / mod compatibility / crash:** explicitly mark unverified unless tested. P1-B cannot begin until the user accepts P1-A Quality Gate.

**Send back:** full Gradle failure or success log; chat output of `/npcortex c2 navscan yui 4` and `6` in flat and partial terrain; any `latest.log` stack traces; test outcome and observed MSPT where available.

No GitHub branch, commit, merge or push was performed by ChatGPT.
