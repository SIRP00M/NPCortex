# NPCortex C0-B Acceptance Script Fix — Windows checklist

## Prepare

1. Preserve a backup of `backend\data\npcortex.sqlite3` and the old repository before replacing files.
2. Replace the working directory with the **full** ZIP in this delivery, not just an old B3/C0 script.
3. Start Ollama with `qwen3:8b` available and start the backend in PowerShell terminal A:

```powershell
.\scripts\dev-backend.ps1
```

## Verify the already-completed goal (Terminal B)

```powershell
.\scripts\test-c0-headless.ps1 -VerifyGoalId 'YOUR-GOAL-UUID'
```

Expected: the six checks for Backend, goal ID, completed status, 2 action attempts, 1 unverified status, and 1 succeeded status all pass. Replace `YOUR-GOAL-UUID` with a Goal ID actually present in your SQLite database. This verification does **not** call Qwen again and should not create a new goal. It requires the existing SQLite database from the earlier test.

If the old goal's database is not present, run the full headless test after starting Ollama and the Backend:

```powershell
.\scripts\test-c0-headless.ps1
```

Use the fresh UUID it prints to run `-VerifyGoalId` after restarting Backend.

## Targeted and full automated checks

```powershell
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_c0_windows_acceptance_script.py -v
.\scripts\test-c0-regression.ps1 -Repeat 3
```

Expected: targeted 3 tests OK; full regression 185 tests OK for each of three runs. Tests were run under Linux by the assistant; Windows PowerShell is the required final platform confirmation.

## If assertions fail

The script now prints actual action counts and a table containing `action_id`, `skill_id`, `status`, and `verdict`. Capture the full console output and Backend log. Do not delete or migrate SQLite because the earlier direct API evidence showed persistence works.

## Boundaries

No gameplay/Forge test is implied. This change does not alter Qwen prompting, runtime action verification, schemas, or player-based NPC features. User manages all GitHub operations; no push before Quality Gate approval.

## Acceptance status note

The maintainer reported that the corrected `-VerifyGoalId` script passed on Windows after the script-only patch. This is historical owner-reported evidence, not a new execution of the script or a Minecraft gameplay acceptance.
