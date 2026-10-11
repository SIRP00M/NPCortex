# NPCortex In-Game Manager diagnostics. Run from the repo root.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Write-Host '=== NPCortex Manager Diagnostics ===' -ForegroundColor Cyan
Write-Host "Repository: $root"
& java -version
& javac -version
if (Test-Path (Join-Path $root 'forge-mod\gradlew.bat')) {
    Write-Host 'Forge Gradle wrapper: found' -ForegroundColor Green
} else {
    Write-Warning 'Forge Gradle wrapper not found; dev-forge.ps1 will bootstrap it.'
}
$skins = Join-Path $root 'forge-mod\run\config\npcortex\skins'
Write-Host "Development instance skin path: $skins"
if (Test-Path $skins) { Get-ChildItem $skins -Filter '*.png' | Select-Object Name,Length }
else { Write-Warning 'No skins folder in the development instance (manager can create one).' }
try { $health = Invoke-RestMethod 'http://127.0.0.1:8765/health' -TimeoutSec 3
    Write-Host "Backend health: $($health.status)" -ForegroundColor Green
} catch { Write-Warning 'Python backend offline. Start .\scripts\dev-backend.ps1' }
& (Join-Path $PSScriptRoot 'test-skin-manager.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Skin Java test failed.' }
