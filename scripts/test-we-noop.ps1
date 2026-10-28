# Stage 4 NO-OP world action foundation: focused offline gates (no Minecraft, no gameplay, no live database, no world).
#   scripts\test-we-noop.ps1            # G1..G4 in order, fails fast
#   scripts\test-we-noop.ps1 -Full      # then the complete scripts\run-offline-verification.ps1 (G5)
# Each stage prints PASS/FAIL from its exit code. Offline fixture evidence only: it says nothing about chunk-save crash
# safety, live Manager GUI behaviour, MSPT or a dedicated server.
param([switch]$Full)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-we-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$results = New-Object System.Collections.ArrayList

function Invoke-Stage([string]$Gate, [string]$Name, [string]$WorkDir, [string]$CommandLine) {
    $log = Join-Path $logDir ((($Gate + '-' + $Name) -replace '[^\w]+', '-') + '.log')
    & cmd.exe /c "cd /d `"$WorkDir`" && $CommandLine > `"$log`" 2>&1"; $code = $LASTEXITCODE
    $tail = (Get-Content $log | Where-Object { $_ -match '^(Ran |OK|FAILED|PASS|BUILD)' } | Select-Object -Last 6) -join ' | '
    [void]$results.Add([pscustomobject]@{ Gate = $Gate; Stage = $Name; Result = $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }); Detail = $tail })
    if ($code -ne 0) {
        $results | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
        Write-Host "STOP: $Gate $Name failed. Log: $log"; exit 1
    }
}

$env:PYTHONPATH = (Join-Path $root 'backend\src') + ';' + (Join-Path $root 'backend\tests')
$backend = Join-Path $root 'backend'
$forge = Join-Path $root 'forge-mod'
$gradle = '.\gradlew.bat --console=plain --offline'

# G1 authority + deny-by-default permission (Java pure cores, Manager packet/core, static source pins)
Invoke-Stage 'G1' 'java policy + flow + manager' $forge "$gradle selfTestWorldActionPolicy selfTestWorldEffectFlow verifyManagerProtocol verifyManagerCore"
Invoke-Stage 'G1' 'static contracts' $backend 'python -m unittest test_milestone_static_contracts'
# G2 ledger state machine, schema compatibility (v4 baseline copy), restart, idempotency, durability settings
Invoke-Stage 'G2' 'ledger + schema + wire' $backend 'python -W error::ResourceWarning -m unittest test_cplus_we_ledger test_cplus_we_java_wire'
# G3 HTTP routes, atomic cross-store actor fence (threads on separate connections), historical stores untouched
Invoke-Stage 'G3' 'http + fences' $backend 'python -W error::ResourceWarning -m unittest test_cplus_we_http test_cplus_m04_store test_cplus_e3_g2_motion test_cplus_e3_g2_http test_cplus_e3_g3_lifecycle'
# G4 offline fault injection lives in the same suites (lost ACK, duplicate, restart, stale identity, backend down, cancel, hold);
#    repeat the race test to catch an intermittent interleaving.
for ($i = 1; $i -le 10; $i++) {
    Invoke-Stage 'G4' "race x$i" $backend 'python -W error::ResourceWarning -m unittest test_cplus_we_ledger.CrossStoreFences.test_concurrent_cross_store_reservations_admit_exactly_one'
}
$results | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
if ($Full) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-offline-verification.ps1')
    exit $LASTEXITCODE
}
Write-Host "WE NO-OP GATES G1-G4: PASS (offline only). Logs: $logDir"
exit 0
