$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    Write-Host 'NPCortex v0.5.6-alpha - Natural Intent tests' -ForegroundColor Cyan
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Backend regression tests failed.' }

    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-natural-intent-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $temp | Out-Null
    try {
        $src = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
        & javac --release 17 -d $temp `
            (Join-Path $src 'DirectIntent.java') `
            (Join-Path $src 'ActionCatalog.java') `
            (Join-Path $root 'tests\java\DirectIntentTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Standalone Java compilation failed.' }
        & java -cp $temp DirectIntentTest
        if ($LASTEXITCODE -ne 0) { throw 'Standalone Java regression tests failed.' }
    } finally {
        Remove-Item -Path $temp -Force -Recurse -ErrorAction SilentlyContinue
    }
    Write-Host 'Tests passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally {
    Pop-Location
}
