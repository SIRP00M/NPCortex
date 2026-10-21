# Terrain-Aware 3D Navigation Sprint 3 (Architecture C): Java 17 contract,
# typed directed 3D B2/B3/Waypoint, followed by Sprint 2 and historical tests.
# Forge Build and in-game physics are a separate Windows Quality Gate.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$ver = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $ver -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Temurin JDK 17 required; found $ver"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_s3_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $files = @('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
               'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2RegionGraph',
               'C2HierarchicalPlanner','C2BPlusCorridor','C2F2SafeShortcut','C2WaypointExecution') | ForEach-Object { Join-Path $main "$_.java" }
    $files += (Join-Path $tests 'C2S3NavigationSelfTest.java')
    & javac --release 17 -encoding UTF-8 -d $out $files
    if ($LASTEXITCODE -ne 0) { throw 'Sprint 3 pure Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2S3NavigationSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Sprint 3 directed movement contract test failed' }
    & (Join-Path $PSScriptRoot 'test-terrain-s2.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Sprint 2/Sprint 1/C1/C2/Backend regression failed' }
    Write-Host 'PASS S3 pure Java + S2/S1/B3/B2/B1/C1/Cancellation/Backend regressions'
    Write-Host 'NEXT (Windows): .\scripts\dev-forge.ps1 -Task build'
    Write-Host 'NEXT (Windows): start Backend and .\scripts\dev-forge.ps1 -Task runClient'
    Write-Host 'IN GAME: /npcortex c2 navhier yui <x> <footY> <z>'
    Write-Host 'IN GAME: /npcortex c2 navigate yui <x> <footY> <z> ; inspect c2 status'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
