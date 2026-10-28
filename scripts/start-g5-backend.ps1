# NPCortex G5 isolated test backend launcher (TEST ONLY).
# Creates g5-results/run-<id>/ with an isolation marker, starts the Python backend with NPCORTEX_DB_PATH pointing at ONE NEW sqlite file inside that
# folder, and records the process (pid/port/database) so that scripts/g5-preflight.ps1 can prove the backend on the port is this one.
# It refuses: a busy port, an existing database file, anything that resolves to backend/data, and a stray NPCORTEX_ALLOW_SCHEMA_V5 (a G5 database is
# always created fresh at v5; it never upgrades a database). The local credential is created/read by the backend itself and is never printed.
#   start:  powershell -File scripts\start-g5-backend.ps1 [-RunId 20261027-a] [-Port 8765]
#   stop:   powershell -File scripts\start-g5-backend.ps1 -Stop            (only stops the pid recorded for the current run)
param(
    [string]$RunId,
    [int]$Port = 8765,
    [string]$OutDir,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'g5-common.ps1')
if (-not $OutDir) { $OutDir = Join-Path $root 'g5-results' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Fail([string]$m) { [Console]::Error.WriteLine("start-g5-backend: $m"); exit 1 }

if ($Stop) {
    $dir = Resolve-G5RunDir '' $OutDir
    if (-not $dir) { Fail 'no current G5 run (g5-results/current-run.json)' }
    $rec = Read-G5Json (Join-Path $dir 'backend-run.json')
    if (-not $rec) { Fail 'backend-run.json not found' }
    $proc = Get-Process -Id ([int]$rec.pid) -ErrorAction SilentlyContinue
    if ($proc) {
        # a recycled pid must never be killed: it has to be the cmd.exe wrapper of THIS run (command line names the module and this run's log)
        $info = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction SilentlyContinue
        if (-not $info -or $info.Name -ine 'cmd.exe' -or $info.CommandLine -notmatch 'npcortex_backend' -or $info.CommandLine.IndexOf([string]$rec.out_log, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            Fail "pid $($rec.pid) is not the backend wrapper recorded for $dir any more (reused pid?); nothing was stopped."
        }
    }
    # Rev6: the backend's health BEFORE it is stopped is evidence for the collector ("healthy during the run" vs "unreachable after shutdown")
    $hb = 'UNREACHABLE'
    try { $hb = (Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:$($rec.port)/health") -TimeoutSec 4).Content } catch { }
    [IO.File]::WriteAllText((Join-Path $dir 'backend-stop.json'), ([ordered]@{ run_id = (Split-Path $dir -Leaf); stopped_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'; health_before_stop = $hb; pid = [int]$rec.pid } | ConvertTo-Json), $utf8)
    # the recorded pid is the cmd.exe wrapper (it keeps the redirected logs); /T takes its python child down with it
    if ($proc) { & taskkill.exe /PID $proc.Id /T /F | Out-Null; Write-Host "stopped backend pid $($rec.pid) (and its child) of $dir" } else { Write-Host 'the recorded backend is not running' }
    exit 0
}

if ($env:NPCORTEX_ALLOW_SCHEMA_V5) { Fail 'NPCORTEX_ALLOW_SCHEMA_V5 is set in this shell: a G5 test database is created fresh and must never be an upgrade of anything. Unset it.' }
if ($Port -lt 1024 -or $Port -gt 65535) { Fail 'Port must be 1024-65535.' }
if (@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count) { Fail "port $Port is already in use (is another backend, perhaps the live one, running?). Stop it first; this script never reuses a running backend." }
if (-not $RunId) { $RunId = Get-Date -Format 'yyyyMMdd-HHmmss' }
if ($RunId -notmatch '^[A-Za-z0-9_-]{1,40}$') { Fail 'RunId may contain only letters, digits, - and _.' }
$runDir = Join-Path $OutDir "run-$RunId"
if (Test-Path -LiteralPath $runDir) { Fail "run folder already exists: $runDir (a G5 run folder is never reused)" }
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$runDir = (Resolve-Path $runDir).Path
$db = Join-Path $runDir 'backend.sqlite3'
$marker = [ordered]@{ kind = $G5_MARKER_KIND; run_id = $RunId; db_path = $db; repo_default_db = (Join-Path $root 'backend/data/npcortex.sqlite3'); created_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z' }
[IO.File]::WriteAllText((Join-Path $runDir 'G5-ISOLATED.marker.json'), ($marker | ConvertTo-Json), $utf8)
$iso = Test-G5Isolation -G5RunDir $runDir
$bad = @($iso.checks | Where-Object { -not $_.ok -and $_.name -notin 'database_exists', 'database_readable_v5' })
if ($bad.Count) { Fail ("isolation proof failed before start: " + (($bad | ForEach-Object { $_.detail }) -join '; ')) }

$py = (Get-Command python -ErrorAction Stop).Source
$old = @{}; foreach ($k in 'NPCORTEX_DB_PATH', 'PYTHONPATH', 'NPCORTEX_ALLOW_SCHEMA_V5') { $old[$k] = [Environment]::GetEnvironmentVariable($k) }
try {
    $env:NPCORTEX_DB_PATH = $db
    $env:PYTHONPATH = Join-Path $root 'backend/src'
    $env:NPCORTEX_ALLOW_SCHEMA_V5 = ''
    $env:NPCORTEX_PORT = [string]$Port
    # Shell-executed cmd.exe wrapper: no inherited std handles (a redirected Start-Process would keep the caller's pipes open and hang any
    # script that captures this script's output) and the environment set above is inherited. The wrapper's pid is the recorded pid.
    $cmdArgs = '/c ""{0}" -m npcortex_backend > "{1}" 2> "{2}""' -f $py, (Join-Path $runDir 'backend.out.log'), (Join-Path $runDir 'backend.err.log')
    $p = Start-Process -FilePath $env:ComSpec -ArgumentList $cmdArgs -WorkingDirectory (Join-Path $root 'backend') -PassThru -WindowStyle Hidden
} finally {
    foreach ($k in $old.Keys) { [Environment]::SetEnvironmentVariable($k, $old[$k]) }
    Remove-Item Env:\NPCORTEX_PORT -ErrorAction SilentlyContinue
}
$ok = $false
for ($i = 0; $i -lt 40 -and -not $p.HasExited; $i++) {
    Start-Sleep -Milliseconds 500
    try { if ((Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2).StatusCode -eq 200) { $ok = $true; break } } catch { }
}
if (-not $ok) { if (-not $p.HasExited) { & taskkill.exe /PID $p.Id /T /F | Out-Null }; Fail "backend did not answer /health (see $runDir\backend.err.log)" }
$rec = [ordered]@{ pid = $p.Id; port = $Port; db_path = $db; started_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'; python = $py; out_log = (Join-Path $runDir 'backend.out.log'); err_log = (Join-Path $runDir 'backend.err.log') }
[IO.File]::WriteAllText((Join-Path $runDir 'backend-run.json'), ($rec | ConvertTo-Json), $utf8)
[IO.File]::WriteAllText((Join-Path $OutDir 'current-run.json'), (@{ run_dir = $runDir; run_id = $RunId } | ConvertTo-Json), $utf8)
Write-Host "G5 isolated backend running: pid $($p.Id) port $Port"
Write-Host "  database : $db"
Write-Host "  run folder: $runDir"
Write-Host 'Next: scripts\g5-preflight.ps1 -Phase Start -WorldName <fresh world>   (then /function npcortex_g5:start in game)'
