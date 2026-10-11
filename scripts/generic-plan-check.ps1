$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    Write-Host 'NPCortex v0.5.4-alpha - Generic Action Planner diagnostics' -ForegroundColor Cyan
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Backend regression tests failed.' }
    $catalog = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\ActionCatalog.java'
    $runner = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\ActionPlanRunner.java'
    if (-not (Test-Path $catalog) -or -not (Test-Path $runner)) { throw 'Missing Java planner source files.' }
    Write-Host 'Python + source checks passed.' -ForegroundColor Green
    Write-Host 'Next: .\scripts\dev-forge.ps1 -Task build'
    Write-Host 'Note: Forge compile and in-game testing must run on your machine.'
} finally {
    Pop-Location
}
