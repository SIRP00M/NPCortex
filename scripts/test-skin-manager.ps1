# Standalone Java checks for PNG validation and path handling (no Forge dependencies).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex\client\SkinFileManager.java'
$test = Join-Path $root 'tests\java\SkinFileManagerTest.java'
$tmp = Join-Path $env:TEMP ('npcortex-skin-test-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $tmp -ItemType Directory -Force | Out-Null
try {
    & javac -encoding UTF-8 -d $tmp $src $test
    if ($LASTEXITCODE -ne 0) { throw 'javac tests failed' }
    & java '-Djava.awt.headless=true' '-cp' $tmp 'SkinFileManagerTest'
    if ($LASTEXITCODE -ne 0) { throw 'SkinFileManager tests failed' }
} finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
