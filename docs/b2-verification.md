# B2 — Windows Build, Qwen and Minecraft Verification

**Scope:** Multi-Actor Dialogue Engine in the Python backend; no Forge entity/GUI
and no autonomous NPC behavior. Tests using `/npcortex ask` only verify legacy API
compatibility, not the B2 multi-NPC engine.

## 0. Stop backend and back up your existing B1 database

1. Close the B1 backend with `Ctrl+C` before installing B2.
2. **Do not delete** `backend\data\npcortex.sqlite3`; it contains B1 identity/history.
3. Back it up **after stopping the backend** (and any other process using the DB):

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
Copy-Item '.\backend\data\npcortex.sqlite3' ".\backend\data\npcortex-B1-backup-$stamp.sqlite3" -ErrorAction Stop
```

If you extract the B2 Full Repository ZIP into a **new folder**, the ZIP does
**not** contain your old SQLite DB. To preserve B1 World/Actor/Conversation data,
copy the *backed-up* B1 database to the same relative `backend\data\npcortex.sqlite3`
inside the new B2 repository **before starting** its backend. Example from the
B2 repository root (replace the source path with your actual B1 backup):

```powershell
New-Item -ItemType Directory -Force '.\backend\data' | Out-Null
Copy-Item 'C:\path\to\NPCortex-B1\backend\data\npcortex-B1-backup-YYYYMMDD-HHMMSS.sqlite3' `
  '.\backend\data\npcortex.sqlite3'
```

Do not overwrite a B2 database you have already used without explicitly backing
it up. If you want an entirely new test world with no B1 history, leave the file
absent and the backend will create a fresh v2 database.

If the database does not exist (fresh installation), skip the backup step.
The path above is the default; if you customized `NPCORTEX_DB_PATH`, back up **that
path**, not the default. An existing v1 database migrates to v2 on B2 startup.
Reverting to B1 requires **restoring the v1 backup**; old B1 code refuses v2.

**IMPORTANT:** Stop Backend before restoring the backup. Never overwrite or delete
a database containing gameplay/player data merely to make a test pass.

## 1. Automated Python Tests and JDK 17 Forge Build

From the extracted `NPCortex-Development` repository root in PowerShell:

```powershell
python --version
python -m unittest discover -s backend/tests -v
java -version
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
```

Expected: all Python tests `OK`; Forge `BUILD SUCCESSFUL` with **JDK 17**. If
Gradle cannot download, report the exact exception and keep the Forge Quality
Gate **unverified**, not passed. Backend requires Python 3.10+.

## 2. Boot local Qwen and backend

```powershell
ollama list
ollama pull qwen3:8b   # only if the model is not already installed
.\scripts\dev-backend.ps1
```

In a different PowerShell, check:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
```

Expected: `status = ok`, `model = qwen3:8b`. `/health` does not test Ollama.

## 3. Real Qwen, 3-NPC + player smoke test

Leave the backend running. In a second PowerShell at the repository root:

```powershell
.\scripts\test-b2-dialogues.ps1
```

This creates **one new test world** plus Yui1, Guide, Yui2 with distinct Actor
UUIDs, then a shared dialogue of four participants: player, Yui1, Guide, Yui2.
Each generated actor turn calls real Qwen. Expected checks:

- Backend online; two Yui instances have distinct UUIDs.
- Four registered dialogue participants.
- A player turn followed by three AI-generated NPC turns; speaker IDs and seq
  `(1,2,3,4)` agree with the selected speaker; only four committed turns.
- Repeating the same `request_id` yields `replayed = True` and does **not**
  generate an additional turn.
- `status = closed` at the specified four-turn budget.
- Replies should resemble dialogue by the selected character, but exact wording
  varies; verify **speaker UUID and transcript**, not whether Qwen obeyed tone.

The script prints `DIALOGUE: <UUID>`; copy the UUID for persistence testing.

## 4. Backend restart and SQLite persistence

Stop backend (`Ctrl+C`) and start it again using the **same database path**.
Then, in your PowerShell terminal, run using the ID printed in step 3:

```powershell
.\scripts\test-b2-dialogues.ps1 -VerifyDialogueId '<your-dialogue-UUID>'
```

Expected: `PASS: Persisted dialogue transcript exists after restart`, four
ordered messages with speaker IDs, `status=closed`. Repeating an identical
`request_id` on the closed dialogue should return the saved response, not call
Qwen. Also verify a previous B1 `GET /v1/conversations/<id>` still returns its
pre-upgrade transcript; **B1 data must not disappear**.

## 5. B1 / Forge compatibility regression

Start the Forge client with JDK 17:

```powershell
.\scripts\dev-forge.ps1 -Task runClient
```

In a cheats-enabled Single Player world:

```text
/npcortex status
/npcortex ask yui Hello Yui!
/npcortex ask guide How are you?
/npcortex ask invalid_character Hello!
```

Expected: Backend online; Yui/Guide reply; invalid character causes an HTTP
error message, **without game crash**. Forge commands still use legacy
`/v1/chat`, so passing this test does not by itself prove B2 dialogue.

## 6. Errors / bounded turns (PowerShell optional)

```powershell
$base = 'http://127.0.0.1:8765'
# Check that unknown dialogue IDs fail with HTTP 404.
try {
    Invoke-RestMethod "$base/v1/dialogues/$([guid]::NewGuid())" | Out-Null
    throw 'FAIL: expected HTTP 404'
} catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) {
        'PASS: unknown dialogue gives HTTP 404'
    } else { throw }
}
```

For deeper resilience tests, intentionally stop Ollama and ask for an NPC turn
in a *new open dialogue*. Expect HTTP 502 `provider_unavailable` and **no new
saved turn**. Restart Ollama and try again. Do not manipulate or corrupt a real
player database to test SQLite failure; automated tests use temporary DBs.

## B2 acceptance / handoff

- [ ] Python backend regression tests pass locally.
- [ ] Existing B1 SQLite v1 DB safely migrated and original rows retained.
- [ ] JDK 17 Forge build passes.
- [ ] Actual Qwen replies for selected actors in shared multi-actor dialogue.
- [ ] UUID speaker identities and ordered turns match requests.
- [ ] Idempotent replay, max-turn close and restart persistence pass.
- [ ] Forge legacy chat still works; no crash.
- [ ] Errors handled without transcript corruption.
- [ ] User reports gameplay/API test acceptance and remaining unverified items.

**No Test, No Push**. GitHub Commit/Push/Merge belongs entirely to the user.
