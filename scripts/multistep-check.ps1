$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    Write-Host 'NPCortex v0.5.3-alpha Multi-Step Agent Checks' -ForegroundColor Cyan
    python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Python backend regression tests failed.' }
    Write-Host 'Backend tests passed. Now build the Forge mod separately.' -ForegroundColor Green
    Write-Host '.\scripts\dev-forge.ps1 -Task build'
} finally {
    Pop-Location
}
