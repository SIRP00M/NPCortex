# C-F2 D P1–P5 Windows Gameplay Acceptance — read-only

**Before:** backup the full working world, keep C-F1 Clean Baseline result; use a new Creative test world or backup. Do not merge ZIP into dirty repo (previous old tests survived overwriting).

### 1. Run tests

```powershell
cd F:\AI\NPCortex
.\scripts\test-cplus-f2-readonly.ps1 -IncludeFullBackend -IncludeForgeBuild
.\scripts\test-cplus-f1-final-gates.ps1 -IncludeForgeBuild
.\scripts\test-c2-airborne-target-projection.ps1
```

C-F2 contract Python/Java, full backend, C-F1 and Forge build must all exit 0. If Forge compile fails, share the **first** compiler error and exact file:line; do not assume green based on isolated Java tests.

### 2. Start Backend and Game (no token)

Window A:
```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
.\scripts\dev-backend.ps1
```
Window B:
```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
.\scripts\dev-forge.ps1 -Task runClient
```

### 3. Minecraft console/chat

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus world yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus sync yui
/npcortex debug cplus audit yui
```

**Expected:** `C-F2 SERVER READ ONLY v2` prints `observed=27` (or fewer OBSERVED but `unknown > 0`), center block ID/status, `break=UNKNOWN` for regular loaded blocks and evidence UUID/tick. Full v2 data is logged server-side under `NPCortex C-F2 operator read-only snapshot:` with UUID, cell entries, status and permission decision. C-F1 journal/sync/audit remain unchanged; C-F2 `world` creates **no new journal event and no World Mutation**.

### 3b. Verify actual Java-emitted JSON against Python v2 contract

After using `world yui` in game, run this in PowerShell:

```powershell
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
python .\scripts\verify-cplus-f2-observation.py .\forge-mod\run\logs\latest.log
```

Expected: `PASS Java->Python C-F2 v2 JSON contract parity`. A failure is **not** an acceptable C-F2 gate: send error + redacted snapshot log line. No HTTP endpoint is used; only locally emitted diagnostic JSON is inspected.

### 4. Manual scenarios to verify

1. Place a vanilla oak log under NPC feet (via operator/player in the test world, NOT via NPC), execute `world` again: ID changes as expected, previous evidence UUID is not reused.
2. Try water, chest, door, slabs, protected/modded block states; properties are redacted to BlockState fields only (never chest NBT or player names). No mutation or inventory change when using `world`.
3. In the Nether and Overworld, spawn/relocate by supported C1 test commands, then observe; world/dimension should match live avatar, not stale cached identity.
4. Save/Rejoin: new `session_id` should be recorded in server log for new server session; old JSON must not be reused after load.
5. Unknown chunk and out-of-build-height cases should never be interpreted as observed air; for diagnostics around boundaries use disposable test world and compare log statuses.
6. Spawn 1, then 3, 5, and 10 NPCs (using supported `c1 spawn` and unique names in test world) and manually run `world` per NPC; profile total server MSPT from F3 Debug screen/server profiler under baseline and worst-case populated scenes. Record median/p95/max, JVM memory, entity counts, and any lag spikes. **No hard performance thresholds are claimed by synthetic tests.**
7. Run C2 grounded and Creative flying Follow/Unfollow/Cancel plus Inventory persistence and rejoin smoke checks to ensure unchanged behavior.

### 5. Fail-closed checks

- Dead/despawned/unknown actor: `world <name>` must HOLD, not read arbitrary player or chunk.
- More than 16 KiB payload: reject observation; do not accept partial JSON or claim unknown blocks to be air.
- Backend offline: C-F2 local read-only `world` should still work; C-F1 sync/audit still HOLD as before, and recover when Backend returns.
- A permission UNKNOWN **never authorizes** break/place/use actions; no new skill in planner is advertised.

**Submit:** PowerShell exit summary, screenshot of C-F2 `world` output, representative redacted JSON line from server log, any Forge errors, measured MSPT 1/3/5/10, C1/C2 regression results. Do not share private credentials.
