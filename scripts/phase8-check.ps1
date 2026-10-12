# NPCortex Phase 8 - local tests, no ForgeGradle download required.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    Write-Host 'NPCortex Phase 8: Python backend tests' -ForegroundColor Cyan
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Python tests failed.' }
    Write-Host 'NPCortex Phase 8: pure Java policy tests' -ForegroundColor Cyan
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-auto-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temp -Force | Out-Null
    try {
        & javac --release 17 -d $temp `
            (Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\AutonomyPolicy.java') `
            (Join-Path $root 'tests\java\AutonomyPolicyTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
        & java -cp $temp AutonomyPolicyTest
        if ($LASTEXITCODE -ne 0) { throw 'Java policy tests failed.' }
    } finally { Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Host 'All local checks passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally { Pop-Location }
