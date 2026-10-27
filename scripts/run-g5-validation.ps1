# Runs every OFFLINE check of the G5 harness and writes docs/g5-test-harness/VALIDATION-RESULTS.md (actual output, not a claim).
# Offline = static validation, controller simulation on a mock world, collector tests on synthetic fixtures + one real log excerpt,
# installer test in a throw-away tree. NONE of this is evidence about NPCortex or Minecraft behaviour.
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ps = (Get-Command powershell.exe).Source
$rows = New-Object System.Collections.ArrayList
function Run($name, $file, $kind) {
    $t0 = Get-Date
    if ($kind -eq 'node') { $out = & node (Join-Path $PSScriptRoot $file) 2>&1 | Out-String } else { $out = & $ps -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $file) 2>&1 | Out-String }
    $code = $LASTEXITCODE
    $summary = ($out -split "`r?`n" | Where-Object { $_ -match 'PASS|FAIL|"checks"|"failures"|"mutations"|"undetected"|assertions' } | ForEach-Object { $_.Trim() }) -join ' '
    [void]$rows.Add([pscustomobject]@{ check = $name; exit = $code; seconds = [int]((Get-Date) - $t0).TotalSeconds; status = $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }); detail = $summary })
}
Run 'build (regenerate pack + zip)' 'build-g5-datapack.ps1' 'ps'
Run 'static validator (1.20.1 grammar subset, NPCortex command shapes, safety, evidence-first invariants)' 'validate-g5-datapack.ps1' 'ps'
Run 'validator mutation test (15 broken packs must be rejected)' 'test-g5-validator-mutations.ps1' 'ps'
Run 'controller simulation (mock world: probe v2 variants, adaptive, owner-gated, AWAIT/gate, no timer progression, stale-run protection)' 'simulate-g5-datapack.js' 'node'
Run 'collector tests (synthetic fixtures + real Forge log excerpt)' 'test-g5-collector.ps1' 'ps'
Run 'owner-gate tests (g5-gate.ps1 on simulated runs + hand-built cases)' 'test-g5-gate.ps1' 'ps'
Run 'installer test (throw-away tree)' 'test-g5-installer.ps1' 'ps'
$zip = Join-Path $root 'integrations/datapacks/NPCortex_G5.zip'
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash
$md = @('# G5 harness - offline validation results', '', "Generated $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') by scripts/run-g5-validation.ps1. Pack zip SHA-256: ``$hash``", '',
        'These are OFFLINE checks (mock world, synthetic fixtures, one real log excerpt). They are not gameplay evidence; every gameplay scenario stays PENDING until run in Minecraft.', '',
        '| Check | Status | Seconds | Output |', '|---|---|---|---|')
foreach ($r in $rows) { $md += "| $($r.check) | **$($r.status)** | $($r.seconds) | $($r.detail -replace '\|', '/') |" }
$dir = Join-Path $root 'docs/g5-test-harness'; New-Item -ItemType Directory -Force -Path $dir | Out-Null
[IO.File]::WriteAllLines((Join-Path $dir 'VALIDATION-RESULTS.md'), $md, (New-Object System.Text.UTF8Encoding($false)))
$rows | Format-Table check, status, seconds -AutoSize | Out-String -Width 200
if ($rows | Where-Object { $_.status -ne 'PASS' }) { exit 1 }
