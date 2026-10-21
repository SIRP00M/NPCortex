# C2-P1-C Fix #1: Java 17 cancellation policy + Python cancellation endpoint,
# then the full P1-C/C1/B3/B2/B1/P1-A/Backend regression chain.
# Does NOT independently certify Minecraft Forge compilation or live gameplay.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_p1c_fix1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2CancellationProtocol.java') `
        (Join-Path $tests 'C2CancellationProtocolSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Fix #1 cancellation policy javac failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2CancellationProtocolSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Fix #1 cancellation policy assertions failed' }
    $env:PYTHONPATH = (Join-Path $root 'backend/src') + [IO.Path]::PathSeparator + (Join-Path $root 'backend/tests')
    Push-Location $root
    try {
        & python -m unittest discover -s backend/tests -p 'test_c2_cancel_recovery.py' -v
        if ($LASTEXITCODE -ne 0) { throw 'Fix #1 backend cancellation contract tests failed' }
    } finally { Pop-Location }
    & (Join-Path $PSScriptRoot 'test-c2-p1c.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'P1-C + earlier Java/Backend regression failed' }
    Write-Host 'PASS C2-P1-C Fix #1 cancellation + prior regression. NEXT: Forge Build and Windows Gameplay.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
