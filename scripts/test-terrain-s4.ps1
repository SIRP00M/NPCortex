# NPCortex Sprint 4 Architecture C: Java 17 S4 policy + historical regression.
# Gameplay, Forge compilation, actual crash consistency and server MSPT still
# require separate Windows/Forge evidence, NOT inferred from this script.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$ver = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $ver -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Temurin 17 is required, got: $ver"
}
$out = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_s4_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $classes = @('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
                 'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2RegionGraph','C2HierarchicalPlanner',
                 'C2BPlusCorridor','C2F2SafeShortcut','C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy')
    $sources = @($classes | ForEach-Object { Join-Path $main "$_.java" })
    $sources += (Join-Path $test 'C2S4RecoverySelfTest.java')
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'S4 Java17 compilation FAILED' }
    & java -cp $out io.github.sirp00m.npcortex.C2S4RecoverySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'S4 safety/ownership contract FAILED' }
    & (Join-Path $PSScriptRoot 'test-terrain-s3.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'S3/S2/S1/C1/C2/Backend regression FAILED' }
    Write-Host 'PASS Sprint 4 pure Java contract + S3/S2/S1 and baseline tests'
    Write-Host 'REQUIRES Windows Forge: .\scripts\dev-forge.ps1 -Task build'
    Write-Host 'REQUIRES Minecraft: 3D blockage/replanning, cancel midair, save/rejoin, MSPT 1/3/5/10'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
