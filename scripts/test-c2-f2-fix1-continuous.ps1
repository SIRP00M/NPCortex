# C2-F2 Fix #1 Continuous Follow; requires Java 17, executes baseline F2 tests.
# Forge compilation, client render interpolation, gameplay and MSPT remain Windows gates.
$ErrorActionPreference = 'Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main=Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$test=Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version=(& javac -version 2>&1) -join ' '
if($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') {throw "Temurin JDK 17 required; got $version"}
$out=Join-Path ([IO.Path]::GetTempPath()) ('npcortex_f2fix1_'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
  $classes=@('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
    'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2BPlusCorridor','C2F2SafeShortcut','C2RegionGraph','C2HierarchicalPlanner',
    'C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy',
    'C2FollowPolicy','C2FollowPlanner','C2F2Locomotion','C1F2MotionTelemetry','C1VanillaMoveControlAdapter','C1F2AdaptiveSteering','C1F2ContinuousWalk')
  $sources=@($classes | ForEach-Object { Join-Path $main "$_.java" })
  $sources += (Join-Path $test 'C2F2ContinuousFollowFix1SelfTest.java')
  & javac --release 17 -encoding UTF-8 -d $out @sources
  if($LASTEXITCODE -ne 0) {throw 'Fix #1 pure Java compile failed'}
  & java -cp $out io.github.sirp00m.npcortex.C2F2ContinuousFollowFix1SelfTest
  if($LASTEXITCODE -ne 0) {throw 'Fix #1 pure Java assertions failed'}
  & (Join-Path $PSScriptRoot 'test-c2-f2-natural.ps1')
  if($LASTEXITCODE -ne 0) {throw 'F2 baseline regression failed'}
  Write-Host 'PASS F2 Fix #1 contracts + F2/F1/S4 Java regression. Forge/Gameplay still PENDING.'
} finally {Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue}
