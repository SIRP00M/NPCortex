# C-F1 D+ staged synthetic and compatibility tests. No Minecraft world mutations.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF1DPlusSelfTest.java'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[.\+\-]|$)') {
    throw "Temurin/OpenJDK 17 required on Windows. Found: $version"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_cf1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $sources = @((Get-ChildItem -LiteralPath $main -Filter '*.java' | ForEach-Object { $_.FullName })) + @($test)
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'C-F1 javac failed' }
    & java -cp $out io.github.sirp00m.npcortex.CPlusF1DPlusSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C-F1 Java synthetic tests failed' }
    Push-Location $root
    try {
        $env:PYTHONPATH = (Join-Path $root 'backend/src')
        & python -m unittest discover -s backend/tests -p 'test_cplus_f1_dplus.py' -v
        if ($LASTEXITCODE -ne 0) { throw 'C-F1 backend synthetic tests failed' }
        & python -m unittest discover -s backend/tests -p 'test_cplus_f0_contracts.py' -q
        if ($LASTEXITCODE -ne 0) { throw 'C-F0 contract regressions failed' }
        & python -m unittest discover -s backend/tests -p 'test_c2*.py' -q
        if ($LASTEXITCODE -ne 0) { throw 'C2 backend regressions failed' }
    } finally { Pop-Location }
    Write-Host 'C-F1 D+ synthetic contracts PASS. Forge/Windows gameplay and real crash recovery NOT certified.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
