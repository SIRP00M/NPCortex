# NPCortex C2-F2 Fix #3 — Vanilla-inspired Adapter A — Windows Acceptance Checklist

Backup Repository/world first. **Use Windows Temurin Java 17**, Minecraft Forge 1.20.1 and the same test world for before/after comparison.

## A. Automated/Forge build

```powershell
cd C:\path\to\NPCortex
.\scripts\test-c2-f2-fix3-vanilla-adapter.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Record PASS/FAIL with exact compiler errors if any; this candidate has not been compiled against actual Forge locally.

## B. Minecraft commands

```mcfunction
/npcortex c1 info yui
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
```

Replace `Dev` with your real player name. Unfollow with `/npcortex c2 unfollow yui`.

## C. Visual baseline vs Fix #2, identical terrain and player speed

- [ ] **Flat straight 20 blocks**: count visibly stopped frames, latency and jerk between route segments, NOT only arrival success
- [ ] **Smooth gentle bend**: Yui turns while walking; no new unnatural stops
- [ ] **Sharp U-turn / circle**: C1 may briefly stop forward motion when target is behind to avoid unsafe wall shortcuts; compare stop duration and client yaw
- [ ] **10–20-block rolling follow**: no >8-block hard pause on loaded local terrain, no teleport
- [ ] **Wall corner / diagonal squeeze**: must NOT move through a blocked corner, claim success through obstacle or step over unloaded chunk
- [ ] **Slab/stairs + JUMP_UP/SAFE_DESCEND**: same typed traversal results as before; no false terminal receipt
- [ ] **Unfollow during movement**: immediate owned-motion cancellation, no old ticket waking later; new follow works when settled
- [ ] **Combat / knockback**: valid combat owner interrupts follow without fighting velocity; original safety behavior remains
- [ ] **Player logout / dimension / Save & Quit / rejoin**: no hidden Follow session replay
- [ ] **Performance 1/3/5/10 NPC**: compare server MSPT, route plans/sec, ticket frequency, position updates and C2-F2FIX2 WALK_TELEMETRY against Fix #2

### Required evidence

Capture a short before/after 20-block video if possible; send `/npcortex c2 followstatus yui`, any `BLOCKED`/`CANCELLED` messages, `WALK_TELEMETRY`, and Forge build output. A successful `motion_done=COMPLETE` is **not** Visual Smoothness PASS.

### Important limits

This uses a bounded Vanilla-**inspired** input adapter only; it does **not** run Vanilla `Mob.MoveControl`. Independent follow route boundaries still stop C1 horizontal velocity. F2-Fix3 should be scored as an experiment pending Windows acceptance. Backend full-suite and previous Navigation Full Quality Gate remain pending this run.
