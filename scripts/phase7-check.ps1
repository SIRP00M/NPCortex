# NPCortex Phase 7: verifies backend, SQLite, and Java/Forge prerequisites.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
Write-Host '=== NPCortex v0.5.0-alpha | Phase 7 ===' -ForegroundColor Cyan
& java -version
& javac -version
$env:PYTHONPATH = Join-Path $root 'backend\src'
& python -m unittest discover -s 'backend\tests' -v
if ($LASTEXITCODE -ne 0) { throw 'Python backend tests failed. Do not build yet.' }
Write-Host 'SQLite uses the Python standard library (no pip install required).'
$database = Join-Path $root 'backend\data\npcortex.sqlite3'
if (Test-Path $database) { Write-Host "Existing memory DB: $database" }
else { Write-Host "Memory DB will be created on first backend startup: $database" }
try {
    $health = Invoke-RestMethod 'http://127.0.0.1:8765/health' -TimeoutSec 3
    Write-Host "Backend: $($health.status), model: $($health.model)" -ForegroundColor Green
} catch {
    Write-Warning 'Backend not started; open a new terminal and run .\scripts\dev-backend.ps1'
}
Write-Host 'Phase 7 checks complete. Run .\scripts\dev-forge.ps1 -Task build next.' -ForegroundColor Green
