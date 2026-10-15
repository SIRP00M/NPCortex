# P1-C Java state persistence test doubles only; NOT Forge Build or world/damage simulation.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaRoot = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$isolatedRoot = Join-Path $root 'forge-mod/src/test-isolated/java/io/github/sirp00m/npcortex'
$stubRoot = Join-Path $root 'forge-mod/src/test-stubs'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "JDK 17 is required. Found $version"
}
$dest = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_p1c_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $dest | Out-Null
try {
    $stubs = @(Get-ChildItem -Path $stubRoot -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac --release 17 -d $dest @stubs `
        (Join-Path $javaRoot 'C1LifecyclePhase.java') `
        (Join-Path $javaRoot 'C1LifecycleData.java') `
        (Join-Path $isolatedRoot 'C1LifecycleDataSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1-C lifecycle SavedData test-doubles compilation failed' }
    & java -cp $dest io.github.sirp00m.npcortex.C1LifecycleDataSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1-C lifecycle SavedData test-doubles tests failed' }
} finally {
    Remove-Item -LiteralPath $dest -Force -Recurse -ErrorAction SilentlyContinue
}
