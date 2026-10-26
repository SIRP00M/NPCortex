param(
    [string]$WorldName = 'New World (2)',
    [Parameter(Mandatory=$true)][string]$Database,
    [string]$Output = '',
    [string]$BackendURL = 'http://127.0.0.1:8765',
    [switch]$Once,
    [switch]$NoZip
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$world = Join-Path (Join-Path $root 'forge-mod\run\saves') $WorldName
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) {
    throw "World not found: $world"
}
if (-not (Test-Path -LiteralPath $Database)) {
    throw "Backend DB not found: $Database. Confirm NPCORTEX_DB_PATH in the Backend console."
}
if ($Output -eq '') { $Output = Join-Path $root ('M03-LiveEvidence-'+(Get-Date -Format yyyyMMdd-HHmmss)) }
$argsList = @((Join-Path $PSScriptRoot 'm03_evidence_watch.py'), '--world', $world, '--database', (Resolve-Path -LiteralPath $Database).Path, '--output', $Output, '--backend-url', $BackendURL)
if ($Once) { $argsList += '--once' }
if (-not $NoZip) { $argsList += '--zip' }
& python @argsList
if ($LASTEXITCODE -ne 0) { throw "M03 evidence watch exited $LASTEXITCODE" }
