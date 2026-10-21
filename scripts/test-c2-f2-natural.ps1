# C2-F2 natural locomotion: Java17 isolated F2 contracts + F1/S4 regression.
# No Minecraft/Forge MSPT or Gameplay claims from this script.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "Temurin 17 required, found $version" }
$out = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_f2_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $classes = @('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
                 'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2TurnAwarePlanner','C2BPlusCorridor','C2F2SafeShortcut','C2RegionGraph','C2HierarchicalPlanner',
                 'C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy',
                 'C2FollowPolicy','C2FollowPlanner','C2F2Locomotion')
    $sources = @($classes | ForEach-Object { Join-Path $main "$_.java" })
    $sources += (Join-Path $test 'C2F2NaturalLocomotionSelfTest.java')
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'C2-F2 Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2F2NaturalLocomotionSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C2-F2 self-test failed' }
    & (Join-Path $PSScriptRoot 'test-c2-f1-follow.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'C2-F1/S4 regression failed' }
    Write-Host 'PASS F2 tests + F1/S4 Java regression. Windows Forge build and gameplay pending.'
} finally { Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue }
