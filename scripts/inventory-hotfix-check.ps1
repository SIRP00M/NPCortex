# NPCortex v0.5.1-alpha inventory hotfix diagnostics.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root
Write-Host '=== NPCortex Inventory Awareness Hotfix v0.5.1-alpha ===' -ForegroundColor Cyan

$expected = @(
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldActions.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldObservation.java',
    'backend/src/npcortex_backend/agent.py',
    'backend/tests/test_inventory_hotfix.py'
)
foreach ($path in $expected) {
    if (-not (Test-Path (Join-Path $root $path))) { throw "Missing hotfix file: $path" }
}

$env:PYTHONPATH = Join-Path $root 'backend/src'
& python -m unittest discover -s 'backend/tests' -v
if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed; do not start Minecraft yet.' }

try {
    $health = Invoke-RestMethod 'http://127.0.0.1:8765/health' -TimeoutSec 3
    Write-Host "Backend online: $($health.model). Restart it if it was launched before the hotfix." -ForegroundColor Green
} catch {
    Write-Warning 'Backend offline. Start .\scripts\dev-backend.ps1 in a separate terminal.'
}
Write-Host 'Python checks passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
