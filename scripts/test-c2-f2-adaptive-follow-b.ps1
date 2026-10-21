# Adaptive Follow B: Java 17 isolated contracts plus prior Follow regressions.
# Does NOT claim actual Forge build, Windows gameplay or render parity.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Temurin Java 17 required for Windows acceptance; found $version"
}
$out = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_followB_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $classes = @('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
        'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2BPlusCorridor','C2F2SafeShortcut','C2RegionGraph','C2HierarchicalPlanner',
        'C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy',
        'C2FollowPolicy','C2FollowPlanner','C2FollowAlternatives','C2F2Locomotion',
        'C1F2AdaptiveSteering','C1F2ContinuousWalk','C1F2MotionTelemetry','C1VanillaMoveControlAdapter')
    $suites = @('C2F1FollowPlannerSelfTest','C2F1FollowPolicySelfTest',
        'C2F2NaturalLocomotionSelfTest','C2F2ContinuousFollowFix1SelfTest',
        'C2F2VanillaInspiredFollowASelfTest','C2F2MotionSmoothingFix2SelfTest',
        'C2F2AdaptiveFollowBSelfTest')
    $sources = @($classes | ForEach-Object { Join-Path $main "$_.java" })
    $sources += @($suites | ForEach-Object { Join-Path $tests "$_.java" })
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'Follow B isolated javac FAILED' }
    foreach ($suite in $suites) {
        & java -cp $out "io.github.sirp00m.npcortex.$suite"
        if ($LASTEXITCODE -ne 0) { throw "Regression failed: $suite" }
    }
    Write-Host 'PASS: Adaptive Follow B + six prior Follow suites (pure Java only).'
    Write-Host 'Run scripts/test-terrain-s4-fix1.ps1 separately for Terrain S4 regressions.'
    Write-Host 'PENDING: full Forge Gradle compile and Windows in-game/1-3-5-10 NPC acceptance.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
