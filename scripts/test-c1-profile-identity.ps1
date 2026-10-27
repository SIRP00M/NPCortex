# G8: C1 identity NBT + Character Profile mode (mock NBT). Isolated test doubles are NOT Forge Build or gameplay.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaRoot = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$testRoot = Join-Path $root 'forge-mod/src/test-isolated/java/io/github/sirp00m/npcortex'
$stubRoot = Join-Path $root 'forge-mod/src/test-stubs'
$javacVersion = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "JDK 17 is required. Found $javacVersion"
}
$dir = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_profile_id_' + [Guid]::NewGuid().ToString('N'))
New-Item -Type Directory -Force $dir | Out-Null
try {
    $stubs = @(Get-ChildItem -Path $stubRoot -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -d $dir @stubs `
        (Join-Path $javaRoot 'C1IdentityData.java') `
        (Join-Path $javaRoot 'C1RotationState.java') `
        (Join-Path $testRoot 'C1IdentityDataSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C1 identity test-doubles javac failed' }
    & java -cp $dir io.github.sirp00m.npcortex.C1IdentityDataSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1 identity self-test failed' }
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}
