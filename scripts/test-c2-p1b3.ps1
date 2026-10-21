# C2 P1-B3: Java 17-only pure Region/Portal/Hierarchical planner tests, then full B2/C1/backend.
# IMPORTANT: javac tests and stub compilation are not Minecraft Forge build/gameplay acceptance.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required on PATH; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_p1b3_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2NavGrid.java') `
        (Join-Path $main 'C2TraversabilityGraph.java') `
        (Join-Path $main 'C2AStarPlanner.java') `
        (Join-Path $main 'C2RegionGraph.java') `
        (Join-Path $main 'C2HierarchicalPlanner.java') `
        (Join-Path $tests 'C2HierarchicalPlannerSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'P1-B3 javac failed' }
    & java -cp $out 'io.github.sirp00m.npcortex.C2HierarchicalPlannerSelfTest'
    if ($LASTEXITCODE -ne 0) { throw 'P1-B3 pure JVM tests failed' }
    & (Join-Path $PSScriptRoot 'test-c2-p1b2.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'P1-B2/B1/P1-A/C1/Backend regression failed' }
    Write-Host 'C2 P1-B3 Region/Portal/Hierarchical A* + B2/B1/P1-A/C1/Backend regressions PASS; NEXT: .\scripts\dev-forge.ps1 -Task build'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
