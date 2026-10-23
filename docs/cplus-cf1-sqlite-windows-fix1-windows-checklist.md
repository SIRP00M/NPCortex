# NPCortex C-F1 D+ Fix #1 — Windows Acceptance Checklist

**Purpose:** prove temporary SQLite test connections close on Windows/Python 3.13. **No Minecraft gameplay changes** in this fix. Do not use a production save as the test arena.

## A. Fix #1 regression (PowerShell, repository root)

```powershell
python --version
.\scripts\test-cplus-f1-sqlite-windows-fix1.ps1
```

Expected: **C-F1 D+ Backend 15/15 PASS**, **C-F0 Backend 15/15 PASS**, **C2 Backend 16/16 PASS**, **Persistence 6/6 PASS**. No `ResourceWarning: unclosed database`, `PermissionError [WinError 32]` or `tearDown` errors. In Python 3.13 confirm the script prints the actual Python version.

## B. Unchanged baseline regression

```powershell
.\scripts\test-cplus-f1-live-diagnostics.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Temurin JDK 17 is needed for project Java/Forge runners. Fix #1 itself does not register or change any commands. The following are **existing** C-F1 Stage 2 diagnostic commands:

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus inspect yui
```

Expected: `probe` is **synthetic-only** and its durable ACK ultimately reports a cancelled/no-world-effect diagnostic action. Yui does not move and no items or blocks change because of that probe. Save & Quit, rejoin the **same** test world, and repeat `inspect`; existing action records remain available. See `docs/cplus-cf1-live-diagnostics-windows-checklist.md` for the full stage 2 checklist.

## C. Optional flat Follow regression arena (destructive — TEST WORLD ONLY)

Stand still on flat ground while executing (relative to feet):

```mcfunction
/fill ~-30 ~-1 ~-5 ~30 ~-1 ~5 minecraft:stone
/fill ~-30 ~ ~-5 ~30 ~5 ~5 minecraft:air
```

Replace `Dev` with your real player name:

```mcfunction
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/npcortex c2 unfollow yui
/npcortex c1 inventory yui
```

Expected: original Follow/Unfollow and Inventory behavior unchanged, including Creative flight Follow without the old zigzag regression.

## Acceptance / pending

- [ ] Windows Python **3.13** displayed and targeted SQLite regression **15/15** PASS
- [ ] C-F0 + C2 + Persistence Backend focused regressions PASS
- [ ] No Windows `WinError 32`, `ResourceWarning` or temporary database cleanup error
- [ ] Existing C-F1 full live diagnostics script PASS on Windows Temurin 17
- [ ] Forge build PASS
- [ ] Optional gameplay regression PASS (not required to prove test-only close fix, but useful for release QA)

**Not established:** real Crash/Power-Loss World Recovery, outbox network integration, multi-NPC MSPT, any World Mutation Skill, or full Backend test suite.
