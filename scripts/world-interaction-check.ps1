# Phase v0.8.0 pre-build checks (no network needed for policy tests).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    Write-Host 'Running NPCortex backend regression tests...' -ForegroundColor Cyan
    & python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Python tests failed' }
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-world-' + [guid]::NewGuid().ToString('N'))
    New-Item -Path $temp -ItemType Directory -Force | Out-Null
    try {
        Write-Host 'Compiling pure Java world policy tests...' -ForegroundColor Cyan
        & javac --release 17 -d $temp `
            (Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\WorldInteractionPolicy.java') `
            (Join-Path $root 'tests\java\WorldInteractionPolicyTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java policy compile failed' }
        & java -cp $temp WorldInteractionPolicyTest
        if ($LASTEXITCODE -ne 0) { throw 'Java policy test failed' }
    } finally { Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Host 'Local tests passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally { Pop-Location }
