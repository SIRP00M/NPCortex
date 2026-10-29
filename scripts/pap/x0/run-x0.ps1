# X0 vanilla chunk-tracking experiment runner (TEST-ONLY). One case per run, on a FRESH disposable world:
#   scripts\pap\x0\run-x0.ps1 -Case smoke|noavatar|spawned|hook|restart [-Steps 24] [-End despawn|quit] [-SpawnWatch] [-KeepWorld] [-RunId x]
# It wipes ONLY forge-mod\run-gametest\world (guarded), caps the JVM heap and wall time, runs the GameTest namespace npcortexchunkx
# headless with no human player, copies the metrics and an independent region-file oracle to docs\pap\x0-evidence\<RunId>\ BEFORE anything
# else, and checks that the real world, backend data and configs did not change. It never touches git, a live world or the live DB.
param(
    [Parameter(Mandatory)][ValidateSet('smoke','noavatar','spawned','hook','restart','c2nav','earlydespawn')][string]$Case,
    [int]$Steps = 24, [ValidateSet('despawn','quit')][string]$End = 'despawn', [switch]$SpawnWatch, [switch]$NoMove, [switch]$KeepWorld,
    [string]$RunId = (Get-Date -Format 'yyyyMMdd-HHmmss'), [int]$TimeoutMinutes = 25, [int]$MaxNewChunks = 2500, [int]$HeapMb = 3072, [int]$MinFreeGb = 20
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
$forge = Join-Path $root 'forge-mod'; $world = Join-Path $forge 'run-gametest\world'
$evid = Join-Path $root "docs\pap\x0-evidence\$RunId"; New-Item -ItemType Directory -Force -Path $evid | Out-Null
function Fail([string]$m) { Write-Host "run-x0: $m"; exit 1 }
$free = [math]::Round((Get-PSDrive -Name ($root.Substring(0,1))).Free / 1GB, 1)
if ($free -lt $MinFreeGb) { Fail "only $free GB free (< $MinFreeGb)" }
# protected state: signature of everything this experiment must not touch
function Signature { $items = @(); foreach ($p in 'forge-mod\run\saves', 'forge-mod\run\config', 'backend\data') { $d = Join-Path $root $p; if (Test-Path $d) { $items += Get-ChildItem -LiteralPath $d -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object { "$($_.FullName)|$($_.Length)|$($_.LastWriteTimeUtc.Ticks)" } } }; $sha = [Security.Cryptography.SHA256]::Create(); [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($items | Sort-Object) -join "`n"))).Replace('-','') + " ($($items.Count) files)" }
$before = Signature
if (-not $KeepWorld) {
    if (Test-Path -LiteralPath $world) { if ((Split-Path -Leaf $world) -ne 'world' -or $world -notlike '*run-gametest*') { Fail "refusing to delete $world" }; Remove-Item -LiteralPath $world -Recurse -Force }
}
$env:X0_CASE = $Case; $env:X0_STEPS = "$Steps"; $env:X0_END = $End; $env:X0_SPAWNWATCH = $(if ($SpawnWatch) { '1' } else { '0' }); $env:X0_NOMOVE = $(if ($NoMove) { '1' } else { '0' }); $env:X0_MAX_NEW_CHUNKS = "$MaxNewChunks"
$env:JAVA_TOOL_OPTIONS = "-Xmx${HeapMb}m"
$log = Join-Path $evid "$Case.gradle.log"
$sw = [Diagnostics.Stopwatch]::StartNew()
$proc = Start-Process -FilePath $env:ComSpec -ArgumentList "/c cd /d `"$forge`" && .\gradlew.bat --console=plain --offline runGameTestServer -PgametestNamespace=npcortexchunkx > `"$log`" 2>&1" -PassThru -WindowStyle Hidden
$timedOut = -not $proc.WaitForExit($TimeoutMinutes * 60000)
if ($timedOut) { & taskkill.exe /PID $proc.Id /T /F | Out-Null; Write-Host "run-x0: WATCHDOG killed the run after $TimeoutMinutes minutes" }
$sw.Stop()
$env:X0_CASE = $null; $env:JAVA_TOOL_OPTIONS = $null
$text = if (Test-Path $log) { Get-Content $log } else { @() }
$data = @($text | Where-Object { $_ -match 'X0DATA ' } | ForEach-Object { $_.Substring($_.IndexOf('X0DATA ') + 7) })
$data | Set-Content -Path (Join-Path $evid "$Case.jsonl") -Encoding utf8
$summary = @($text | Where-Object { $_ -match 'GAME TESTS COMPLETE|required tests failed|All \d+ required|LogTestReporter.*(failed|passed)|X0 LIMIT|Exception' } | Select-Object -First 12)
Write-Host "case=$Case wall=$([math]::Round($sw.Elapsed.TotalSeconds))s timedOut=$timedOut metricsLines=$($data.Count)"; $summary | ForEach-Object { Write-Host "  $_" }
# oracle on the world as the server left it
$oracle = & python -I (Join-Path $PSScriptRoot 'x0_region_oracle.py') $world
$oracle | Set-Content -Path (Join-Path $evid "$Case.region-oracle.json") -Encoding utf8
Write-Host "oracle: $oracle"
Copy-Item -LiteralPath (Join-Path $forge 'run-gametest\logs\latest.log') -Destination (Join-Path $evid "$Case.latest.log") -ErrorAction SilentlyContinue
$after = Signature
$same = $before -eq $after
Write-Host "protected state unchanged: $same  ($before)"
if (-not $same) { Write-Host 'run-x0: PROTECTED STATE CHANGED'; exit 2 }
if ($timedOut) { exit 3 }
exit 0
