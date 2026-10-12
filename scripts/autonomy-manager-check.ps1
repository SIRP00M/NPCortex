# NPCortex v0.7.2-alpha - Local regression tests (ForgeGradle build is separate).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    Write-Host 'NPCortex: Python backend regression tests' -ForegroundColor Cyan
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests
    if ($LASTEXITCODE -ne 0) { throw 'Python regression tests failed.' }
    Write-Host 'NPCortex: autonomy ID/UUID selector Java test' -ForegroundColor Cyan
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-auto-manager-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temp -Force | Out-Null
    try {
        & javac --release 17 -d $temp `
            (Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\AutonomySelector.java') `
            (Join-Path $root 'tests\java\AutonomySelectorTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
        & java -cp $temp AutonomySelectorTest
        if ($LASTEXITCODE -ne 0) { throw 'AutonomySelector test failed.' }
    } finally { Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Host 'Local regression tests passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally { Pop-Location }
