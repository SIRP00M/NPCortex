# C-TN Smooth Follow Hardening C — Windows 1.20.1 Forge Checklist

**Requires:** Java 17 Temurin, test world backed up, latest ZIP extracted fully (not copied partially on top of older build). No GitHub operations.

## Build and tests (PowerShell in repository root)

```powershell
.\scripts\test-c2-smooth-follow-hardening-c.ps1
.\scripts\test-c2-terrain-native-follow-c.ps1
.\scripts\test-c2-persistent-3d-c.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

## In Minecraft (replace `Dev` with actual player username)

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c2 unfollow yui
```

## Core acceptance

- [ ] **S-01 flat 50 blocks:** player walks straight, NPC follows smoothly. Capture 2 followstatus snapshots separated by 10–15s. Check growth of `smoothMotionTicks`, `smoothWaitTicks`, `ticketHandoffs`, and `preparedHits`. Compare to baseline C-TN under same conditions.
- [ ] **S-02 diagonal 25 blocks:** minimal zigzag; no new grid snap, unsafe corner or stutter.
- [ ] **S-03 moving target:** player walks 1.5–5 blocks lateral while NPC detours; avoid repeated reversing left/right, target motion >6 blocks permits safe retarget.
- [ ] **S-04 ravine edge:** verified detour or safe bounded pause; never leave safe terrain to cut straight.
- [ ] **S-05 safe 3D transitions:** flat→STEP_UP +0.5, JUMP_UP +1, SAFE_DESCEND −1 on cleared test fixtures, correctly reverified landing.
- [ ] **S-06 STEP_DOWN −0.5** separately; previously unverified across early nav acceptance, record factual result.
- [ ] **S-07 block mutation:** place/break block during motion; stale hint must fail closed, no unsafe motion.
- [ ] **S-08 active cancel:** `/npcortex c2 unfollow yui` while walking and during a 3D transition; ensure no resumption from cached plan.
- [ ] **S-09 combat/knockback:** ownership and non-conflicting motion; death/respawn safe.
- [ ] **S-10 save/rejoin:** cached hint and old Follow session must not auto-replay.
- [ ] **S-11 repeated failures:** no runaway replan loop or unbounded fallback; check `lastFailure`, `terrainReprobes`, `maxWaitTicks`.
- [ ] **S-12 1/3/5/10 NPC:** real Minecraft server MSPT and route latency, compare CPU/tick impact.

### How to read counters

`smoothMotionTicks`: server ticks reporting MOVING (not 1:1 distance travelled). `smoothWaitTicks`: ticks in TURNING, COOLDOWN, WAIT_GROUND, REPLANNING, PAUSED_UNREACHABLE or PAUSED_COMBAT. `turnWait`, `cooldownWait`, `replanWait`, `recoveryWait`, `groundWait`, `combatWait` split that total by phase. `smoothHoldTicks`: intentional stop-distance HOLD/PAUSED. `maxWaitTicks`: longest uninterrupted wait. `ticketHandoffs`: verified terminal follow route handoffs. `preplanProbes` and `preplanFound`: number of speculative attempts and valid hints created; `preparedHits` and `preparedMisses`: consumed or discarded at terminal. None prove client visual smoothness alone.

**PASS criterion:** materially reduce unnecessary wait/handoff on matched fixtures WITHOUT increasing unsafe terrain/cancellation/combat failures. Only the user's Windows Gameplay and real Forge MSPT measurements can close the Full Quality Gate.
