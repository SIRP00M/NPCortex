# Phase 6 safe diagnostics — does not change Windows policy, delete or install anything.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Write-Host '=== NPCortex Phase 6 Diagnostics ===' -ForegroundColor Cyan
Write-Host "Repository: $repo"
$needed = @(
 'backend/src/npcortex_backend/agent.py',
 'forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldObservation.java',
 'forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldActions.java'
)
foreach ($item in $needed) {
  if (-not (Test-Path (Join-Path $repo $item))) { throw "Missing Phase 6 file: $item" }
}
Write-Host 'Phase 6 files: OK' -ForegroundColor Green
& java -version
& javac -version
Write-Host '--- Backend ---'
try {
  $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 5
  Write-Host "Backend: $($health.status) | model $($health.model)"
} catch { Write-Warning 'Backend offline. Start .\scripts\dev-backend.ps1 in a separate PowerShell window.' }
Write-Host '--- Python tests ---'
Push-Location $repo
try {
  & python -m unittest discover -s backend/tests -v
  if ($LASTEXITCODE -ne 0) { throw 'Python tests failed.' }
} finally { Pop-Location }
Write-Host 'Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Cyan
