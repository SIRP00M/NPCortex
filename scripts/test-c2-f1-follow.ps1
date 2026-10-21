# C2-F1 Reliable Follow Player: Java 17 pure contracts + previous S4 regression.
# Does not imply a Forge build or in-game/real MSPT acceptance.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Temurin JDK 17 required; found $version"
}
$out = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_f1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $classes = @('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
                 'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2BPlusCorridor','C2F2SafeShortcut','C2RegionGraph','C2HierarchicalPlanner',
                 'C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy',
                 'C2FollowPolicy','C2FollowPlanner')
    $sources = @($classes | ForEach-Object { Join-Path $main "$_.java" })
    $sources += (Join-Path $test 'C2F1FollowPolicySelfTest.java')
    $sources += (Join-Path $test 'C2F1FollowPlannerSelfTest.java')
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'C2-F1 Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2F1FollowPolicySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C2-F1 follow policy FAILED' }
    & java -cp $out io.github.sirp00m.npcortex.C2F1FollowPlannerSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C2-F1 follow-ring planner FAILED' }
    & (Join-Path $PSScriptRoot 'test-terrain-s4-fix1.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'S4 Fix #1 baseline regression FAILED' }
    Write-Host 'PASS C2-F1 policy/route selection + Sprint4 recovery regression.'
    Write-Host 'PENDING: Windows Forge build, in-game follow, 1/3/5/10 NPC MSPT and multiplayer.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
