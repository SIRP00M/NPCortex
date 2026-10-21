# C-L1..L4 — Windows Minecraft Gameplay Acceptance Checklist (Candidate C)

All tests are **PENDING** until the user reports evidence from this exact ZIP. Do not treat Java-only tests as an in-game PASS.

## Step 0 — Environment / build

Open PowerShell at extracted repository root. Use JDK 17.

```powershell
java -version
javac -version
.\scripts\test-c1-full-locomotion-c.ps1
.\scripts\test-c2-turn-aware-b.ps1
.\scripts\test-c2-generalized-traversal-c.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**If Forge build fails**, stop: copy first compiler error and complete console log. Do not infer failure from Java self-tests; do not declare Minecraft PASS.

## Step 1 — Baseline and visual tests

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
/npcortex c1 sync yui
/npcortex c2 follow yui <YourMinecraftName>
/npcortex c2 followstatus yui
```

The `<YourMinecraftName>` placeholder means your actual account name; omit angle brackets.

- [ ] L01 Stationary idle for 10 seconds: body/head no repeated twitch, `/npcortex c1 motion yui` reports `locomotion=IDLE` when settled.
- [ ] L02 Straight flat 20 blocks: normal vanilla limb swing, no unnatural head/body snapping.
- [ ] L03 90° turn around known safe corner: body follows turn smoothly; no through-wall corner cut.
- [ ] L04 U-turn 180°: no full 360° spin across -180/180 wrap, reasonable head-body difference.
- [ ] L05 Start/stop three times across follow stop/resume hysteresis (2.5 / 4.0 blocks): pose settles without oscillating at idle.
- [ ] L06 Follow over short grass, tall grass and flowers: no artificial obstruction and no unsafe shortcut.
- [ ] L07 Walk to stair/step up, jump, safe descend: remains on existing C1 3D path / supports only verified transitions.
- [ ] L08 Explicit operator turn while not following: `/npcortex c2 unfollow yui`, `/npcortex c1 turn yui 90`, then `/npcortex c1 rotation yui`; yaw correct and body/head settle.
- [ ] L09 Unfollow during walk: `/npcortex c2 unfollow yui` then `/npcortex c1 motion yui`; motion stops and pose returns IDLE, without stale restart.
- [ ] L10 Combat/knockback while following: combat impulse moves character correctly and no duplicate physics/motion owner; no mid-combat follow overwrites.
- [ ] L11 Despawn/respawn, Save & Quit/rejoin: viewer identity, rotation, equipment, UUID unchanged as applicable; no stale pose packets or automatic Follow restart.
- [ ] L12 Target player logout/dimension change (where multiplayer available): Follow fails safely, no stale target / cross-dimension motion.
- [ ] L13 Two clients near same NPC: view from both cameras, no yaw disagreement or packet flicker; check `/npcortex c1 sync yui` rotations acknowledged.
- [ ] L14 1, 3, 5, 10 NPC follow or move simultaneously: capture server MSPT (sustained and peak), frame smoothness, packet load, actor isolation, no uncontrolled CPU scaling.
- [ ] L15 Regression of static C2 navigation, cancel and new navigate without manual reset, verified wall, hazard and swept collision behavior.

## Debug capture on a visual failure

While the twitch is visible, take **video 10–20 s** and run:

```mcfunction
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c1 rotation yui
/npcortex c1 sync yui
```

Capture `latest.log` / Java exception and note: actor, actual client FPS/TPS, approximate separation, `generation`, `failures`, `motion`/`locomotion`, time of snap, whether Follow PAUSED/MOVING/TURNING and whether it happens at packet updates or on every tick.

### Acceptance gate

- Full Forge build succeeds (JDK17) and all mandatory gameplay cases PASS.
- No regression in C1 move/combat, C2 static navigation/cancel, 3D traversals or save/rejoin.
- MSPT and synchronization validated on 1/3/5/10 NPC fixtures, not presumed from pure Java tests.
- Keep any failure in `PENDING_TESTS.txt` until verified. Do not Push GitHub before explicit user acceptance.
