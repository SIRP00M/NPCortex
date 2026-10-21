# NPCortex Persistent Follow C — Windows / Forge 1.20.1 Acceptance

**Candidate only.** Execute on Windows with Temurin JDK17 and clean test world; do not declare PASS on the basis of local pure JVM results.

## 1. PowerShell (repository root)

```powershell
java -version
javac -version
.\scripts\test-c2-persistent-follow-c.ps1
.\scripts\test-c2-bplus-adaptive-steering.ps1
.\scripts\test-c1-full-locomotion-c.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

## 2. Minecraft commands

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui YourMinecraftName
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
/npcortex c2 unfollow yui
```

Replace `YourMinecraftName` with your real Minecraft player name. Spawn only if not already spawned.

## 3. Core physical gameplay

- [ ] **C-01 Ground straight 50 blocks**: player walks/runs in a straight line on loaded flat floor. Collect `/npcortex c2 followstatus yui` twice while Yui is actively walking. Expect `persistentAppends` **greater than zero** when a clean continuation is available; no forced 4–5-block Ticket handoff at each segment. A new Ticket at bounded limits is allowed.
- [ ] **C-02 Angled 360° walk**: player moves diagonally at arbitrary slope, zigzag and orientation change. Follow must not enter loop or drift out of safe corridor.
- [ ] **C-03 Zero-forward pre-turn**: start with Yui facing away from target beside an obstacle. No recurring `BLOCKED ticks=1 commandedZero=1` solely because yaw aligns in place; genuine blocked collision remains fail-closed.
- [ ] **C-04 Dynamic player retarget**: player changes direction while Follow is active. C2 should append only a **fresh validated** continuation, no instant unsafe redirection.
- [ ] **C-05 Target stop/resume ring**: Yui holds near 2.5 blocks; player walks beyond 4.0 blocks; no 2.5–4.0 oscillation.
- [ ] **C-06 Wall and U-shaped obstacle**: no shortcut through wall, safe recovery when reachable, bounded pause when not.
- [ ] **C-07 Terrain mutation**: insert a block, hole or hazard in the NEXT to-be-committed corridor while following. No traversal of stale unsafe edge; fallback pauses/replans safely.
- [ ] **C-08 Risk and passability**: walk through short/tall grass/flowers; verify berry bushes/cobwebs and fire/lava policies; test modded pass-through blocks separately.
- [ ] **C-09 Vertical primitives**: 0.5 step up, 1.0 jump, safe descend 1.0, stairs/slabs; separately mark unverified 0.5 step down. No unauthorized flat-path splice across vertical transition.
- [ ] **C-10 Unfollow while moving**: `/npcortex c2 unfollow yui`, then check motion and position: no old Session resurrection, no drift.
- [ ] **C-11 Combat/knockback**: cause a controlled hostile encounter; combat priority must not be overridden by Follow appends.
- [ ] **C-12 Save/Quit/Rejoin**: no Follow replay after reload; preserved identity/position and no orphan owner.
- [ ] **C-13 Static C2 navigation**: test `/npcortex c2 navigate yui <x> <y> <z>`, then `/npcortex c2 cancel yui`; retained transaction behavior.
- [ ] **C-14 Unloaded chunk, player disconnect/dimension switch**: no force-loading or movement into unknown areas; safe pause or terminal lifecycle.
- [ ] **C-15 1/3/5/10 NPC**: compare TPS/MSPT, planning time, per-NPC token/status isolation; no unbounded per-tick route planning.
- [ ] **C-16 Long 100+ blocks**: after one bounded C1 lease expires, system safely starts a new motion if still following. A bounded lease must never become infinite; quality-gate requires measuring remaining handoffs.

## 4. What evidence to send back

1. Build status (`BUILD SUCCESSFUL` or full compilation error).
2. `followstatus` at start, halfway and after ~50 blocks (include `generation`, `waypoint`, `persistentAppends`, `rollingMisses`).
3. First `WALK_TELEMETRY` lines, any `result=BLOCKED`/`CANCELLED`, and relevant `motion_done` messages.
4. Whether walking looked uninterrupted; provide short video of straight and corner motion if available.
5. Mark EACH C-01..C-16 PASS/FAIL/NOT TESTED, plus 1/3/5/10 NPC MSPT if recorded.

**Stop and report** if build fails, C1 ownership becomes inconsistent, avatar teleports/clips, or a cancelled Session continues moving. Do not commit/push before your quality gate.
