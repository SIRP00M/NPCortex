# NPCortex C2-F2 Fix #1 — Windows Forge Acceptance Checklist

**Use this ZIP extracted over your development repo only after making a backup.** Test on a copy of your world. No need for backend/Ollama for a fresh operator-only Follow session; keep your original backend database if earlier C0 journal reconciliation is in progress.

## Build and automated tests (PowerShell)

```powershell
cd F:\AI\NPCortex
.\scripts\test-c2-f2-fix1-continuous.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Script requires Temurin 17. Local backend regression was performed separately. A *successful* Windows build and gameplay are still necessary.

## Live gameplay

1. If Yui isn't spawned: `/npcortex c1 spawn yui`
2. On flat ground: `/npcortex c2 follow yui Dev` (replace Dev with the exact online Minecraft player name).
3. Walk diagonally, zigzag and draw a wide circle; watch legs/body and whether turns occur while moving. Check `/npcortex c2 followstatus yui` and `/npcortex c1 motion yui` during movement.
4. Run 10–20 blocks in a safe, loaded flat area; Yui must close the gap via rolling local follow instead of stopping permanently at >8 blocks. The path may pause safely at unknown/unloaded terrain.
5. Place a 2-block-high wall and walk around it; no corner clipping. If obstacle added while walking, bounded failure/replan is permissible; no phantom success.
6. `/npcortex c2 unfollow yui` in mid-WALK; ensure Yui stops and stays stopped. Immediately reissue follow, then cancel with `/npcortex c2 cancel yui`; ensure no stale motion.
7. Independently retest `/npcortex c2 navhier yui <X> <Y> <Z>` and `/npcortex c2 navigate yui <X> <Y> <Z>` (integer X,Z; Y may be fractional) including flat, jump-up and safe-descend fixtures from earlier Sprints. C0 backend must be running for actual action-owned `navigate`.
8. In a separate disposable test world, test actual melee knockback while Yui follows, Save&Quit/rejoin mid-follow, target logout/dimension change, and 1/3/5/10 NPC run; confirm no old work replay, no unexpected teleport or livelock.

## How to report

- Windows Forge build: PASS / FAIL (attach first compiler error if failed).
- Flat diagonal while moving/turning: PASS / FAIL (video more useful than static log).
- >8 blocks rolling Follow: PASS / FAIL.
- Wall/corner and terrain changes: PASS / FAIL.
- Unfollow and cancel: PASS / FAIL.
- Combat / vertical primitive / restart regressions: PASS / FAIL / NOT TESTED.
- Actual Forge MSPT / packet rate for 1/3/5/10 NPC: measured values or NOT TESTED.

**Expect:** fewer short `C1-P1 motion_done` receipts and no burst of INFO `ROTATION_SYNC` logs. Zero rotation-log spam is instrumentation behavior, not proof of visual smoothness. A 6-target WALK chain still ends once its verified last target is reached. If it still stutters, capture a 10–15-second clip and C1/C2 status plus nearby logs; do not silently label Full Quality Gate passed.
