# C0 Windows Verification (candidate)

Use Python 3.10+ and Ollama with `qwen3:8b`. Work from the Repository root.
**Stop Backend and back up `backend/data/npcortex.sqlite3` before switching to
C0**. C0 applies a one-way additive schema v3 → v4; older versions will reject
this newer database. Keep the backup until the new Backend is accepted.

## 1. Strict Windows Automated Regression

```powershell
.\scripts\test-c0-regression.ps1 -Repeat 3
```

Expected **182 tests** per run for C0-B planner reliability candidate, `OK`, no `WinError 32`, `WinError 10053`
`ResourceWarning` or `FAILED`. Some deliberate failure-injection tests log
tracebacks ending in `ok`: the final unittest summary, not those expected
exception traces, determines PASS/FAIL.

Focused new tests (10 per run):

```powershell
.\scripts\test-c0-planner.ps1 -Repeat 3
```

## 2. Start Ollama + Python Backend

```powershell
ollama list
.\scripts\dev-backend.ps1
```

In another PowerShell, execute:

```powershell
.\scripts\test-c0-headless.ps1
```

The script uses Qwen for planning but **simulates Forge world observations**. C0-B returns `planner_invalid_decision` if the model responds but no valid skill plan can be produced; this differs from `provider_unavailable` when Ollama fails. See [C0 Planner Reliability](c0-planner-reliability.md).
It tests one initial oak log, an intentionally false `succeeded` result that
must become `unverified`, then a second result with verified air that must
complete the goal. Qwen outputs are nondeterministic; a bad plan is reported
as a fail, not automatically replaced by a rule-based action. The script prints
`GOAL: <uuid>` and every individual PASS.

## 3. Restart persistence

Restart Python Backend with Ctrl+C, then run:

```powershell
.\scripts\test-c0-headless.ps1 -VerifyGoalId 'GOAL_UUID'
```

Must show the completed goal and exactly two stored actions: first
`unverified`, second `succeeded`. Verify normal legacy endpoints too if desired:

```powershell
.\scripts\test-b3-social.ps1
```

## 4. Forge Regression (unchanged Java sources)

```powershell
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Use JDK 17. In Minecraft (cheats enabled):

```text
/npcortex status
/npcortex ask yui Hello!
```

The game bridge is still A2 chat only: **there is no in-game C0 NPC sensor,
movement or player-based entity in this ZIP**. Passing headless C0 is not
passing a C1 Forge integration test.

## 5. Status and quality gates

- Python strict regression: verified in development environment, must check
  Windows independently (test `-Repeat 3`).
- Qwen real local adapter: user test pending.
- Schema v3 → v4 with old B1/B2/B3 content: independently verified in
  development environment (see test report).
- Forge compile/build: blocked if Gradle distribution/dependencies unavailable;
  do not claim success without full output.
- Minecraft gameplay: user must build/run and report evidence.

**These instructions do not, by themselves, certify completed tests or a GitHub update.**
