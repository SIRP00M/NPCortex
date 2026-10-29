# X0 Phase 2: real C2 navigation (navigate / navigateLong) with the ChunkMap notifier ON or OFF, on a fresh disposable world.
# Needs the real Python backend (temporary DB + temporary credential, never backend\data) and a TEST-ONLY stand-in for the model server
# (x0_stub_model.py echoes the goal's own move_to; no Qwen/Ollama is contacted). Stops only the processes it started.
param([ValidateSet('0','1')][string]$Hook = '1', [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'), [int]$Port = 18771, [int]$StubPort = 18772, [int]$TimeoutMinutes = 20)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$forge = Join-Path $root 'forge-mod'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-x0-c2-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path (Join-Path $tmp 'bridge') | Out-Null
foreach ($pt in $Port, $StubPort) { if (@(Get-NetTCPConnection -State Listen -LocalPort $pt -ErrorAction SilentlyContinue).Count) { Write-Host "port $pt busy"; exit 1 } }
$cfgDir = Join-Path $forge 'run-gametest\config'; New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
[IO.File]::WriteAllText((Join-Path $cfgDir 'npcortex-common.toml'), "[backend]`n`turl = `"http://127.0.0.1:$Port`"`n", (New-Object Text.UTF8Encoding $false))
$secret = Join-Path $tmp 'bridge\cplus-f1.secret'
$env:NPCORTEX_CPLUS_F1_TOKEN = ''; $oldSecret = $env:NPCORTEX_CPLUS_F1_SECRET_FILE; $env:NPCORTEX_CPLUS_F1_SECRET_FILE = $secret
$py = (Get-Command python -ErrorAction Stop).Source
$stub = Start-Process -FilePath $py -ArgumentList "-I `"$(Join-Path $PSScriptRoot 'x0_stub_model.py')`" $StubPort" -PassThru -WindowStyle Hidden
$old = @{}; foreach ($k in 'NPCORTEX_DB_PATH','PYTHONPATH','NPCORTEX_ALLOW_SCHEMA_V5','NPCORTEX_PORT','NPCORTEX_OLLAMA_URL') { $old[$k] = [Environment]::GetEnvironmentVariable($k) }
try {
    $env:NPCORTEX_DB_PATH = Join-Path $tmp 'x0.sqlite3'; $env:PYTHONPATH = Join-Path $root 'backend\src'; $env:NPCORTEX_ALLOW_SCHEMA_V5 = ''; $env:NPCORTEX_PORT = "$Port"; $env:NPCORTEX_OLLAMA_URL = "http://127.0.0.1:$StubPort"
    $args2 = '/c ""{0}" -m npcortex_backend > "{1}" 2> "{2}""' -f $py, (Join-Path $tmp 'backend.out.log'), (Join-Path $tmp 'backend.err.log')
    $be = Start-Process -FilePath $env:ComSpec -ArgumentList $args2 -WorkingDirectory (Join-Path $root 'backend') -PassThru -WindowStyle Hidden
} finally { foreach ($k in $old.Keys) { [Environment]::SetEnvironmentVariable($k, $old[$k]) } }
$ok = $false
for ($i = 0; $i -lt 40 -and -not $be.HasExited; $i++) { Start-Sleep -Milliseconds 500; try { if ((Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2).StatusCode -eq 200) { $ok = $true; break } } catch { } }
if (-not $ok) { foreach ($p in $be, $stub) { if ($p -and -not $p.HasExited) { & taskkill.exe /PID $p.Id /T /F | Out-Null } }; Write-Host "backend did not answer (see $tmp)"; exit 1 }
Write-Host "temporary backend pid $($be.Id), stand-in model pid $($stub.Id), db $tmp"
$code = 1
try {
    $env:X0_HOOK = $Hook
    & (Join-Path $PSScriptRoot 'run-x0.ps1') -Case c2nav -RunId $RunId -TimeoutMinutes $TimeoutMinutes
    $code = $LASTEXITCODE
} finally {
    foreach ($p in $be, $stub) { if ($p -and (Get-Process -Id $p.Id -ErrorAction SilentlyContinue)) { & taskkill.exe /PID $p.Id /T /F | Out-Null } }
    $env:NPCORTEX_CPLUS_F1_SECRET_FILE = $oldSecret; $env:X0_HOOK = $null
    $ev = Join-Path $root "docs\pap\x0-evidence\$RunId"
    Copy-Item (Join-Path $tmp 'backend.err.log') (Join-Path $ev "c2nav-hook$Hook.backend.err.log") -ErrorAction SilentlyContinue
}
exit $code
