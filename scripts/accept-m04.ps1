<#
.SYNOPSIS
    Runs NPCortex M04 automated static/backend/Java/Forge checks and packages test evidence.
.DESCRIPTION
    Default: .\scripts\accept-m04.ps1
    The optional -WorldName and -Database enable READ-ONLY evidence correlation.
    No world action, server restart, mining, pickup or gameplay acceptance.
#>
param(
    [switch]$SkipForge,
    [switch]$SkipBackendFull,
    [string]$WorldName,
    [string]$WorldPath,
    [string]$Database,
    [string]$OutputRoot,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($WorldName -and $WorldPath) { throw 'Choose either -WorldName or -WorldPath, not both.' }
if ($WorldName) { $WorldPath = Join-Path $repo ('forge-mod\run\saves\' + $WorldName) }
if ([bool]$WorldPath -ne [bool]$Database) {
    throw 'Read-only evidence review needs BOTH -WorldName/-WorldPath and -Database. Omit both for offline automated tests.'
}
$runner = Join-Path $PSScriptRoot 'm04_auto_accept.py'
$arguments = @('-B', $runner)
if ($SkipForge) { $arguments += '--skip-forge' }
if ($SkipBackendFull) { $arguments += '--skip-backend-full' }
if ($WorldPath) {
    if (-not (Test-Path -LiteralPath (Join-Path $WorldPath 'level.dat') -PathType Leaf)) {
        throw ('Missing Minecraft level.dat: ' + $WorldPath)
    }
    if (-not (Test-Path -LiteralPath $Database -PathType Leaf)) {
        throw ('Missing backend DB: ' + $Database)
    }
    $arguments += @('--world', (Resolve-Path -LiteralPath $WorldPath).Path,
                     '--database', (Resolve-Path -LiteralPath $Database).Path)
}
if ($OutputRoot) { $arguments += @('--output-root', $OutputRoot) }
& $Python @arguments
$code = $LASTEXITCODE
if ($code -ne 0) { throw "M04 automated acceptance encountered FAIL (exit $code); inspect Evidence ZIP and summary. Gameplay remains HOLD." }
Write-Host 'Offline automated run completed. Check the report: skipped/unavailable gates remain HOLD; gameplay is NOT accepted.' -ForegroundColor Yellow
