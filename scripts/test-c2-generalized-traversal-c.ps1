# C Generalized Block Traversal isolated JVM contracts and Follow/Navigation regressions.
# Does NOT claim Forge, world-collision/modded-block compatibility, or Windows gameplay.
$ErrorActionPreference = 'Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main=Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests=Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version=(& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "Java 17 required: $version" }
$out=Join-Path ([IO.Path]::GetTempPath()) ('npcortex_Ctraversal_'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $classes=@('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
        'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2BPlusCorridor','C2F2SafeShortcut','C2RegionGraph',
        'C2HierarchicalPlanner','C2WaypointExecution','C2ReplanningPolicy',
        'C2S4RecoveryPolicy','C2FollowPolicy','C2FollowPlanner','C2FollowAlternatives',
        'C2F2Locomotion','C1F2AdaptiveSteering','C1F2ContinuousWalk','C1F2MotionTelemetry','C1VanillaMoveControlAdapter')
    $suites=@('C2GeneralizedTraversalCSelfTest','C2NavGridSelfTest',
        'C2TraversabilityGraphSelfTest','C2AStarPlannerSelfTest',
        'C2HierarchicalPlannerSelfTest','C2F1FollowPlannerSelfTest',
        'C2F1FollowPolicySelfTest','C2F2NaturalLocomotionSelfTest',
        'C2F2ContinuousFollowFix1SelfTest','C2F2VanillaInspiredFollowASelfTest',
        'C2F2MotionSmoothingFix2SelfTest','C2F2AdaptiveFollowBSelfTest',
        'C2TerrainGeometrySelfTest')
    $sources=@($classes|ForEach-Object{Join-Path $main "$_.java"})
    $sources+=@($suites|ForEach-Object{Join-Path $tests "$_.java"})
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if($LASTEXITCODE -ne 0){throw 'C isolated javac FAILED'}
    foreach($suite in $suites){
        & java -cp $out "io.github.sirp00m.npcortex.$suite"
        if($LASTEXITCODE -ne 0){throw "C regression FAILED: $suite"}
    }
    Write-Host 'PASS isolated C traversal + prior Planner, Terrain and Follow suites (not Forge).'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
