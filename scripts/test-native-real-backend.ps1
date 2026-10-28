# Stage 4 Option D: the real Forge executors against the REAL Python backend over loopback HTTP (G5 end-to-end, no Minecraft client).
#   scripts\test-native-real-backend.ps1
#   scripts\test-native-real-backend.ps1 -Down        # also: run once with NO backend and prove native effects are refused
# It starts the backend on a TEMPORARY database and a TEMPORARY credential file (never backend\data, never your credential), runs the
# GameTest namespace npcortexreal in forge-mod\run-gametest (a throw-away world that this script wipes first), stops ONLY the backend
# it started, then inspects the database read-only (scripts\verify_native_db.py). Port 8765 must be free.
# Offline evidence only: no real client, no viewer sync, no chunk-save/crash behaviour.
param([int]$Port = 18765, [switch]$Down)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$forge = Join-Path $root 'forge-mod'
$world = Join-Path $forge 'run-gametest\world'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-native-real-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path (Join-Path $tmp 'bridge') | Out-Null
$db = Join-Path $tmp 'native.sqlite3'
$secret = Join-Path $tmp 'bridge\cplus-f1.secret'
function Fail([string]$m) { Write-Host "test-native-real-backend: $m"; exit 1 }
function Wipe-World { if (Test-Path -LiteralPath $world) { if ((Split-Path -Leaf $world) -ne 'world' -or $world -notlike '*run-gametest*') { Fail "refusing to delete $world" }; Remove-Item -LiteralPath $world -Recurse -Force } }
if (@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count) { Fail "port $Port is busy (is the live backend running?). Stop it first; this script never reuses a running backend." }
# The Forge side of the test reads its backend address from the throw-away run-gametest config (never from your game's config).
$cfgDir = Join-Path $forge 'run-gametest\config'
New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
$toml = "#AI backend settings. On a dedicated server, use the server's backend address.`n[backend]`n`t#Local HTTP backend URL (no trailing slash)`n`turl = `"http://127.0.0.1:$Port`"`n"
[IO.File]::WriteAllText((Join-Path $cfgDir 'npcortex-common.toml'), $toml, (New-Object Text.UTF8Encoding $false))
$env:NPCORTEX_CPLUS_F1_TOKEN = ''
$oldSecret = $env:NPCORTEX_CPLUS_F1_SECRET_FILE
$env:NPCORTEX_CPLUS_F1_SECRET_FILE = $secret

if ($Down) {
    Wipe-World
    $log = Join-Path $tmp 'down.log'
    & cmd.exe /c "cd /d `"$forge`" && .\gradlew.bat --console=plain --offline runGameTestServer -PgametestNamespace=npcortexreal > `"$log`" 2>&1"
    $failed = @(Get-Content $log | Where-Object { $_ -match 'LogTestReporter\]: (\w+) failed!' })
    Write-Host "no-backend run: $($failed.Count) test(s) failed (EXPECTED: every real test needs the backend); the point is that no native effect happened."
    $blocks = @(Get-Content $log | Where-Object { $_ -match 'Native effects refused|Reservation reply unusable|binding' }).Count
    Write-Host "refusal lines in the log: $blocks"
    if ($failed.Count -eq 0) { Fail 'the real tests passed WITHOUT a backend - that must be impossible' }
}

$py = (Get-Command python -ErrorAction Stop).Source
$old = @{}; foreach ($k in 'NPCORTEX_DB_PATH', 'PYTHONPATH', 'NPCORTEX_ALLOW_SCHEMA_V5', 'NPCORTEX_PORT') { $old[$k] = [Environment]::GetEnvironmentVariable($k) }
try {
    $env:NPCORTEX_DB_PATH = $db; $env:PYTHONPATH = Join-Path $root 'backend\src'; $env:NPCORTEX_ALLOW_SCHEMA_V5 = ''; $env:NPCORTEX_PORT = [string]$Port
    $cmdArgs = '/c ""{0}" -m npcortex_backend > "{1}" 2> "{2}""' -f $py, (Join-Path $tmp 'backend.out.log'), (Join-Path $tmp 'backend.err.log')
    $p = Start-Process -FilePath $env:ComSpec -ArgumentList $cmdArgs -WorkingDirectory (Join-Path $root 'backend') -PassThru -WindowStyle Hidden
} finally { foreach ($k in $old.Keys) { [Environment]::SetEnvironmentVariable($k, $old[$k]) } }
$ok = $false
for ($i = 0; $i -lt 40 -and -not $p.HasExited; $i++) {
    Start-Sleep -Milliseconds 500
    try { if ((Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2).StatusCode -eq 200) { $ok = $true; break } } catch { }
}
if (-not $ok) { if (-not $p.HasExited) { & taskkill.exe /PID $p.Id /T /F | Out-Null }; Fail "backend did not answer /health (see $tmp\backend.err.log)" }
Write-Host "temporary backend: pid $($p.Id), database $db"
$exit = 1
try {
    Wipe-World
    $log = Join-Path $tmp 'gametest.log'
    & cmd.exe /c "cd /d `"$forge`" && .\gradlew.bat --console=plain --offline runGameTestServer -PgametestNamespace=npcortexreal > `"$log`" 2>&1"; $code = $LASTEXITCODE
    $text = Get-Content $log
    $failed = @($text | Where-Object { $_ -match 'LogTestReporter\]: (\w+) failed!' } | ForEach-Object { if ($_ -match 'LogTestReporter\]: (\w+) failed! (.*)$') { "$($Matches[1]): $($Matches[2])" } })
    Write-Host ("gametest: exit $code | " + (($text | Where-Object { $_ -match 'GAME TESTS COMPLETE|All \d+ required|required tests failed' }) -join ' | '))
    foreach ($f in $failed) { Write-Host "  FAILED $f" }
    ($text | Where-Object { $_ -match 'REAL ' }) | ForEach-Object { Write-Host ('  ' + $_.Substring($_.IndexOf(']: ') + 3)) }
    # Fail closed on "0 tests ran": the number that completed must equal the number of @GameTest methods in the source.
    $expected = ([regex]::Matches((Get-Content (Join-Path $forge 'src\test\java\io\github\sirp00m\npcortex\NativeRealBackendGameTests.java') -Raw), '@GameTest\(')).Count
    $ran = 0; foreach ($l in $text) { if ($l -match '=+ (\d+) GAME TESTS COMPLETE') { $ran = [int]$Matches[1] } }
    if ($ran -ne $expected) { Write-Host "  FAILED the server ran $ran real GameTests but the source declares $expected"; $code = 1 }
    $exit = if ($code -eq 0 -and $failed.Count -eq 0) { 0 } else { 1 }
} finally {
    $proc = Get-Process -Id $p.Id -ErrorAction SilentlyContinue
    if ($proc) { & taskkill.exe /PID $p.Id /T /F | Out-Null; Write-Host "stopped the temporary backend (pid $($p.Id))" }
    $env:NPCORTEX_CPLUS_F1_SECRET_FILE = $oldSecret
}
$outbox = Join-Path $world 'data\npcortex\transport'
& python (Join-Path $PSScriptRoot 'verify_native_db.py') $db --outbox $outbox
$dbExit = $LASTEXITCODE
Write-Host "Evidence folder (temporary): $tmp"
if ($exit -ne 0 -or $dbExit -ne 0) { Write-Host 'NATIVE REAL BACKEND: FAIL'; exit 1 }
Write-Host 'NATIVE REAL BACKEND: PASS (real Python ledger over loopback HTTP; Forge and Python agreed on every receipt hash; no client, no chunk-save/crash coverage)'
exit 0
