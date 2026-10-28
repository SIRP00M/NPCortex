# M03 Live Evidence Watch — Game + Backend (read-only)

This overlay targets `NPCortex_M03_AllTests_FunctionLoadFix_FullRepository_CANDIDATE.zip` and its existing `npcortex_m03_suite` datapack + Forge temporary bridge. It does not change Forge/Backend gameplay behavior.

## What it does

- Scans `New World (2)/data/npcortex/m03-evidence/*.json` and the durable `transport/*.json` records.
- Copies each new Forge snapshot, performance sample or fault record **immutably** to a separate run folder.
- At every checkpoint, checks loopback `/health` (no token, GET only) and opens **the explicitly selected SQLite database with `mode=ro` + `PRAGMA query_only`** and exports only world/actor-correlated G2 and task-correlated G3 metadata (not general conversations or credentials).
- Captures the changing database state during OFFLINE/ONLINE tests; a DB read error becomes evidence, not an artificial pass.
- Produces `REPORT.md`, `final-report.json`, JSON checkpoints and an optionally zipped evidence bundle. Reports NEVER assert Gameplay PASS automatically.
- Rejects an existing output folder to protect the previous collection.

**Limits:** The Forge snapshots themselves come from the already-installed M03 test bridge and capture live in-game state on the server thread. This external script only watches those files and SQLite; it cannot compel Minecraft to snapshot without `/function` or `/npcortex ... m03 snapshot yui`. It does not start/stop backend processes, click menus, or simulate Save & Quit. Periodic sampling is **not an atomic distributed snapshot**. A missing DB G2 row can mean an intentionally never-handed-off request or the WRONG database; do not infer data loss.

## Installation (on a copy of the repository)

Copy `scripts/m03_evidence_watch.py` and `scripts/m03-evidence-watch.ps1` from this overlay to `C:\path\to\NPCortex\scripts\`. Existing files are not overwritten. No Gradle rebuild required: the Forge bridge/datapack already exist.

Open a **new PowerShell**. Confirm which DB is in use in the **actual Backend terminal** (`NPCORTEX_DB_PATH` override, otherwise default is generally `backend\data\npcortex.sqlite3`). Use that path explicitly; do not auto-pick a DB just because it exists.

```powershell
cd C:\path\to\NPCortex
$Db = 'C:\path\to\NPCortex\backend\data\npcortex.sqlite3' # ONLY if confirmed as the live backend database
.\scripts\m03-evidence-watch.ps1 -WorldName 'New World (2)' -Database $Db -BackendURL 'http://127.0.0.1:8765'
```

Keep the watcher running in its own PowerShell while testing. In Minecraft, after every meaningful action / phase use **the existing** in-game suite menu or this snapshot command:

```mcfunction
/function npcortex_m03_suite:menu
/npcortex debug cplus m03 snapshot yui
```

Wait for **`snapshot saved`** in chat before stopping/restarting Backend or quitting the world; snapshots are asynchronous. For Test 8, wait for the snapshot before Save & Quit, then after rejoin enable the M03 bridge again and snapshot. For Test 10 perform the actual 1/3/5/10 measurements and capture profiler data; the watcher collects existing `performance-*.json` if generated.

When all tests are finished, press `Ctrl+C` in the watcher terminal. It writes the report and creates `M03-LiveEvidence-...zip` next to the run folder. The watcher never writes inside the World or DB.

## Manual proof still required for acceptance

| Test | Required real-world proof, in addition to snapshots |
|---|---|
| 6B | Operator-stopped and restarted **same backend process configuration**; online/offline/online DB states; retained outbox; unchanged C2 goal and no replay |
| 7 | Fault `AFTER_SUCCESSFUL_RESPONSE` (committed then response lost), correlated task/attempt and Backend terminal state; no duplicate movement |
| 8 | Real Save/Quit and Rejoin; stable world/actor/profile; new session; before/after metadata comparison and legitimate new task admission when allowed |
| 9 | C1 lifecycle, actual C2 navigation, physical Follow, inventory persistence/equip/swap; **empty-slot unequip is HOLD** until API exists (not a reason to fake success) |
| 10 | 1/3/5/10 NPC on real server, ≥3 rounds per count, same workloads, actual profiler/MSPT & hardware; compile alone does not pass |

**Important:** Backup your test World and DB before fault testing. No deletes, force-release, blind replays, or GitHub changes are performed by this kit. Review files before sharing because a Forge snapshot contains inventory and location observations.

The health probe only proves that something responds on the configured loopback URL; it does NOT prove the service owns the selected database. World/Actor/Task correlation is checked independently. For the isolated owned-backend on port 18765 use `-BackendURL 'http://127.0.0.1:18765'`.
