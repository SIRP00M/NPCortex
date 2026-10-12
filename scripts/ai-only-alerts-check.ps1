# NPCortex v0.7.1-alpha - AI-only autonomous warning regression checks
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    Write-Host 'NPCortex: backend regression tests' -ForegroundColor Cyan
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests
    if ($LASTEXITCODE -ne 0) { throw 'Python regression tests failed.' }
    Write-Host 'NPCortex: autonomy speech Java policy tests' -ForegroundColor Cyan
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-speech-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    try {
        & javac --release 17 -d $tmp `
            (Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\AutonomySpeechPolicy.java') `
            (Join-Path $root 'tests\java\AutonomySpeechPolicyTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java policy compilation failed.' }
        & java -cp $tmp AutonomySpeechPolicyTest
        if ($LASTEXITCODE -ne 0) { throw 'Java policy tests failed.' }
    } finally { Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue }
    Write-Host 'Local tests passed; run Forge build for full verification.' -ForegroundColor Green
} finally { Pop-Location }
