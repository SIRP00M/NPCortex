# NPCortex C2-P1-C pure Java 17 waypoint state machine + existing B3/C1/backend regressions.
# NOT proof of Forge compilation, real C1 ticket wiring, navigation accuracy, or MSPT.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_p1c_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2NavGrid.java') `
        (Join-Path $main 'C2TraversalCost.java') `
        (Join-Path $main 'C2TraversabilityGraph.java') `
        (Join-Path $main 'C2F2SafeShortcut.java') `
        (Join-Path $main 'C2WaypointExecution.java') `
        (Join-Path $tests 'C2WaypointExecutionSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'P1-C waypoint protocol javac failed' }
    & java -cp $out 'io.github.sirp00m.npcortex.C2WaypointExecutionSelfTest'
    if ($LASTEXITCODE -ne 0) { throw 'P1-C waypoint protocol assertions failed' }
    & (Join-Path $PSScriptRoot 'test-c2-p1b3.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'P1-B3 / B2 / B1 / P1-A / C1 / Backend regression failed' }
    Write-Host 'C2 P1-C Waypoint Protocol + B3/B2/B1/P1-A/C1/Backend isolated regressions PASS. NEXT: Forge Build and Minecraft Gameplay on Windows.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
