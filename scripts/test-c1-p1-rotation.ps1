# C1-P1-A Rotation Fix #1: pure Java checks, not a Forge or gameplay test.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaRoot = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$testRoot = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacVersion = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "JDK 17 is required. Found $javacVersion"
}
$dir = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_rotation_' + [Guid]::NewGuid().ToString('N'))
New-Item -Type Directory -Force $dir | Out-Null
try {
    & javac --release 17 -d $dir `
        (Join-Path $javaRoot 'C1RotationState.java') `
        (Join-Path $javaRoot 'C1P0SyncState.java') `
        (Join-Path $testRoot 'C1P1RotationStateSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1 rotation javac failed' }
    & java -cp $dir io.github.sirp00m.npcortex.C1P1RotationStateSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1 rotation self-test failed' }
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}
