# NPCortex v0.5.7-alpha - Verify backend tests and standalone DirectIntent grammar.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    Write-Host 'NPCortex v0.5.7 Action Dialogue checks' -ForegroundColor Cyan
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Python regression tests failed.' }

    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-speech-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $temp | Out-Null
    try {
        $java = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
        & javac --release 17 -d $temp `
            (Join-Path $java 'DirectIntent.java') `
            (Join-Path $java 'ActionCatalog.java') `
            (Join-Path $root 'tests\java\DirectIntentTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Standalone Java compile failed.' }
        & java -cp $temp DirectIntentTest
        if ($LASTEXITCODE -ne 0) { throw 'Standalone Java tests failed.' }
    } finally {
        Remove-Item -Path $temp -Force -Recurse -ErrorAction SilentlyContinue
    }

    Write-Host 'Local tests passed. Next: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally {
    Pop-Location
}
