# NPCortex C2-F2 Fix #2 — Windows Gameplay Acceptance Checklist

## 0. Back up and build

Back up world and original repo before installing candidate. Java 17 / Forge 1.20.1.

```powershell
cd F:\AI\NPCortex
.\scripts\test-c2-f2-fix2-smoothing.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**Do not mark Forge PASS if Gradle/build fails.** Fix exact Java/Forge compiler errors via new source audit, not guesswork.

## 1. Start

In a spacious test-world flat area with Yui spawned and not busy:

```mcfunction
/npcortex c1 info yui
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
```

Replace `Dev` with actual player name. Follow is operator-only and does not need Ollama/Backend absent old C0 action reconciliation.

## 2. Visually compare BEFORE and AFTER on identical terrain

- [ ] **A: 20-block flat straight**. Walk at normal speed. Note stop/start count, `WALK_TELEMETRY` samples, nearStationary and extensions in latest.log. `extensions>0` is only expected if route still has safe uncommitted flat nodes: zero extensions does *not* imply failure for short routes.
- [ ] **B: Zigzag or circle** (4 wide turns). Observe whether Yui curves earlier and avoids standing still unnecessarily; sharply reversing may still need to turn in place for safety.
- [ ] **C: Follow moving player farther than 8 blocks** on loaded terrain, confirm rolling follow and no teleport. Check `/npcortex c2 followstatus yui` for reason and active ticket telemetry.
- [ ] **D: Stair/Slab + JUMP_UP/SAFE_DESCEND**: must use typed traversal/land correctly, not shortcut with flat walk.
- [ ] **E: Diagonal wall corner**: cannot clip/cut through a blocked corner; route may pause/replan when unsafe.
- [ ] **F: Terrain mutation during Follow**: place obstructing block *safely in front*; must not ghost through, false-complete or loop without a failure bound.
- [ ] **G: Unfollow during continuous WALK**: `/npcortex c2 unfollow yui`; no stale movement, and new follow remains possible after motion settles.
- [ ] **H: Combat knockback while following**: motion must not overpower Vanilla impulse; follow only resumes by a verified new plan. Do not infer combat PASS from pure Java.
- [ ] **I: Save & Quit/rejoin / target logout / dimension change**: no resurrected Follow ticket or wrong-player UUID binding.
- [ ] **J: Performance**: repeat 1/3/5/10 NPC follow, capture actual server MSPT, C1 motion tickets/s, graph scans/sec and `WALK_TELEMETRY` with equal scenarios vs Fix #1. Pure Java timing is NOT Forge MSPT.

## 3. Diagnose

Inspect `logs/latest.log` for `C2-F2FIX2 WALK_TELEMETRY npc=... token=... result=... samples=... nearStationary=... commandedZero=... turns=... extensions=... waypoints=... distance=...`.

**Interpreting:** Continuous motion within a ticket may improve while separate route handoffs still stop. Video/F3 tick data needed to verify true smoothness; `motion_done COMPLETE` alone is not proof.

## 4. Gate policy

Record **PASS/FAIL/NOT TESTED** for every case. Report only observed gameplay passes. Existing C2 Navigation Full Quality Gate and earlier pending tests stay open. Do not push GitHub before user confirmation.
