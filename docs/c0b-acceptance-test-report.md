# NPCortex C0-B — Acceptance Script Fix Test Report

Date: 2026-10-15

## Scope

PowerShell `-VerifyGoalId` count assertions and mismatch diagnostics only.

## Automated evidence

- The three new source-guard tests were executed with `python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_c0_windows_acceptance_script.py -v`: **3/3 PASS**.
- Full Python regression is run in the assistant's Linux container against the patched full repository. See completion counts below.
- Comparison against the C0-B input ZIP is performed to ensure all original application code / SQLite migration / Forge sources are byte-for-byte unchanged.
- Full repository ZIP archive CRC integrity is checked with Python `ZipFile.testzip()`.

## Still pending

- Native Windows PowerShell execution of fixed `-VerifyGoalId` (no `pwsh`/`powershell` executable in assistant's container).
- Real Qwen integration on the new ZIP (the user already ran this on the immediately prior C0-B backend, whose application code is unchanged).
- Forge build/gameplay, outside this script-only scope.

## Regression run results

- Full regression run 1: **185/185 PASS** (33.673s), `python -W error::ResourceWarning -m unittest discover -s backend/tests -v`.
- Full regression run 2: **185/185 PASS** (33.797s), `python -W error::ResourceWarning -m unittest discover -s backend/tests -v`.
- Full regression run 3: **185/185 PASS** (33.575s), `python -W error::ResourceWarning -m unittest discover -s backend/tests -v`.
