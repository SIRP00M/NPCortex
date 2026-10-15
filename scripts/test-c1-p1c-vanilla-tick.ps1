# C1-P1-C Fix #1 isolated tick-policy tests; not a Forge compilation.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C1P1VanillaTickPolicy.java'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/C1P1VanillaTickPolicySelfTest.java'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "JDK 17 required: $version" }
$dest = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_tick_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $dest | Out-Null
try {
    & javac --release 17 -d $dest $source $test
    if ($LASTEXITCODE -ne 0) { throw 'Vanilla tick policy compile FAILED' }
    & java -cp $dest io.github.sirp00m.npcortex.C1P1VanillaTickPolicySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Vanilla tick policy assertions FAILED' }
} finally {
    Remove-Item -LiteralPath $dest -Recurse -Force -ErrorAction SilentlyContinue
}
