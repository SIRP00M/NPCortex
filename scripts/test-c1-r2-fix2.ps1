# C1-R2 Fix #2 isolated movement-ownership / combat-policy tests.
# Requires JDK 17. NOT a Forge compile, physical-world test, or load benchmark.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "JDK 17 required: $version" }
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_r2_fix2_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C1VanillaMotionOwnership.java') `
        (Join-Path $tests 'C1VanillaMotionOwnershipSelfTest.java') `
        (Join-Path $main 'C1PlayerMotionPhase.java') `
        (Join-Path $tests 'C1PlayerMotionPhaseSelfTest.java') `
        (Join-Path $main 'C1CombatTickPolicy.java') `
        (Join-Path $tests 'C1CombatTickPolicySelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'R2 Fix #2 Java compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1VanillaMotionOwnershipSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'R2 Fix #2 ownership test failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1PlayerMotionPhaseSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'R2 Fix #2 movement phase test failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1CombatTickPolicySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'R2 combat regression test failed' }
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
