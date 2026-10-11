$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:PYTHONPATH = Join-Path $root 'backend\src'
Write-Host 'NPCortex AI Agent Hotfix: Python Regression Tests'
Push-Location $root
try {
    python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
    Write-Host 'All backend tests passed.' -ForegroundColor Green
} finally { Pop-Location }
