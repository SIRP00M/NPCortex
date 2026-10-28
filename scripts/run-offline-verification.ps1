# NPCortex complete OFFLINE verification (no Minecraft, no gameplay, no live database, no world).
#   scripts\run-offline-verification.ps1                 # everything below
#   scripts\run-offline-verification.ps1 -HttpRepeats 5  # fewer repeats of test_http_security
# Stages: (1) forge-mod `gradlew build` = compile + EVERY Java *SelfTest (check -> verifySelfTests, nonzero exit fails),
#         (2) client/server separation, (3) backend unittest discovery (includes the static contracts),
#         (4) test_http_security repeated -HttpRepeats times (an intermittent failure must not hide behind one green run).
# Exit code 0 only when every stage passed. Logs go to a temporary folder, never into the repository.
param([int]$HttpRepeats = 30)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-offline-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$results = New-Object System.Collections.ArrayList

# cmd.exe does the redirection so Windows PowerShell 5.1 never wraps native stderr into error records.
function Invoke-Stage([string]$Name, [string]$WorkDir, [string]$CommandLine, [scriptblock]$Summary) {
    $log = Join-Path $logDir (($Name -replace '[^\w]+', '-') + '.log')
    # `cd /d` because Push-Location does not change the process directory that cmd.exe inherits.
    & cmd.exe /c "cd /d `"$WorkDir`" && $CommandLine > `"$log`" 2>&1"; $code = $LASTEXITCODE
    $detail = & $Summary $log
    [void]$results.Add([pscustomobject]@{ Stage = $Name; Exit = $code; Result = $(if ($code -eq 0) { 'PASS' } else { 'FAIL' }); Detail = $detail; Log = $log })
}

Invoke-Stage 'java build + all self-tests' (Join-Path $root 'forge-mod') '.\gradlew.bat --console=plain build' {
    param($log)
    $text = Get-Content -Raw $log
    $tasks = ([regex]::Matches($text, '(?m)^> Task :(selfTest|verify)\w+')).Count
    "self-test tasks executed: $tasks; " + ($text -split "`n" | Where-Object { $_ -match '^BUILD ' } | Select-Object -First 1)
}
Invoke-Stage 'client/server separation' $root 'python scripts\check-dist-separation.py' {
    param($log) (Get-Content $log | Select-Object -Last 1)
}
$env:PYTHONPATH = (Join-Path $root 'backend\src') + ';' + (Join-Path $root 'backend\tests')
Invoke-Stage 'backend unittest discovery' (Join-Path $root 'backend') 'python -W error::ResourceWarning -m unittest discover -s tests' {
    param($log) (($(Get-Content $log) | Where-Object { $_ -match '^(Ran |OK|FAILED)' }) -join ' ')
}
$httpFailures = 0
for ($i = 1; $i -le $HttpRepeats; $i++) {
    $log = Join-Path $logDir "http-security-$i.log"
    & cmd.exe /c "cd /d `"$(Join-Path $root 'backend')`" && python -m unittest test_http_security > `"$log`" 2>&1"
    if ($LASTEXITCODE -ne 0) { $httpFailures++ }
}
[void]$results.Add([pscustomobject]@{ Stage = "test_http_security x$HttpRepeats"; Exit = $httpFailures; Result = $(if ($httpFailures -eq 0) { 'PASS' } else { 'FAIL' });
    Detail = "$httpFailures failing run(s) of $HttpRepeats"; Log = $logDir })

$results | Format-Table Stage, Result, Detail -AutoSize | Out-String -Width 200 | Write-Host
Write-Host "Logs: $logDir"
if (@($results | Where-Object { $_.Result -ne 'PASS' }).Count -gt 0) { Write-Host 'OFFLINE VERIFICATION: FAIL'; exit 1 }
Write-Host 'OFFLINE VERIFICATION: PASS (offline only; says nothing about Minecraft gameplay, MSPT or a dedicated server)'
exit 0
