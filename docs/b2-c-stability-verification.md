# NPCortex B2-C — Windows Stability Verification (candidate)

## Important boundaries

This is **B2 Stability Fix C**, not a new B3 sprint or C autonomous world
simulation. `WorldStore`/`DialogueStore` now use an explicit SQLite persistence
unit of work. **Schema version stays 2; no migration to v3**. Existing B1/B2
HTTP routes, character packs, Forge Java sources, and dialogue scripts remain.
The Python tests run without starting Ollama; the later dialogue script exercises
real local Qwen. User acceptance and successful Windows execution are pending.

## Before replacing old files

1. Stop the NPCortex backend (`Ctrl+C`) and Minecraft client. Keep your existing
   repository/database as a rollback copy. Do **not** delete or overwrite the
   only copy of `backend\data\npcortex.sqlite3`.
2. Run from `F:\AI\NPCortex` or your extracted repository root:

```powershell
Copy-Item .\backend\data\npcortex.sqlite3 .\backend\data\npcortex.sqlite3.pre-B2-C.bak
```

If your database is stored at the custom `NPCORTEX_DB_PATH`, back up that path
instead. A proper backup must be taken with the backend stopped (SQLite WAL may
hold uncheckpointed data while active).

## Gate 1 — Local Python regression

Python 3.10+ supported; reproduce the observed Windows issue with the same
Python 3.13 version when possible.

```powershell
python --version
.\scripts\test-b2-stability.ps1 -Repeat 3
```

**Expected**: each run finishes `Ran 70 tests ... OK`, then `PASS: run 1`,
`PASS: run 2`, `PASS: run 3`; exit code 0. A stack trace printed by the
intentional SQLite/Model error-injection tests is normal **only when the suite
still ends in `OK`**. Watch for `ResourceWarning`, `WinError 32`, `WinError 10053`,
`FAILED`, or an unexpected nonzero exit: any of these fails the gate.

The test suite works with temporary database files and does not intentionally
modify your production SQLite database.

## Gate 2 — Backend and genuine Qwen conversation

Open Ollama and check that `qwen3:8b` is installed. Open backend from root:

```powershell
ollama list
.\scripts\dev-backend.ps1
```

In a **second** PowerShell window (same extracted root):

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
.\scripts\test-b2-dialogues.ps1
```

**Expected**: Backend status `ok`, B2 script prints ten `PASS:` lines, four
turns with distinct speaker IDs, status `closed`, and a dialogue UUID to save.
The exact Qwen dialogue text may vary; it should still comply with the speaker
and persistence checks. The existing B2 script does not automatically validate
all possible dialogue quality issues.

Stop and restart **backend only**, preserving `backend\data\npcortex.sqlite3`,
then use the UUID printed by the first command:

```powershell
.\scripts\test-b2-dialogues.ps1 -VerifyDialogueId 'PASTE-YOUR-DIALOGUE-UUID'
```

**Expected**: `PASS: Persisted dialogue transcript exists after restart`,
`4 stored turns; status=closed`, with unchanged speaker IDs and contents.

## Gate 3 — Preserve your prior B1/B2 data

In addition to the fresh script world, verify an existing B1 conversation ID
and/or B2 dialogue ID recorded **before** installing B2-C. Example:

```powershell
$base = 'http://127.0.0.1:8765'
$oldDialogueId = 'YOUR-PREVIOUS-B2-DIALOGUE-UUID'
Invoke-RestMethod "$base/v1/dialogues/$oldDialogueId" |
    Select-Object dialogue_id, status, max_turns
```

For B1 replace the URL with `/v1/conversations/YOUR-B1-CONVERSATION-UUID`.
The old records/turns should still exist. If not found, first verify you used
the same database path, rather than assuming a destructive migration.

## Gate 4 — Forge regression (JDK 17)

```powershell
java -version
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Expected: `BUILD SUCCESSFUL` with JDK 17 and a functioning Forge client.
Inside Minecraft (operator/cheats enabled), test:

```text
/npcortex status
/npcortex ask yui Hello again!
/npcortex ask guide Hello!
/npcortex ask invalid_character Hello!
```

Expected: health online, normal answers from `yui`/`guide`, and graceful
rejection of unknown character without crash. This release **does not** add
visible NPC entities, NPC-to-NPC automatic turn selection, or world simulation.

## If a test fails

Provide (1) entire test output, (2) `python --version`, (3) build output or
Minecraft log where relevant, (4) backend traceback only if a backend request
fails, and (5) whether your database path was a new temporary DB or existing
pre-upgrade DB. We will return to Audit → Options → Approval before any new
out-of-scope changes. Do not push until all agreed gates are satisfied.
