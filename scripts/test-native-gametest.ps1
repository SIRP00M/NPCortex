# Stage 4 Option D: the REAL Forge GameTests (custom avatar, real destroyBlock / playerTouch, real drops, real merging, real command tree).
#   scripts\test-native-gametest.ps1              # one run
#   scripts\test-native-gametest.ps1 -Repeat 3    # repeat to expose an intermittent test
# A headless Forge GameTestServer starts its OWN throw-away world in forge-mod\run-gametest\world; this script deletes that folder
# before every run (it only ever deletes that folder, never saves\, never a database, never a journal). The backend is an in-process
# scripted ledger (the contract's state rules): the real Python ledger is covered by the backend tests and the wire fixture.
# Offline evidence only: no client, no viewer sync, no chunk-save/crash behaviour, no integrated single-player specifics.
param([int]$Repeat = 1)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$forge = Join-Path $root 'forge-mod'
$world = Join-Path $forge 'run-gametest\world'
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-gametest-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$failedRuns = 0
for ($i = 1; $i -le $Repeat; $i++) {
    if (Test-Path -LiteralPath $world) {
        if ((Resolve-Path $world).Path -ne (Join-Path (Resolve-Path (Join-Path $forge 'run-gametest')).Path 'world')) { throw "refusing to delete $world" }
        Remove-Item -LiteralPath $world -Recurse -Force
    }
    $log = Join-Path $logDir "run-$i.log"
    & cmd.exe /c "cd /d `"$forge`" && .\gradlew.bat --console=plain --offline runGameTestServer > `"$log`" 2>&1"; $code = $LASTEXITCODE
    $text = Get-Content $log
    $failed = @($text | Where-Object { $_ -match 'LogTestReporter\]: (\w+) failed!' } | ForEach-Object { if ($_ -match 'LogTestReporter\]: (\w+) failed! (.*)$') { "$($Matches[1]): $($Matches[2])" } })
    $summary = @($text | Where-Object { $_ -match 'GAME TESTS COMPLETE|required tests (passed|failed)|All \d+ required' })
    Write-Host "run $i : exit $code | $($summary -join ' | ')"
    foreach ($f in $failed) { Write-Host "  FAILED $f" }
    # Fail closed on "0 tests ran": the number that completed must equal the number of @GameTest methods in the source.
    $expected = ([regex]::Matches((Get-Content (Join-Path $forge 'src\test\java\io\github\sirp00m\npcortex\NativeGameTests.java') -Raw), '@GameTest\(')).Count
    $ran = 0; foreach ($l in $text) { if ($l -match '=+ (\d+) GAME TESTS COMPLETE') { $ran = [int]$Matches[1] } }
    if ($ran -ne $expected) { Write-Host "  FAILED the server ran $ran GameTests but the source declares $expected"; $failedRuns++; continue }
    if ($code -ne 0 -or $failed.Count -gt 0) { $failedRuns++ }
}
Write-Host "Logs: $logDir"
if ($failedRuns -gt 0) { Write-Host "NATIVE GAMETEST: FAIL ($failedRuns of $Repeat run(s))"; exit 1 }
Write-Host "NATIVE GAMETEST: PASS ($Repeat run(s); headless server, scripted backend; says nothing about a real client, chunk save or crash behaviour)"
exit 0
