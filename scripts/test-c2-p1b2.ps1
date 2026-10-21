# NPCortex C2-P1-B2 pure-JVM A* contracts, then the unchanged B1/P1-A/C1/backend regressions.
# A passing script is NOT proof of Forge compilation or gameplay acceptance.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required on PATH; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_p1b2_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2NavGrid.java') `
        (Join-Path $main 'C2TraversabilityGraph.java') `
        (Join-Path $main 'C2AStarPlanner.java') `
        (Join-Path $tests 'C2NavGridSelfTest.java') `
        (Join-Path $tests 'C2TraversabilityGraphSelfTest.java') `
        (Join-Path $tests 'C2AStarPlannerSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'P1-B2 isolated javac failed' }
    foreach ($test in 'C2NavGridSelfTest','C2TraversabilityGraphSelfTest','C2AStarPlannerSelfTest') {
        & java -cp $out ('io.github.sirp00m.npcortex.' + $test)
        if ($LASTEXITCODE -ne 0) { throw "P1-B2 pure JVM test failed: $test" }
    }
    & (Join-Path $PSScriptRoot 'test-c2-p1b1.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'P1-B1 / P1-A / C1 / Backend regression failed' }
    Write-Host 'C2 P1-B2 A* + B1/P1-A/C1/Backend regression PASS; NEXT: .\scripts\dev-forge.ps1 -Task build'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
