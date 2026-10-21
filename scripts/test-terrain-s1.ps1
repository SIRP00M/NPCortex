# NPCortex Terrain-Aware 3D Traversal - Sprint 1 (Architecture C).
# Unit tests first, then P1-D/C1/C0 full regression. Requires Temurin JDK 17.
# This DOES NOT replace Windows Forge Gradle build or in-game terrain acceptance.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin JDK 17 required. Detected: $version"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_s1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2NavGrid.java') `
        (Join-Path $main 'C2TerrainGeometry.java') `
        (Join-Path $main 'C2TerrainObservation.java') `
        (Join-Path $tests 'C2TerrainGeometrySelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Sprint 1 terrain geometry Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C2TerrainGeometrySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Sprint 1 terrain geometry assertions failed' }
    & (Join-Path $PSScriptRoot 'test-c2-p1d.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'P1-D/C1/Backend regression failed' }
    Write-Host 'Sprint 1 isolated terrain tests + baseline regression PASS'
    Write-Host 'NEXT: .\scripts\dev-forge.ps1 -Task build'
    Write-Host 'NEXT: .\scripts\dev-forge.ps1 -Task runClient; use /npcortex c2 terraininspect'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
