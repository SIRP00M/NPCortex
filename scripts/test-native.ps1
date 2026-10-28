# Stage 4 Option D (native break + pickup): the integrated offline gates, in order, failing fast. No Minecraft client, no gameplay, no live database.
#   scripts\test-native.ps1               # G1..G5 (the GameTest stage runs a headless Forge server, about a minute)
#   scripts\test-native.ps1 -SkipGameTest # only the pure Java + Python gates
#   scripts\test-native.ps1 -Full         # then the complete scripts\run-offline-verification.ps1 (G6 regression)
# Every stage prints PASS/FAIL from its exit code. Offline fixture evidence only: it says nothing about chunk-save crash safety, the
# live Manager GUI, MSPT on a real server or a dedicated server.
param([switch]$SkipGameTest, [switch]$Full)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-native-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$results = New-Object System.Collections.ArrayList

function Invoke-Stage([string]$Gate, [string]$Name, [string]$WorkDir, [string]$CommandLine) {
    $log = Join-Path $logDir ((($Gate + '-' + $Name) -replace '[^\w]+', '-') + '.log')
    & cmd.exe /c "cd /d `"$WorkDir`" && $CommandLine > `"$log`" 2>&1"; $code = $LASTEXITCODE
    $tail = (Get-Content $log | Where-Object { $_ -match '^(Ran |OK|FAILED|PASS|BUILD|Native\w+SelfTest|WorldEffect\w+SelfTest)|checks OK|assertions' } | Select-Object -Last 6) -join ' | '
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

# G1 authority + permission + preview/confirm + manager wire + binding refusals
Invoke-Stage 'G1' 'java policy + flow + manager' $forge "$gradle selfTestNativeActionPolicy selfTestNativeEffectFlow verifyManagerProtocol verifyManagerCore"
Invoke-Stage 'G1' 'static contracts' $backend 'python -m unittest test_milestone_static_contracts'
# G2 native ledger, claims, bindings, fences in both directions, attempt-specific status, wire parity with the Java flow
Invoke-Stage 'G2' 'ledger + claims + http + wire' $backend 'python -W error::ResourceWarning -m unittest test_cplus_wn_store test_cplus_wn_http test_cplus_wn_java_wire'
Invoke-Stage 'G2' 'accepted stores and no-op ledger unchanged' $backend 'python -W error::ResourceWarning -m unittest test_cplus_we_ledger test_cplus_we_http test_cplus_we_java_wire test_cplus_m04_store test_cplus_e3_g2_motion test_cplus_e3_g2_http test_cplus_e3_g3_lifecycle'
# G3 + G4 the real native semantics (headless GameTest server)
if (-not $SkipGameTest) { Invoke-Stage 'G3-G4' 'real Forge GameTests' $root 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test-native-gametest.ps1' }
# G5 repeat the racing tests to catch an intermittent interleaving, then the transport/ledger measurement (an OBSERVATION, never an acceptance)
for ($i = 1; $i -le 10; $i++) {
    Invoke-Stage 'G5' "race x$i" $backend 'python -W error::ResourceWarning -m unittest test_cplus_wn_store.Claims.test_concurrent_reservations_of_one_block_admit_exactly_one test_cplus_wn_store.Claims.test_concurrent_pickups_of_one_entity_admit_exactly_one test_cplus_wn_store.CrossStoreFences.test_concurrent_native_and_g2_reservations_for_one_actor_admit_exactly_one'
}
Invoke-Stage 'G5' 'ledger measurement 1/3/5/10 actors' $root 'python scripts\measure_native_ledger.py'
$results | Format-Table -AutoSize | Out-String -Width 220 | Write-Host
if ($Full) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-offline-verification.ps1')
    exit $LASTEXITCODE
}
Write-Host "NATIVE GATES G1-G5: PASS (offline only). Logs: $logDir"
exit 0
