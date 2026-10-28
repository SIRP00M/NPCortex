# E3-D G3-1 — Windows Gameplay & Quality Checklist (JDK 17)

## Clean extraction + do not erase previous evidence
- Unzip candidate into a new directory; **do not** overwrite existing world/SQLite/config; keep old G2 attempts in their original database for future reconciliation. Test in disposable Creative world with Cheats/Operator enabled.
- Verify which repo folder contains `forge-mod`, `scripts` and `CHANGELOG.md`. Open separate PowerShell terminals.

**A — Forge build (standalone, actual Temurin 17)**
```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task build
```
Record `BUILD SUCCESSFUL` or complete compile errors.

**B — Focused tests (independent of build/runClient/backend)**
```powershell
.\scripts\test-cplus-e3-g3-g1.ps1 -IncludeG2Regression
```
Expected Python G3-1 7 PASS, G2 regressions PASS, Java `verifyG3TerminalContract` PASS. If wanting only Python and no Java: `-BackendOnly` (not a substitute for Java gate).

**C — Full backend suite (separate; optional before gameplay, necessary to close full gate)**
```powershell
$env:PYTHONPATH=(Resolve-Path .\backend\src).Path
python -m unittest discover -s backend\tests -q
```

**D — Backend (terminal separate, keep running)**
```powershell
.\scripts\dev-backend.ps1
```

**E — Minecraft client (another terminal)**
```powershell
.\scripts\dev-forge.ps1 -Task runClient
```

## In-game G3-1 diagnostic, no motion or world changes
```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c2 status yui
/npcortex debug cplus taskmovestatus yui
/npcortex debug cplus taskmovejournal yui
```
After successful registration the journal command should display `G3-1 CONTRACT_INSPECTION` and `NOT correlated with C+ attempt; NO completion proof/ACK/fence release`. If the last C2 row is `COMPLETED`, this is **not** proof that a G2 Attempt has been closed. Run `taskmovestatus` again: any old `C2_ACCEPTED/HOLD` must remain untouched.

Negative checks: unauthorized player cannot access `debug` route; unknown NPC name is rejected; Save/Rejoin must not cause spontaneous movement or clear HOLD. Optionally inspect existing safe C2 navigation in another *disposable world*, but G3-1 does not authorize a new movement attempt/retry of an old one.

**Gate exit:** Java 17 Forge Build PASS, Java Gson fixture PASS, Python contract PASS, read-only command behavior PASS, no actor-fence release or world mutation, no C1/C2/E1/E2/C-F1/F2 regression. Record screenshots/logs. Until then G3-2 remains disabled/not implemented.
