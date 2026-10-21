# F2 Fix #2: Java 17 telemetry/lookahead/owned extension contracts + baseline.
# Forge and visual smoothness still require real Windows in-game acceptance.
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main=Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$test=Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version=(& javac -version 2>&1) -join ' '
if($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') {throw "Temurin 17 required; found $version"}
$out=Join-Path ([IO.Path]::GetTempPath()) ('npcortex_f2fix2_'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
  $classes=@('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
    'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2BPlusCorridor','C2F2SafeShortcut','C2RegionGraph','C2HierarchicalPlanner',
    'C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy',
    'C2FollowPolicy','C2FollowPlanner','C2F2Locomotion',
    'C1F2AdaptiveSteering','C1F2ContinuousWalk','C1F2MotionTelemetry','C1VanillaMoveControlAdapter')
  $sources=@($classes | ForEach-Object { Join-Path $main "$_.java" })
  $sources+=(Join-Path $test 'C2F2MotionSmoothingFix2SelfTest.java')
  & javac --release 17 -encoding UTF-8 -d $out @sources
  if($LASTEXITCODE -ne 0) {throw 'F2 Fix #2 compile failed'}
  & java -cp $out io.github.sirp00m.npcortex.C2F2MotionSmoothingFix2SelfTest
  if($LASTEXITCODE -ne 0) {throw 'F2 Fix #2 contract FAIL'}
  & (Join-Path $PSScriptRoot 'test-c2-f2-fix1-continuous.ps1')
  if($LASTEXITCODE -ne 0) {throw 'F2 Fix #1/S4 regression FAIL'}
  Write-Host 'PASS Fix #2 contracts + F2 Fix #1 + Navigation regressions (NOT Forge Gameplay).'
} finally {Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue}
