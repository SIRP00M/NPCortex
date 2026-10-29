# PAP-G1b TEST-ONLY: real Minecraft SavedData persistence on the THROW-AWAY GameTest world (forge-mod\run-gametest\world, wiped by
# this script exactly like scripts\test-native-gametest.ps1). Never touches saves\, run\ (live), a database or a journal.
#   scripts\pap\run-pap-g1b.ps1            # measurements (namespace npcortexpap) then the real-kill pair (npcortexpapkill)
param([switch]$SkipKill)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$forge = Join-Path $root 'forge-mod'
$world = Join-Path $forge 'run-gametest\world'
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('pap-g1b-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $logDir | Out-Null

function Wipe-World {
    if (Test-Path -LiteralPath $world) {
        if ((Resolve-Path $world).Path -ne (Join-Path (Resolve-Path (Join-Path $forge 'run-gametest')).Path 'world')) { throw "refusing to delete $world" }
        Remove-Item -LiteralPath $world -Recurse -Force
    }
}
function Run-Namespace([string]$ns, [string]$name) {
    $log = Join-Path $logDir "$name.log"
    & cmd.exe /c "cd /d `"$forge`" && .\gradlew.bat --console=plain --offline runGameTestServer -PgametestNamespace=$ns > `"$log`" 2>&1"
    $code = $LASTEXITCODE
    $text = Get-Content $log
    Write-Host "--- $name : exit $code"
    $text | Where-Object { $_ -match 'PAPG1B\|' } | ForEach-Object { ($_ -replace '^.*PAPG1B\|', '') } | ForEach-Object { Write-Host "  $_" }
    $text | Where-Object { $_ -match 'LogTestReporter\]: (\w+) failed!|GAME TESTS COMPLETE|required tests (passed|failed)|All \d+ required' } | ForEach-Object { Write-Host "  $_" }
    return $code
}

Wipe-World
$c1 = Run-Namespace 'npcortexpap' 'measure'
if ($SkipKill) { Write-Host "logs: $logDir"; exit $c1 }

Wipe-World
$k1 = Run-Namespace 'npcortexpapkill' 'kill-phase1'
$marker = Test-Path (Join-Path $world 'pap-kill-marker.txt')
Write-Host "phase1 exit $k1 (a halted JVM is expected to be non-zero); marker written: $marker"
$k2 = Run-Namespace 'npcortexpapkill' 'kill-phase2'
Wipe-World
Write-Host "measure exit $c1 | kill phase1 exit $k1 marker=$marker | kill phase2 exit $k2 (0 = A durable, B absent)"
Write-Host "logs: $logDir"
