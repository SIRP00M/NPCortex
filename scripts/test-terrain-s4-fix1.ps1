# Sprint 4 Fix #1: pure-Java ownership/recovery tests + existing S4 regression.
# Forge build and real Gameplay MUST still run on Windows; no Git operations.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "Temurin 17 required: $version" }
$out = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_s4_fix1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $classes = @('C2NavGrid','C2S3SupportY','C2TerrainGeometry','C2TerrainObservation',
                 'C2TraversalCost','C2TraversabilityGraph','C2AStarPlanner','C2RegionGraph','C2HierarchicalPlanner',
                 'C2BPlusCorridor','C2F2SafeShortcut','C2WaypointExecution','C2ReplanningPolicy','C2S4RecoveryPolicy')
    $sources = @($classes | ForEach-Object { Join-Path $main "$_.java" })
    $sources += (Join-Path $test 'C2S4TransactionalRecoveryFix1SelfTest.java')
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'Sprint4 Fix1 compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2S4TransactionalRecoveryFix1SelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Sprint4 Fix1 safety contract failed' }
    & (Join-Path $PSScriptRoot 'test-terrain-s4.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Existing Sprint4 baseline regression failed' }
    Write-Host 'PASS S4-Fix1 Pure Java and S4 baseline regression'
    Write-Host 'PENDING: Forge build .\scripts\dev-forge.ps1 -Task build; Windows gameplay; MSPT'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
