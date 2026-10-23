# Windows Acceptance — NPCortex C-F1 D+ Transaction Bridge (D1–D5)

**Applies to:** `NPCortex_CPlus_CF1_DPlus_TransactionBridge_D1-D5_FullRepository_CANDIDATE.zip`.

**Important:** This stage is explicitly **SYNTHETIC / DIAGNOSTIC-ONLY**. It does NOT recover a broken-world mutation automatically. Commands cannot mine, place, collect items or change inventory. No arena is needed for the new diagnostic. Make a BACKUP of your test world and Backend SQLite file before destructive crash testing.

## A. Windows terminal tests (run from repository root)

```powershell
java -version
python --version
.\scripts\test-cplus-f1-dplus-transaction-bridge.ps1
.\scripts\test-cplus-f1-sqlite-windows-fix1.ps1
.\scripts\test-c2-airborne-target-projection.ps1
.\scripts\dev-forge.ps1 -Task build
```

Expect the bridge Java checks, Java→Python binary-hash parity, Python bridge tests, D+ SQLite tests, C-F0, C2 backend tests and navigation Follow regression all PASS. Python **3.13** and Temurin **17** preferred. Backend/Java regression already passed here on Linux, but Forge compilation remains **PENDING** pending your machine.

## B. Start local Backend + game with same shared token

**Security:** Generate a high-entropy random secret of at least 24 characters, don't share/commit it. The same value must be in both processes. Use one PowerShell terminal for server, another for game. For example, replace the placeholder with your own long random secret:

```powershell
# Terminal 1: start backend
$env:NPCORTEX_CPLUS_F1_TOKEN = '<YOUR_RANDOM_SECRET_32PLUS_CHARACTERS>'
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
python -m npcortex_backend
```

```powershell
# Terminal 2: game, same token
$env:NPCORTEX_CPLUS_F1_TOKEN = '<THE_SAME_RANDOM_SECRET_32PLUS_CHARACTERS>'
.\scripts\dev-forge.ps1 -Task runClient
```

Use backend address `http://127.0.0.1:8765` in NPCortex config. The bridge deliberately rejects remote origins. If Ollama is offline, this diagnostic transport should still work because it does not ask Qwen to make decisions.

## C. In-game tests (operator level 2)

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus inspect yui
/npcortex debug cplus syncstatus yui
/npcortex debug cplus sync yui
/npcortex debug cplus syncstatus yui
```

`sync` delivers **up to 8 immutable journal events per invocation**. Repeat it until `pending=0`. It must not spawn extra NPCs, move the NPC, change blocks/inventory, start a C2 task, or report a world effect. `syncstatus` is a no-disk-read cached status. `inspect` remains a local journal diagnostic.

**Expect:** `READY`, `transferred` positive on first sync, later `pending=0`. Repeated `sync` must not produce duplicate SQLite inbox rows or new action execution. `probe` is a durable synthetic INTENT+CANCELLED pair (FENCE when new epoch).

## D. Backend offline / restart & lost replies

1. Stop the backend process (not Minecraft). Issue `/npcortex debug cplus probe yui` followed by `/npcortex debug cplus sync yui`. Expect `HOLD` without world change; local `/npcortex debug cplus inspect yui` must stay `READY`.
2. Restart backend with **the same token and database file**. Execute `sync` again and check it reaches `pending=0`. Backend must not duplicate previously accepted Event IDs.
3. Save & Quit Minecraft, reopen world and repeat `inspect`, `probe`, `sync` and `syncstatus`. Expect previous Actions preserved and a **new Actor Session Epoch** on first fresh probe. No stale action execution.
4. For fault-injection beyond these manual checks, use only a disposable world/db copy: pause/kill during HTTP send, before SQLite response, during local checkpoint, and inspect the recovery state. A missing cursor may cause harmless idempotent redelivery; a damaged cursor or journal must HOLD, **never auto-reset**.

Backend read-only recovery inspection is also available to an authorized client as `POST /v1/cplus/f1/recovery` with JSON `{"world_id":"<WORLD-UUID>"}` and `X-NPCortex-CPlus-Token`. **Do not mistake journal classification for verified world effects.**

## E. Optional safe flat Follow regression arena

In a NEW Creative Superflat world, stand where the center should be and run (these commands overwrite existing blocks in that area):

```mcfunction
/fill ~-15 ~-1 ~-4 ~15 ~-1 ~4 minecraft:stone
/fill ~-15 ~ ~-4 ~15 ~5 ~4 minecraft:air
/npcortex c2 follow yui Dev
/npcortex c2 followstatus yui
/npcortex c2 unfollow yui
```

Replace `Dev` with the player's name. Repeat grounded follow, Creative flying follow (no zigzag), cancellation, C1 inventory replay and basic 3D Navigation from previous C-F0 checklist. The bridge must not change any prior behavior. No requirement to create this arena solely for C-F1 bridge sync.

## F. Acceptance checklist

- [ ] D1 Java ↔ Python envelope/hash parity PASS (Windows JDK 17/Python 3.13)
- [ ] D2 durable Backend inbox, duplicate ACK, receipt confirmation and world/actor fencing PASS
- [ ] D3 Minecraft `probe`, `sync`, `syncstatus`: bounded delivery and retry after backend offline PASS
- [ ] D4 read-only coordinator classifies latest synthetic Action state without replay PASS
- [ ] D5 invalid token, wrong world, gap, stale epoch, corrupt hash, missing ACK fail-closed PASS
- [ ] Save/Quit/rejoin: epoch rollover + sync cursor survives without duplicate side effects PASS
- [ ] Forge compile/build PASS (Temurin 17)
- [ ] C1/C2 Gameplay, Follow including flying, Inventory, Cancellation regressions PASS
- [ ] Actual 1/3/5/10 NPC MSPT, queue latency, IO pressure measured (do not infer from Java tests)
- [ ] Process-kill / true world-side-effect recovery remain **PENDING** until explicit scope and tests

**Please share:** Windows terminal results + Minecraft screenshots (`inspect`, `sync`, `syncstatus` before/after Restart) and any failed cases. No GitHub actions are needed.
