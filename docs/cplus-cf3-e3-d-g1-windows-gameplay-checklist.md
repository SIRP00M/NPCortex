# Windows Minecraft — NPCortex E3-D G1 PREVIEW ONLY

## Separate commands

Build (Temurin 17):
```powershell
cd F:\AI\NPCortex
.\scripts\dev-forge.ps1 -Task build
```

Focused Python tests (no Minecraft):
```powershell
.\scripts\test-cplus-e3-g1.ps1
```

Full backend suite (separate, optional):
```powershell
$env:PYTHONPATH = (Resolve-Path '.\backend\src').Path
python -m unittest discover -s backend/tests -q
```

Backend terminal:
```powershell
.\scripts\dev-backend.ps1
```

Minecraft terminal:
```powershell
.\scripts\dev-forge.ps1 -Task runClient
```

## Safe gameplay checks — new disposable Creative world

1. Spawn/inspect Yui if needed: `/npcortex c1 spawn yui`, `/npcortex c1 info yui`, `/npcortex debug cplus world yui`.
2. Stand on stable flat ground. Inspect Yui's block coordinates. Use a **nearby loaded target**, for example two blocks along +X and same integer Y as Yui's feet.
3. `/npcortex debug cplus taskmovepreview yui <x> <y> <z>` — replace placeholders with real numbers (do not enter brackets). Expect `E3 G1 PREVIEW_ONLY move_to ... no motion, no world mutation, not queued`. Yui must NOT move.
4. `/npcortex debug cplus taskqueue yui` should show **no additional task** from the E3 preview. Existing E1/E2 tasks must be unchanged.
5. Negative: target more than 6 blocks away → HOLD. Target chunk not loaded → HOLD. While Follow owns Yui → HOLD. Try without operator permission → command unavailable/denied.
6. Backend stopped: preview must fail closed without motion. Restart backend and repeat manually, no auto replay.
7. Save/Quit/rejoin: preview does not start motion or inject tasks after load.
8. Regression: E1 submit/queue/cancel and E2 read-only dispatch/verify (on appropriate clean FIFO); C2 navigate/Follow and C1 inventory/lifecycle commands should work exactly as before. No Break/Place/Pickup/Use.
9. Record screenshots/output, Forge build log, focused tests, and unexpected warnings. Keep E2 multi-NPC/fault/MSPT gates pending until their actual evidence exists.

**Do not use `taskmove` yet:** it is NOT implemented in G1. E3-D G2 requires separate Quality Gate acceptance.
