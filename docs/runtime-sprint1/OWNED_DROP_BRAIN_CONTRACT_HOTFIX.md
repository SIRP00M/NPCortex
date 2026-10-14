# Runtime Sprint 1 – Owned Drop / Brain Contract Hotfix

## Root cause

The Owned Drop Auto Retrieval patch added `npc_mined` to each drop entry in
`BrainObservation.capture()` on the Forge side. The Python `/v1/brain/step`
validator still required exactly five legacy drop properties, therefore rejected
the valid new snapshot with HTTP 400 `Unexpected drop observation fields`
**before Ollama/Qwen was called**. Forge then paused the task with the generic
`Qwen unavailable or invalid structured decision` message. The `loot_collected`
event and contents of the NPC bag are independent signs that auto-pickup itself
had succeeded.

## Changes

- Python `validate_state` now accepts `npc_mined` as an **optional strict boolean**
  on a drop observation; old Forge clients omitting it still work.
- Unexpected extra keys and invalid provenance types still fail closed.
- The backend prompt explicitly describes provenance as context, **not** an
  authorization to bypass server-side checks.
- Regression tests cover valid new/legacy snapshots, invalid provenance, and a
  `/v1/brain/step` mock-provider HTTP 200 acceptance path. No live model call is
  made during this test.

## Apply on Windows

1. Back up your current `F:\AI\NPCortex` folder or at least
   `backend\src\npcortex_backend\brain.py`.
2. Extract this Full Repository archive over the *same* folder. The entire
   patch is backwards-compatible and changes backend code, tests, and docs;
   there are **no Forge Java source changes** in this hotfix.
3. In the first PowerShell window, stop the old backend with Ctrl+C.
4. Restart it:
   ```powershell
   cd F:\AI\NPCortex
   .\scripts\dev-backend.ps1
   ```
5. Forge `runClient` does not need rebuilding solely for this backend fix if
   you're already running the matching Owned Drop Retrieval mod. In Minecraft,
   run `/npcortex task resume` on the paused task if its budgets permit. Or
   cancel it and start a new task to get an independent test result.
6. Inspect `/npcortex task status`, `/npcortex task trace` and backend console.
   The previous `Unexpected drop observation fields` rejection should no
   longer occur. Model/network errors and navigation limitations are separate.

## Tested

- `python scripts/test-runtime.py`: 194 backend tests passed;
  193 simulated runtime assertions passed; Java 17 syntax checker passed
  for 68 Java files.
- Full ForgeGradle compilation and in-game behavior are **not yet verified**.
- This patch does **not** fix tree canopy reach/navigation or inaccessible
  3D item drops.
