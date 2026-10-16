# C2-P0 precision + C1 MotionPlan isolated tests. This does NOT certify Forge Build / gameplay.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required on PATH; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c2_p0_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2MoveGeometry.java') `
        (Join-Path $main 'C2Events.java') `
        (Join-Path $main 'C2Scheduler.java') `
        (Join-Path $main 'C1P1MotionPlan.java') `
        (Join-Path $tests 'C1P1MotionPlanSelfTest.java') `
        (Join-Path $tests 'C2FoundationSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C2 isolated javac failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2FoundationSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C2 isolated JVM assertions failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1P1MotionPlanSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1 motion precision regression failed' }
    $env:PYTHONPATH = (Join-Path $root 'backend/src') + [IO.Path]::PathSeparator + (Join-Path $root 'backend/tests')
    Push-Location $root
    try {
        & python -m unittest discover -s backend/tests -p 'test_c0*.py' -q
        if ($LASTEXITCODE -ne 0) { throw 'C0 backend regression failed' }
        & python -m unittest discover -s backend/tests -p 'test_c2*.py' -q
        if ($LASTEXITCODE -ne 0) { throw 'C2 backend contract tests failed' }
    } finally { Pop-Location }
    Write-Host 'C2 isolated tests PASS. NEXT: .\scripts\dev-forge.ps1 -Task build'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
