# M04 Automatic Acceptance — Windows One Command

**Status:** operator-independent static/contract/backend/build verification only.
No real Forge gameplay, native mining, item pickup, crash/power-loss recovery or
world-effect authority is established by this tool. **M04 Gates 0–6 remain HOLD
where live evidence is missing.**

## Run

From a PowerShell window at the root of this exact full repository:

```powershell
cd C:\path\to\NPCortex
.\scripts\accept-m04.ps1
```

- Executes M04 safety/source guards, Option D static checks, M04 Python contracts,
  M04 review fixtures, runner's own tests, Java 17 contract, **full backend suite**,
  and Forge build plus the registered M04/Transport contract Gradle tasks.
- Requires an installed Python, Java JDK with `javac`/`java`, and the bundled
  Gradle wrapper's distribution available to run Forge. External Gradle/Minecraft
  dependencies may require internet and a suitable Windows/JDK 17 setup.
- The runner does **not** start the Minecraft client/server or the application
  backend. It does not install a datapack. It does not connect to or change a DB.
- It creates `NPCortex-Automated-Reports/M04-Auto-<UTC>-<id>/summary.md`,
  JSON, individual gate logs and an adjacent ZIP, **in the parent directory of
  the extracted NPCortex source** (not inside the source repository).
- No raw world saves, SQLite files, local `.env`, chat transcripts, private keys
  or existing outbox/journals are ever copied into the report ZIP.

If you only need Python and Java tests (Forge marked **HOLD**, not PASS):

```powershell
.\scripts\accept-m04.ps1 -SkipForge
```

For a quick *smoke check of the harness itself* (both Forge and full Backend
are explicitly HOLD, **never** accepted):

```powershell
.\scripts\accept-m04.ps1 -SkipForge -SkipBackendFull
```

To optionally review an **existing** test world and backend SQLite read-only:

```powershell
.\scripts\accept-m04.ps1 -WorldName 'New World (2)' `
  -Database 'C:\path\to\NPCortex\backend\data\npcortex.sqlite3'
```

World/Database must be supplied together and must identify the **same** running
instance. The script only reads; it cannot make the live snapshot atomic with
DB writes. For the most consistent evidence capture, Save & Quit your copied
world before running the optional review. A successful correlation is
**metadata evidence**, not mining/pickup acceptance.

## Results

- `PASS_AUTOMATED_ONLY`: all automated tests/build passed (still **not** gameplay).
- `FAIL`: a test or build failed; inspect the matching log in the generated ZIP.
- `HOLD`: missing tool or an explicitly skipped Forge build; never claim a pass.
- `S09-review-live=HOLD`: live world+DB not supplied; this is expected for the
  no-argument one-command path. When supplied, the review remains read-only.

`summary.json` lists every gate separately, its exit code/failure, log path,
run duration and machine class. The script returns exit code 1 on a failing
automated gate, 0 on automated PASS or HOLD, and 2 on invalid startup arguments.
Do not push anything to GitHub or overwrite the local repository simply because
this script succeeded. Integrate source changes with a reviewed diff.

## Scope / limitations

Automated offline testing is not the same as real Forge GameTest. No real Minecraft
GameTest fixture is added by this change because M04 does not yet have Gate 0
provenance/permission authority, Gate 3 native cancel quiescence, or Gate 4
pickup custody. Inventing a green gameplay test without those would be unsafe.

The one-command runner also provides a correct inherited `PYTHONPATH` for the
C-F1 Python child-process crash-boundary test; CI `backend-tests.yml` explicitly
sets it too. This prevents an accidental subprocess import failure from being
misreported as a real-world recovery defect. CI status itself must be rerun to
confirm: this candidate has **not** run any GitHub action.
