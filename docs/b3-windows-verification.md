# NPCortex B3 — Windows Test Checklist

## 0. Data safety (do this first)

- **Stop Backend** (Ctrl+C) to flush and close SQLite. Do not modify the database while it is live.
- Save a backup of your real database (`backend/data/npcortex.sqlite3` by default) to an external backup path. SQLite migration to v3 is forward-only; older B2-C cannot reopen it.
- Extract the B3 Full Repository ZIP. If you extract to a new folder, move a **copy** of the backed-up B2-C database to `backend/data/npcortex.sqlite3` or point `NPCORTEX_DB_PATH` to the intended copy. **Never delete the original B2-C database**.

## 1. Python regression (PowerShell at repository root)

```powershell
python --version
.\scripts\test-b3-regression.ps1 -Repeat 3
```

**Expected:** `Ran 111 tests ... OK` at the end of each of the three runs, then `PASS: all 3 B3 regression runs.` Test logs intentionally include some mock error tracebacks; final summary must be OK, and there should be no `WinError 32` / `ResourceWarning: unclosed database`.

## 2. Qwen and Backend

PowerShell terminal A:

```powershell
ollama list
.\scripts\dev-backend.ps1
```

Ensure `qwen3:8b` appears in `ollama list`. Backend should listen on `127.0.0.1:8765`.

PowerShell terminal B:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
.\scripts\test-b3-social.ps1
```

**Expected:** `PASS: Backend online`, `PASS: Two Yui instances have different UUIDs`, `PASS: Social run created and ready`, requests with model-chosen NPC IDs and natural speech, `PASS: Duplicate step request is replayed`, `PASS: Journal and transcript turn counts match`, no same-speaker consecutive turns, and terminal `completed` or `stopped` within the four-step budget. Output carries `RUN` and `DIALOGUE` UUIDs. `/health` alone does not verify Qwen; actual dialogue does.

### After backend restart (verify persistence)

Stop terminal A with Ctrl+C and start it again. From B:

```powershell
.\scripts\test-b3-social.ps1 -VerifyRunId 'RUN-UUID-PRINTED-ABOVE'
```

**Expected:** the same Run ID and persisted turn sequence, consistent transcript/journal, terminal state.

### Extra: pause and resume after backend restart

```powershell
.\scripts\test-b3-social.ps1 -PauseAfter 2
```

It prints a RUN ID; restart Backend; then:

```powershell
.\scripts\test-b3-social.ps1 -ResumeRunId 'RUN-UUID-PRINTED-ABOVE'
```

**Expected:** previous two turns preserved, social run is active after restart and resumes with later `seq` values, never repeats a speaker consecutively. The script performs no database deletes.

## 3. Existing B2 API regression (Qwen real)

```powershell
.\scripts\test-b2-dialogues.ps1
```

Expected existing B2 PASS checks, four turns, same UUID on replay. Record `DIALOGUE` and verify via `-VerifyDialogueId` after restart. B2 remains explicitly chosen speaker and should work for dialogues that are **not owned by an active B3 run**.

## 4. Forge Build and Minecraft regression

Use **JDK 17**. From repository root:

```powershell
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Minecraft Single Player / Cheats on:

```text
/npcortex status
/npcortex ask yui Hello!
/npcortex ask guide Hi!
/npcortex ask invalid_character Hello!
```

Expected Backend online, AI replies without client crash, and invalid character gives a non-crashing error. No new B3 in-game NPC UI exists yet.

## 5. Report back

Provide Python test summary (3 runs), outputs of `test-b3-social.ps1` and post-restart verification (plus any 502/409 errors), and Forge build/Minecraft results. **Sprint B3 stays Candidate until the appropriate user quality gates pass**. Do not push to GitHub merely because scripted tests pass.

## Troubleshooting

- `502 provider_unavailable`: Qwen returned invalid coordinator JSON twice or Ollama failed; no fake speech committed. Retry with the same step request_id if manually invoking API; check Ollama logs and model availability.
- `409 identity_conflict`: social run ended, a non-actor attempted a step, a manual B2 request conflicts with active B3 ownership, or an interrupted step must be recovered with original request ID. `GET /v1/social-runs/<uuid>` and `GET /v1/dialogues/<uuid>` show durable state.
- `503 model_busy`: model slots unavailable; retry later with same request UUID.
- On Windows file access errors during `TempDirectory` cleanup, share complete logs; do not delete production SQLite data.
