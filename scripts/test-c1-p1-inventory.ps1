# C1-P1-B pure Java ledger tests. These do not prove Forge API or Minecraft gameplay.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaRoot = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$testRoot = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacVersion = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "JDK 17 is required. Found $javacVersion"
}
$dir = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_inventory_' + [Guid]::NewGuid().ToString('N'))
New-Item -Type Directory -Force $dir | Out-Null
try {
    & javac --release 17 -d $dir `
        (Join-Path $javaRoot 'C1InventoryJournal.java') `
        (Join-Path $testRoot 'C1InventoryJournalSelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1-B journal javac failed' }
    & java -cp $dir io.github.sirp00m.npcortex.C1InventoryJournalSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1-B journal self-test failed' }
} finally {
    Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
}
