# Stage 4 native Break/Pickup GAMEPLAY ACCEPTANCE harness (test-only). One entry point, isolated from your normal game and database.
#   scripts\native-acceptance.ps1 -Action Start                       # isolated backend (port 18765, temp DB + secret); backs up and redirects the client's backend URL
#   scripts\native-acceptance.ps1 -Action Client                      # runClient with the isolated credential (create/open a world whose name contains "Native")
#   scripts\native-acceptance.ps1 -Action Install -WorldName <World>  # copies the acceptance data pack into that world's datapacks
#   (in game: /reload ; /function npcortex_native:setup ; /function npcortex_native:info)
#   scripts\native-acceptance.ps1 -Action Allow                       # writes the native allow-list from the player UUID + arena anchor found in latest.log
#   (in game: use the Manager Brk/Pck, follow /function npcortex_native:guide, then /function npcortex_native:check/all)
#   scripts\native-acceptance.ps1 -Action Report                      # read-only PASS/FAIL/INCONCLUSIVE/NOT_TESTED report from the ledger + latest.log
#   scripts\native-acceptance.ps1 -Action Stop                        # stops ONLY the backend this script started, restores your backend URL config
# Never touched: backend\data, your credential file, your existing backend on 8765, any world other than the one named, any ledger row.
# The report is read-only: it never releases a claim, reconciles a HOLD or replays anything.
param(
    [Parameter(Mandatory)][ValidateSet('Start', 'Client', 'Install', 'Allow', 'Report', 'Stop', 'Status')][string]$Action,
    [int]$Port = 18765,
    [string]$WorldName,
    [string[]]$Actors = @('*'),
    [string]$RunDir
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$forge = Join-Path $root 'forge-mod'
$cfgDir = Join-Path $forge 'run\config'
$toml = Join-Path $cfgDir 'npcortex-common.toml'
if (-not $RunDir) { $RunDir = Join-Path ([IO.Path]::GetTempPath()) 'npcortex-owner-native' }
$stateFile = Join-Path $RunDir 'state.json'
function Fail([string]$m) { Write-Host "native-acceptance: $m" -ForegroundColor Red; exit 1 }
function Read-State { if (-not (Test-Path -LiteralPath $stateFile)) { Fail 'No acceptance session. Run -Action Start first.' }; Get-Content -Raw -LiteralPath $stateFile | ConvertFrom-Json }
function Latest-Log { Join-Path $forge 'run\logs\latest.log' }

switch ($Action) {
    'Start' {
        if ($Port -eq 8765) { Fail 'Port 8765 is the normal backend. Use a different port (default 18765).' }
        if ($Port -lt 1024 -or $Port -gt 65535) { Fail 'Bad port.' }
        if (Test-Path -LiteralPath $stateFile) { Fail "A session already exists ($stateFile). Run -Action Stop first (it never deletes the evidence)." }
        if (@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count) { Fail "Port $Port is busy. This script never reuses a running backend." }
        New-Item -ItemType Directory -Force -Path (Join-Path $RunDir 'bridge') | Out-Null
        $db = Join-Path $RunDir 'native.sqlite3'
        $secret = Join-Path $RunDir 'bridge\cplus-f1.secret'
        # Back up the client's backend URL once, then point it at the isolated backend. An existing backup is never overwritten.
        New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
        $bak = $toml + '.acceptance-bak'
        $hadToml = Test-Path -LiteralPath $toml
        if ($hadToml -and -not (Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $toml -Destination $bak }
        $text = "#AI backend settings. Temporary: written by scripts\native-acceptance.ps1, restored by -Action Stop.`n[backend]`n`t#Local HTTP backend URL (no trailing slash)`n`turl = `"http://127.0.0.1:$Port`"`n"
        [IO.File]::WriteAllText($toml, $text, (New-Object Text.UTF8Encoding $false))
        $py = (Get-Command python -ErrorAction Stop).Source
        $old = @{}; foreach ($k in 'NPCORTEX_DB_PATH', 'PYTHONPATH', 'NPCORTEX_ALLOW_SCHEMA_V5', 'NPCORTEX_PORT', 'NPCORTEX_CPLUS_F1_SECRET_FILE', 'NPCORTEX_CPLUS_F1_TOKEN') { $old[$k] = [Environment]::GetEnvironmentVariable($k) }
        try {
            $env:NPCORTEX_DB_PATH = $db; $env:PYTHONPATH = Join-Path $root 'backend\src'; $env:NPCORTEX_ALLOW_SCHEMA_V5 = ''; $env:NPCORTEX_PORT = [string]$Port
            $env:NPCORTEX_CPLUS_F1_SECRET_FILE = $secret; $env:NPCORTEX_CPLUS_F1_TOKEN = ''
            $cmdArgs = '/c ""{0}" -m npcortex_backend > "{1}" 2> "{2}""' -f $py, (Join-Path $RunDir 'backend.out.log'), (Join-Path $RunDir 'backend.err.log')
            $p = Start-Process -FilePath $env:ComSpec -ArgumentList $cmdArgs -WorkingDirectory (Join-Path $root 'backend') -PassThru -WindowStyle Hidden
        } finally { foreach ($k in $old.Keys) { [Environment]::SetEnvironmentVariable($k, $old[$k]) } }
        $ok = $false
        for ($i = 0; $i -lt 40 -and -not $p.HasExited; $i++) {
            Start-Sleep -Milliseconds 500
            try { if ((Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 2).StatusCode -eq 200) { $ok = $true; break } } catch { }
        }
        if (-not $ok) {
            if (-not $p.HasExited) { & taskkill.exe /PID $p.Id /T /F | Out-Null }
            if ($hadToml -and (Test-Path -LiteralPath $bak)) { Copy-Item -LiteralPath $bak -Destination $toml -Force; Remove-Item -LiteralPath $bak }
            Fail "Backend did not answer /health (see $RunDir\backend.err.log). Your config was restored."
        }
        [ordered]@{ pid = $p.Id; port = $Port; db = $db; secret = $secret; hadToml = $hadToml; startTicks = $p.StartTime.ToUniversalTime().Ticks; started = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding ascii
        Write-Host "Isolated backend up: pid $($p.Id), http://127.0.0.1:$Port, database $db" -ForegroundColor Green
        Write-Host "Next: scripts\native-acceptance.ps1 -Action Client   (then create/open a world whose name contains 'Native')"
    }
    'Client' {
        $s = Read-State
        $env:NPCORTEX_CPLUS_F1_SECRET_FILE = $s.secret
        $env:NPCORTEX_CPLUS_F1_TOKEN = ''
        Push-Location $forge
        try { & .\gradlew.bat runClient } finally { Pop-Location }
    }
    'Install' {
        if (-not $WorldName) { Fail '-WorldName is required (the install script itself refuses names without "Native").' }
        Read-State | Out-Null
        & (Join-Path $PSScriptRoot 'install-native-datapack.ps1') -WorldName $WorldName
    }
    'Allow' {
        Read-State | Out-Null
        $log = Latest-Log
        if (-not (Test-Path -LiteralPath $log)) { Fail "No $log yet. Start the client, join the world and run /function npcortex_native:info first." }
        $lines = Get-Content -LiteralPath $log
        $uuidLine = $lines | Where-Object { $_ -match 'UUID of player \S+ is ([0-9a-fA-F-]{36})' } | Select-Object -Last 1
        if (-not $uuidLine) { Fail 'No "UUID of player ..." line in latest.log: join a world first.' }
        $null = $uuidLine -match 'UUID of player \S+ is ([0-9a-fA-F-]{36})'; $uuid = $Matches[1]
        $anchorLine = $lines | Where-Object { $_ -match 'anchor \(SW corner, feet level\): \[(-?[\d.]+)d?, (-?[\d.]+)d?, (-?[\d.]+)d?\]' } | Select-Object -Last 1
        if (-not $anchorLine) { Fail 'No arena anchor in latest.log: run /function npcortex_native:setup then /function npcortex_native:info in the game.' }
        $null = $anchorLine -match 'anchor \(SW corner, feet level\): \[(-?[\d.]+)d?, (-?[\d.]+)d?, (-?[\d.]+)d?\]'
        $a = @([math]::Floor([double]$Matches[1]), [math]::Floor([double]$Matches[2]), [math]::Floor([double]$Matches[3])) -join ','
        Write-Host "player $uuid, anchor $a, actors $($Actors -join ',')"
        & (Join-Path $PSScriptRoot 'install-native-allowlist.ps1') -ConfigDir $cfgDir -PlayerUuid $uuid -Actors $Actors -Anchor $a -Force
    }
    'Report' {
        $s = Read-State
        $out = Join-Path $RunDir ('report-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        # Copy the evidence first so the report is reproducible: the live files keep being written by the running game and backend.
        New-Item -ItemType Directory -Force -Path $out | Out-Null
        $logs = @()
        foreach ($f in 'latest.log') { $src = Join-Path $forge "run\logs\$f"; if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $out $f); $logs += (Join-Path $out $f) } }
        $dbCopy = Join-Path $out 'native.sqlite3'
        & python -c "import sqlite3,sys; s=sqlite3.connect('file:'+sys.argv[1].replace('\\','/')+'?mode=ro',uri=True); d=sqlite3.connect(sys.argv[2]); s.backup(d); d.close(); s.close()" $s.db $dbCopy
        if ($LASTEXITCODE -ne 0) { Fail 'Could not take a read-only snapshot of the ledger.' }
        $argsList = @((Join-Path $PSScriptRoot 'native_acceptance_report.py'), '--db', $dbCopy, '--json', (Join-Path $out 'report.json'))
        foreach ($l in $logs) { $argsList += @('--log', $l) }
        & python @argsList | Tee-Object -FilePath (Join-Path $out 'report.txt')
        $code = $LASTEXITCODE
        Write-Host "Evidence folder: $out"
        exit $code
    }
    'Status' {
        $s = Read-State
        $alive = [bool](Get-Process -Id $s.pid -ErrorAction SilentlyContinue)
        Write-Host "session: backend pid $($s.pid) alive=$alive port $($s.port) db $($s.db)"
    }
    'Stop' {
        $s = Read-State
        $proc = Get-Process -Id $s.pid -ErrorAction SilentlyContinue
        # A reused PID must never be killed: only the exact process this script started (same start time) is stopped.
        $ticks = -1; if ($proc) { try { $ticks = $proc.StartTime.ToUniversalTime().Ticks } catch { $ticks = -1 } }
        if ($proc -and $ticks -ne [int64]$s.startTicks) { Write-Host "PID $($s.pid) is now a different process; not touching it."; $proc = $null }
        if ($proc) { & taskkill.exe /PID $s.pid /T /F | Out-Null; Write-Host "Stopped the isolated backend (pid $($s.pid))." } else { Write-Host 'The isolated backend was not running.' }
        $bak = $toml + '.acceptance-bak'
        if (Test-Path -LiteralPath $bak) { Copy-Item -LiteralPath $bak -Destination $toml -Force; Remove-Item -LiteralPath $bak; Write-Host 'Restored your npcortex-common.toml.' }
        elseif (-not $s.hadToml -and (Test-Path -LiteralPath $toml)) { Remove-Item -LiteralPath $toml; Write-Host 'Removed the temporary npcortex-common.toml (there was none before).' }
        Move-Item -LiteralPath $stateFile -Destination (Join-Path $RunDir ('state-ended-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json'))
        Write-Host "Evidence kept in $RunDir (database, logs, reports). Nothing was deleted."
    }
}
