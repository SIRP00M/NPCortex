# Dependency-free P0 sync state checks (NOT a substitute for Forge compile).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceDir = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
$testDir = Join-Path $root 'forge-mod\src\test\java\io\github\sirp00m\npcortex'
$out = Join-Path $env:TEMP ('npcortex-c1p0-sync-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $out -Force | Out-Null
try {
    & javac.exe --release 17 -encoding UTF-8 -d $out `
        (Join-Path $sourceDir 'C1P0SyncState.java') `
        (Join-Path $testDir 'C1P0SyncStateSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C1-P0 state test compilation failed' }
    & java.exe -cp $out io.github.sirp00m.npcortex.C1P0SyncStateSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1-P0 state test failed' }
} finally {
    Remove-Item -Force -Recurse $out -ErrorAction SilentlyContinue
}
