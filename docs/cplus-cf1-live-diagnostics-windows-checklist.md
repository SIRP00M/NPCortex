# C-F1 D+ Stage 2 — Windows Gameplay Acceptance

## Automated tests (PowerShell in repository root)

```powershell
.\scripts\test-cplus-f1-live-diagnostics.ps1
.\scripts\test-c2-airborne-target-projection.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

**New in-game commands, developer-only** (require cheats/op level 2):

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus inspect yui
```

Expected: first inspect asynchronously returns `state=READY`, `actions=0` for a clean world. Each successful `probe` yields `SYNTHETIC ONLY durable CANCELLED ... epoch=N sequence=M` and `actions` increases by 1. `unresolved=0`; Yui does NOT move/consume items/change blocks. If `probe` is queued, wait for the separate ACK message; **do not treat the immediate queued message as success.**

After Save & Quit, rejoin SAME world and run:

```mcfunction
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
```

Expected: persisted `actions` count remains, and first post-rejoin ACK uses a strictly higher session epoch. The command does not retry the old probe. Existing C1/C2 commands continue unchanged.

## Optional flat regression arena (destructive; TEST WORLD ONLY)

Hold position while issuing these commands (coordinates relative to your feet):

```mcfunction
/fill ~-30 ~-1 ~-5 ~30 ~-1 ~5 minecraft:stone
/fill ~-30 ~ ~-5 ~30 ~5 ~5 minecraft:air
```

Replace `Dev` with actual player name:

```mcfunction
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/gamemode creative
/npcortex c2 followstatus yui
/npcortex c2 unfollow yui
/npcortex c1 inventory yui
/npcortex c1 ledger yui
```

- [ ] Temurin 17 pure-Java bridge tests pass
- [ ] C-F1/F0 + C2 backend focused tests pass
- [ ] Forge 1.20.1 build succeeds
- [ ] `inspect` shows READY without changing world
- [ ] Two synthetic `probe` requests receive separate durable CANCELLED ACKs
- [ ] Save/Rejoin retains history and increases fencing epoch
- [ ] C1 Spawn/Inventory and C2 Follow/Unfollow work unchanged
- [ ] Creative-flying Follow does not regress to zigzag
- [ ] No automatic world-effect replay after rejoin

**Not covered:** true process kill / power loss, disk-full Windows injection, passive pickup, real Block/Inventory world mutation recovery, backend live outbox transport, real MSPT with 1/3/5/10 NPCs. Those require future scope and evidence; do not mark them PASS because synthetic tests pass.
