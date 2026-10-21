# Vanilla-inspired Follow Adapter A; pure JVM Java 17-target tests only.
# Actual Forge compilation/gameplay and client visual smoothness are separate gates.
$ErrorActionPreference = 'Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main=Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests=Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version=(& javac -version 2>&1) -join ' '
if($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "Java 17 required, found $version" }
$out=Join-Path ([IO.Path]::GetTempPath()) ('npcortex_followA_'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out | Out-Null
try {
  $classes=@('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
    'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2RegionGraph','C2HierarchicalPlanner',
    'C2BPlusCorridor','C2F2SafeShortcut','C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy',
    'C2FollowPolicy','C2FollowPlanner','C2F2Locomotion',
    'C1F2MotionTelemetry','C1VanillaMoveControlAdapter','C1F2AdaptiveSteering','C1F2ContinuousWalk')
  $src=@($classes | ForEach-Object { Join-Path $main "$_.java" })
  $src += (Join-Path $tests 'C2F2VanillaInspiredFollowASelfTest.java')
  & javac --release 17 -encoding UTF-8 -d $out @src
  if($LASTEXITCODE -ne 0) { throw 'Vanilla Adapter A compile FAILED' }
  & java -cp $out io.github.sirp00m.npcortex.C2F2VanillaInspiredFollowASelfTest
  if($LASTEXITCODE -ne 0) { throw 'Vanilla Adapter A assertions FAILED' }
  & (Join-Path $PSScriptRoot 'test-c2-f2-fix2-smoothing.ps1')
  if($LASTEXITCODE -ne 0) { throw 'Baseline F2 Fix #2 regression FAILED' }
  Write-Host 'PASS Follow A contract and prior F2/S4 regressions. Forge & Gameplay NOT VERIFIED.'
} finally { Remove-Item -LiteralPath $out -Force -Recurse -ErrorAction SilentlyContinue }
