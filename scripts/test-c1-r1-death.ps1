# R1 isolated state-policy tests. Does NOT compile Forge or verify client rendering.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C1DeathPresentationPolicy.java'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/C1DeathPresentationPolicySelfTest.java'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "JDK 17 required: $version" }
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_r1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out $src $test
    if ($LASTEXITCODE -ne 0) { throw 'R1 policy compilation failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1DeathPresentationPolicySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'R1 policy test failed' }
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
