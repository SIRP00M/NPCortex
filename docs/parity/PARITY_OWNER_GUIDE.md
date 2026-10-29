# Parity gameplay campaign - Owner guide (one session)

Everything below is PENDING until you run it. Use a **throw-away** world (the pack builds, fills and clears blocks and kills dropped items nearby; the installer refuses world names without "Parity"). Cheats on, difficulty normal, game mode survival. The pack uses its own NPC `Parity` (and `Parity1..10`), not Reika/Mika.

## 0. Before Minecraft (PowerShell, automatic)
```powershell
scripts\parity\run-parity.ps1 -Mode Preflight        # environment + pack lint (read-only)
scripts\parity\run-parity.ps1 -Mode Auto             # build + 4 GameTest namespaces + Data Pack execution + both real-backend suites (~6 min)
scripts\parity\run-parity.ps1 -Mode Install -WorldName NPCortex_Parity_Test   # create the world in-game first (cheats on), then install
```
`-Mode Auto` already loaded this exact pack into a real Minecraft server (GameTest world), checked that every function registered and executed each scenario's setup -> check against a headless avatar. That is **harness evidence, not your acceptance**: some verdicts are trivially true there (T17 has no real reload, T19 only sees the initial ring, T25 only sees the entity gone).

Start the game with the freshly built mod (`scripts\dev-forge.ps1 -Task runClient`) and open the world.

## 1. In game
`/reload`, then `/function npcortex_parity:guide`. For each test: `/function npcortex_parity:setup/tNN` -> (if it says so) `/function npcortex_parity:setup/tNN_stage2` after the printed wait -> `/function npcortex_parity:check/tNN`. At the end `/function npcortex_parity:status`. Run `/function npcortex_parity:reset` between tests (it revives a dead NPC first, then despawns it - never despawn a DEAD NPC by hand: it stays inactive).

| Do this | Tests | Manual part |
|---|---|---|
| automatic after setup (wait the printed time) | T01 T02 T03 T04 T05 T06 T09 T12 | T12: after the check, `/npcortex c1 respawn Parity` then `check/t12b` |
| two-step (setup, wait, stage2, check) | T04 T06 T10 T11 T13 | none - stage2 triggers the NPC action (`npcortex c1 act ...`) |
| Save and Quit in the middle | T17 | Save and Quit between stage2 and check; also look at `world/stats` and `world/advancements` for the NPC UUID |
| needs the PAP/native allow-list (`scripts\pap\install-pap-allowlist.ps1`) and chat actions | T07 T08 T14 T15 | say `Parity, break that log` etc., then `confirm` |
| operator walks away / long wait | T19 T20 T21 T22 T23 T25 | keep >150 blocks from the NPC; T19: `/npcortex c2 navlong Parity x y z` |
| observation (you judge) | T18, T21, T24 (`/forge tps`) | |

**Your human-oracle half:** for T01-T06, T09, T10, T13 do the same thing yourself (jump into the pit, eat a golden apple, sleep in the bed, ...) and compare damage, timing and food values with what the pack reports.

**What changed since the last guide (decisions already taken, 2026-10-29):** touch pickup is ON (T05 expects the item to be taken); the NPC is a `PlayerList` member (`@a`, `@p`, `@r`, `@e[type=player]` and `/list` now include it; the pack still addresses it with the tag `npcortex_Parity` because its profile name is internal, `NParity_xxxxxx`); stats and advancements are saved in vanilla's `world/stats` and `world/advancements`; attack / use item / sleep / containers exist as operator commands `npcortex c1 act <name> ...` (they are NOT yet AI/ledger actions).

## 2. Evidence
```powershell
scripts\parity\run-parity.ps1 -Mode Collect            # reads forge-mod\run\logs\latest.log, writes docs\parity\evidence\matrix-<time>.md
scripts\parity\run-parity.ps1 -Mode Remove -WorldName NPCortex_Parity_Test
```
`/function npcortex_parity:status` writes `PARITY|STATUS|Txx|<status>` with `say`, which Collect parses. Statuses: PASS / FAIL / PENDING / NOT TESTED. A check that finds no NPC says NOT TESTED, never PASS.

## 3. Known limitations you will see
- The NPC's TAB / `/list` name is the internal profile name (`NParity_xxxxxx`), not `Parity`.
- No real Connection: the NPC cannot chat-sign; chat is the existing deterministic adapter.
- `PlayerList.saveAll` also writes a `playerdata/<uuid>.dat` for the NPC that nothing reads (C1 SavedData stays the inventory/position owner).
- The server's player count and `max-players` include NPCs.
- The AI/chat/ledger route for attack, use_item, sleep and containers is not built; only the operator command exists.
- Vanilla portal travel is still refused (`c1 dimension` is the controlled transfer).
