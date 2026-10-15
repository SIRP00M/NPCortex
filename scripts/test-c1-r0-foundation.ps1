# R0 isolated ownership-policy test. Does NOT compile Minecraft/Forge classes.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C1VanillaLifecyclePolicy.java'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/C1VanillaLifecyclePolicySelfTest.java'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') { throw "JDK 17 required: $version" }
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_r0_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out $src $test
    if ($LASTEXITCODE -ne 0) { throw 'R0 policy compile failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1VanillaLifecyclePolicySelfTest
    if ($LASTEXITCODE -ne 0) { throw 'R0 policy test failed' }

    # Compile real coordinator against small isolated stand-ins only. This
    # catches ownership/wiring/order regressions, NOT Minecraft API compatibility.
    $fakeRoot = Join-Path $root 'forge-mod/src/test-isolated/r0-stubs/java'
    $coordinator = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C1VanillaLifecycleCoordinator.java'
    $stubs = @(Get-ChildItem -Path $fakeRoot -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -d $out $src $coordinator @stubs
    if ($LASTEXITCODE -ne 0) { throw 'R0 coordinator mock compile failed' }
    & java -cp $out io.github.sirp00m.npcortex.C1VanillaLifecycleCoordinatorSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'R0 coordinator mock tests failed' }
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
