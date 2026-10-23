# NPCortex C-F1 D+ — Windows Acceptance Checklist (STAGED)

## Windows automated tests (run in repository root)

```powershell
.\scripts\test-cplus-f1-dplus.ps1
.\scripts\test-cplus-f0-contracts.ps1
.\scripts\test-c2-airborne-target-projection.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Use Eclipse Temurin JDK 17. The first runner checks only isolated C-F1 synthetic Ledger/SQLite/effect contracts; it **does not mutate the world**. If Forge Build fails, capture full console/log. Full Backend Suite and true crash injection remain separate.

## Optional regression arena (CREATIVE TEST WORLD ONLY)

Stand in a chosen clear area; keep your position fixed while using the following destructive `/fill` commands. They remove blocks in the chosen region; never use in an important survival world:

```mcfunction
/fill ~-30 ~-1 ~-5 ~30 ~-1 ~5 minecraft:stone
/fill ~-30 ~ ~-5 ~30 ~5 ~5 minecraft:air
```

Replace `Dev` with actual player name. The exact commands below are *existing* commands, not newly registered C-F1 actions:

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/gamemode creative
/npcortex c2 unfollow yui
/npcortex c1 inventory yui
/npcortex c1 ledger yui
```

### Acceptance

- [ ] C-F1 Java synthetic crash/duplicate/corrupt tests PASS on Windows JDK17
- [ ] C-F1 Backend tests PASS
- [ ] C-F0 Java/Backend contract regressions PASS
- [ ] Forge build PASS
- [ ] Grounded Follow straight 30–50 blocks remains stable
- [ ] Creative Flying Follow retains no zigzag regression
- [ ] Unfollow stops and leaves no stale motion
- [ ] Inventory/Receipt Replay remains correct, including Save & Quit/Rejoin
- [ ] No new C+ gameplay/world mutation commands unexpectedly registered
- [ ] Record any Forge/Backend/log errors separately

**Not testable in Minecraft yet:** real D+ `IN_DOUBT` and recovery for dropped items, crash injection between effect and save, multi-NPC effect provenance, permission/claim safety, async durability latency, 1/3/5/10 NPC MSPT. Those belong to later scoped implementation/quality gates. Do not treat the original user's C-F0 PASS as these new C-F1 results.
