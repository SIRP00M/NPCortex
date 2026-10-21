# Terrain-Aware 3D Traversal Sprint 2 — Verified Vanilla 3D Motion Primitives.
# Java 17-only contract and baseline regression; NOT a Forge build/physics proof.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_s2_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C1S2TraversalPlan.java') `
        (Join-Path $tests 'C1S2TraversalPlanSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'S2 pure Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1S2TraversalPlanSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'S2 contract assertions failed' }
    & (Join-Path $PSScriptRoot 'test-terrain-s1.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'S1/C2/C1/Backend regression failed' }
    Write-Host 'PASS S2 contract + S1 and C1/C2/Backend regressions'
    Write-Host 'NEXT: .\scripts\dev-forge.ps1 -Task build'
    Write-Host 'NEXT: .\scripts\dev-forge.ps1 -Task runClient'
    Write-Host 'TEST: /npcortex c1 traverse yui jump_up forward 1.0'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
