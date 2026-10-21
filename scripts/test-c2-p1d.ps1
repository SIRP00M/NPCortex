# C2-P1-D bounded dynamic replanning unit/integration test, followed by the
# previously accepted P1-C Fix #1 / B3 / B2 / B1 / C1 / backend regressions.
# Java 17 isolated compile is NOT a substitute for Forge Gradle build/gameplay.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_p1d_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $src 'C2NavGrid.java') `
        (Join-Path $src 'C2TraversalCost.java') `
        (Join-Path $src 'C2TraversabilityGraph.java') `
        (Join-Path $src 'C2AStarPlanner.java') `
        (Join-Path $src 'C2RegionGraph.java') `
        (Join-Path $src 'C2HierarchicalPlanner.java') `
        (Join-Path $src 'C2F2SafeShortcut.java') `
        (Join-Path $src 'C2WaypointExecution.java') `
        (Join-Path $src 'C2ReplanningPolicy.java') `
        (Join-Path $tests 'C2ReplanningPolicySelfTest.java') `
        (Join-Path $tests 'C2P1DNavigationSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'P1-D Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2ReplanningPolicySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'P1-D replanning policy assertions failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2P1DNavigationSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'P1-D synthetic reroute integration failed' }
    & (Join-Path $PSScriptRoot 'test-c2-p1c-fix1.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'P1-C Fix #1 / earlier regressions failed' }
    Write-Host 'PASS C2 P1-D + C2-P1-C Fix #1 / B3 / B2 / B1 / C1 / Backend regressions'
    Write-Host 'NEXT: .\scripts\dev-forge.ps1 -Task build (Windows Forge 1.20.1)'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
