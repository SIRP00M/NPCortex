# v0.8.1-alpha checks: Python regression suite and independent Java task-policy tests.
# Full Minecraft/Forge compilation and behavior must still be checked on Windows.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    Write-Host 'NPCortex Python regression tests...' -ForegroundColor Cyan
    & python -m unittest discover -s backend/tests -q
    if ($LASTEXITCODE -ne 0) { throw 'Backend regression tests failed.' }
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-task-' + [guid]::NewGuid().ToString('N'))
    New-Item $temp -ItemType Directory -Force | Out-Null
    try {
        $javaDir = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
        & javac --release 17 -d $temp `
            (Join-Path $javaDir 'GatherTaskPolicy.java') `
            (Join-Path $javaDir 'GatherTaskIntent.java') `
            (Join-Path $root 'tests\java\GatherTaskPolicyTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java task policy compilation failed.' }
        & java -cp $temp GatherTaskPolicyTest
        if ($LASTEXITCODE -ne 0) { throw 'Java task policy tests failed.' }
    } finally {
        Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'Task policy checks passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally {
    Pop-Location
}
