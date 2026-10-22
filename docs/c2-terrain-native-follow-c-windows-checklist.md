# NPCortex C-TN — Terrain-Native Follow Windows Gameplay Checklist

**Baseline:** C-3D Full Repository Candidate. **Expected:** safer terrain selection and less oscillation on uneven ground; not a promise of route success across an unknown/unloaded ravine. **Do not GitHub push until accepted.**

## 1. Build + regression (Windows PowerShell, project root)

```powershell
java -version
.\scripts\test-c2-terrain-native-follow-c.ps1
.\scripts\test-c2-persistent-3d-c.ps1
.\scripts\test-c2-persistent-follow-c.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

`BUILD SUCCESSFUL` is required; if it fails send exact compiler error. Previous Java-only pass does NOT certify Forge. Optional: `PYTHONPATH=backend/src python -m unittest discover -s backend/tests -p 'test_c2*.py' -q` when backend interpreter is configured.

## 2. In Minecraft (replace Dev with your player name)

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/npcortex c1 motion yui
```

When stopping, use `/npcortex c2 unfollow yui`. To diagnose an unexplained stop, capture `followstatus` **before** moving closer, then capture `c1 motion` and server logs. New diagnostics: `terrainDecision`, `lastFailure`, `terrainSelections`, `terrainReprobes`, `detourHops`. `VERIFIED_BOUNDED_LATERAL_DETOUR` means a graph-verified sideways corridor, not a promise the target can be reached beyond the scan radius.

## 3. Fixtures and acceptance

- [ ] **TN-01:** open field, stationary target ~10–15 blocks, no unwanted lateral zigzag; route physically reaches stop ring.
- [ ] **TN-02:** player walking straight 30–50 blocks, persistent appends still increase; no repeated short-ticket stop/start regression.
- [ ] **TN-03:** steep ravine / ridge from screenshots, compare previous C-3D; safe terrain route chosen if one is locally reachable. No cliff drop or fake path success.
- [ ] **TN-04:** a pit with a reachable *side* corridor. NPC can initially turn sideways, then resume target progress; should avoid repeated left/right flip; inspect `detourHops`.
- [ ] **TN-05:** entirely disconnected island/blocked edge. Fail closed; `PAUSED_UNREACHABLE` is allowed, no teleport/wall clipping. Rate-limited re-probes, not 20-TPS loops.
- [ ] **TN-06:** safely connected Jump +1 → Walk, verify `mixed3DStarts`, grounded landing; no false success.
- [ ] **TN-07:** STEP_UP +0.5 / STEP_DOWN -0.5 / SAFE_DESCEND -1 + slab/stair orientations; especially STEP_DOWN previously unverified.
- [ ] **TN-08:** change a block after the path was planned or before landing; stage must refuse unsafe movement and re-probe rather than using stale support.
- [ ] **TN-09:** hazards (berry bush, cobweb, damaging blocks), unloaded chunks and partial supports; risk-aware detours and no forced chunk loads.
- [ ] **TN-10:** unfollow and C1 cancel while moving/turning/airborne; no stale Motion Token resurrection or automatic follow restart.
- [ ] **TN-11:** combat knockback, death, unload, player offline/different dimension; correct priority, safe cleanup, no movement after cancellation.
- [ ] **TN-12:** Save & Quit / rejoin: same NPC identity, no old Follow Session auto-resumed.
- [ ] **TN-13:** normal `/npcortex c2 navigate yui x y z` after unfollow, old flat + 3D navigate/cancel regressions.
- [ ] **TN-14:** 1, 3, 5, 10 NPC: MSPT before/after, rolling planning latency, no starvation or unsafe Owner overlap.

## 4. Reporting template

`TN-##: PASS/FAIL/NOT TESTED | terrain fixture | followstatus | c1 motion | exact timestamp and server log excerpt`. Compare Yui's path before/after especially near the ravine and send a 5–15 second clip for visible zigzag. Never mark a skipped test PASS.
