param()
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tmp=Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-test5-'+[System.Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $src=Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusTest5Decision.java'
    $test=Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusTest5DecisionSelfTest.java'
    & javac --release 17 -d $tmp $src $test
    if($LASTEXITCODE -ne 0) { throw 'Test5 policy javac failed' }
    & java -cp $tmp io.github.sirp00m.npcortex.CPlusTest5DecisionSelfTest
    if($LASTEXITCODE -ne 0) { throw 'Test5 policy test failed' }
    Write-Host 'Test5 policy PASS. Forge compile/gameplay still separate.'
} finally { Remove-Item -LiteralPath $tmp -Force -Recurse -ErrorAction SilentlyContinue }
