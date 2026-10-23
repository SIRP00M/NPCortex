# NPCortex GWI — C-F1 Final Quality Gate — Windows Acceptance

**Use this exact ZIP as your project root at `F:\AI\NPCortex`.** Python 3.13, Temurin JDK 17, Forge 1.20.1. No manually configured Token required. No GitHub operations.

## A. One-command automated quality gates

Open PowerShell from the repository root:

```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:NPCORTEX_CPLUS_F1_SECRET_FILE -ErrorAction SilentlyContinue
.\scripts\test-cplus-f1-final-gates.ps1 -IncludeForgeBuild
```

- Runs repaired Auto Local Auth runner (private **temporary** secret only), Java/Python parity, cursor/HTTP/backend fault regressions, the **full Python backend suite**, and 22 Follow/Navigation Java suites. `-IncludeForgeBuild` also runs the Forge build.
- `test-cplus-f1-auto-local-auth.ps1` no longer relies on PowerShell passing embedded double quotes through `python -c`.
- Expected `PASS C-F1 automated diagnostic foundation regression commands.` **only if all scripts exit cleanly**. If it fails, copy *the first error and last 30 lines*; don't assume the later gates passed.
- Credentials are generated under the test runner's isolated `%TEMP%` folder; cleanup is automatic. Real C-F1 credentials remain separate.

## B. Local Backend and real Minecraft launch

**PowerShell 1**:

```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:NPCORTEX_CPLUS_F1_SECRET_FILE -ErrorAction SilentlyContinue
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
python -m npcortex_backend
```

**PowerShell 2**:

```powershell
cd F:\AI\NPCortex
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:NPCORTEX_CPLUS_F1_SECRET_FILE -ErrorAction SilentlyContinue
.\scripts\dev-forge.ps1 -Task runClient
```

### B1. Existing functional gameplay evidence (A–D)

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus syncstatus yui
/npcortex debug cplus audit yui
/npcortex debug cplus sync yui
/npcortex debug cplus syncstatus yui
/npcortex debug cplus audit yui
```

Previous operator evidence: **Test A, B (Backend Offline → Online), C (Save/Rejoin), D (C1/C2 Regression) PASS** on H1–H5 baseline. No new gameplay production code in this cleanup. Repeat a short smoke test on this exact ZIP if possible.

## C. Deliberate corrupted cursor test — copied world ONLY

1. Create a **throwaway copy** of the single-player save *and* keep a backup of the working backend SQLite state. Do not fault-test the only copy of an important world.
2. Enter the copied world, run `probe` then `sync` until `audit READY` and `pending=0`. Exit with **Save & Quit**, then stop the whole game before editing anything.
3. From the copied world's root, locate `data\npcortex\cplus_f1_delivery.cursor`. **Never modify `cplus_f1_diagnostics.bin` manually.**
4. Create the orphan temp file next to it (path below must be replaced with the **copied** save directory):

```powershell
# REPLACE with an expendable copied save; do not point this at the original world!
$copyWorld = 'F:\AI\NPCortex\forge-mod\run\saves\CF1_DISPOSABLE_COPY'
$folder = Join-Path $copyWorld 'data\npcortex'
if (-not (Test-Path -LiteralPath $folder -PathType Container)) { throw 'Copied world data\npcortex folder not found — stop!' }
if (-not (Test-Path -LiteralPath (Join-Path $folder 'cplus_f1_delivery.cursor'))) { throw 'No existing copied-world cursor — run a real sync first' }
$tmp = Join-Path $folder 'cplus_f1_delivery.cursor.tmp'
if (Test-Path -LiteralPath $tmp) { throw 'An existing .tmp was found; preserve it and stop' }
Set-Content -LiteralPath $tmp -Value 'test-only-orphaned-cursor-evidence' -Encoding ASCII
```

5. Restart the **copied** world and invoke:

```mcfunction
/npcortex debug cplus syncstatus yui
/npcortex debug cplus audit yui
/npcortex debug cplus sync yui
```

**PASS:** local cursor `HOLD`, audit `HOLD`, sync cannot advance ACK; `.tmp` remains untouched. **Do not delete the file while Minecraft is running.** Restore the original copy from backup after evidence captured. The pure-Java test suite also covers broken hash, truncated, overflow and oversized cursor cases in disposable test directories.

## D. Real 1/3/5/10 NPC MSPT (not synthetic)

- Test **a disposable Creative superflat world**. Do not test valuable saves. Keep the same view distance, graphics settings and unloaded/background programs across runs. Test 0 (baseline), 1, 3, 5, and 10 NPCs.
- Open F3 and record the *Integrated server* `ms ticks` / TPS line where available. Keep 60 seconds per actor count, take readings or screenshots at approximately 20/40/60 seconds. The F3 information layout may vary; if MSPT isn't visible, capture `/debug start` → wait 60 seconds → `/debug stop` profiling output/logs instead and report that you could not measure MSPT directly.
- Spawn distinct identities, **moving a few blocks between spawns** to avoid stacking all avatars in one spot:

```mcfunction
/npcortex c1 spawn bench01
/npcortex c1 spawn bench02
/npcortex c1 spawn bench03
/npcortex c1 spawn bench04
/npcortex c1 spawn bench05
/npcortex c1 spawn bench06
/npcortex c1 spawn bench07
/npcortex c1 spawn bench08
/npcortex c1 spawn bench09
/npcortex c1 spawn bench10
```

- At 1/3/5/10 actors record **idle** F3 server ms ticks, then run a manual bounded diagnostic `probe` / `sync` while they are active; record peak ms ticks and `audit` state. Do not run expensive uncontrolled tasks or add C-F2/C-F3 skills during this test.
- Record each result in `docs/cplus-cf1-mspt-evidence-template.csv`, replacing blank cells with observed data. Never make up MSPT values; send screenshots/logs if numeric values cannot be extracted. Report pauses/queue-rejections separately from tick-time.
- To clean up the disposable world, you may use `/npcortex c1 despawn bench01` etc. Identity persistence is intentional; deleting the throwaway world when finished is easier.

## E. Disposition

| Gate | Status from evidence so far |
|---|---|
| Windows Gameplay A/B/C/D | **PASS**, operator-confirmed on H1–H5 baseline |
| Original H1–H5 Windows Forge Build | **PASS**, operator-confirmed |
| Repaired PowerShell Auto Local Auth + combined runner | **PENDING on Windows** |
| Disposed copied-world orphan `.tmp` fail-closed | **PENDING** |
| Genuine 1/3/5/10 NPC MSPT | **PENDING** |
| Actual power cut / Minecraft process kill | **NOT VERIFIED**; do not endanger valuable worlds |
| World-effect recovery | **Out of C-F1 implementation scope** |

Do not mark the entire C-F1 Quality Gate complete without the remaining Windows evidence. After that, begin **C-F2 World Perception/Permissions Detailed Source Audit and A–E options**, still requiring scope approval before code changes.
