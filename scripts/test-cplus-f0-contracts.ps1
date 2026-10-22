# Pure Java 17 + Python C-F0 contract tests. No Minecraft world modifications.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF0ContractsSelfTest.java'
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[.\+\-]|$)') {
    throw "JDK 17 required on Windows. Found: $version"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_cf0_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $sources = @((Get-ChildItem -LiteralPath $main -Filter '*.java' | ForEach-Object { $_.FullName })) + @($test)
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) { throw 'C-F0 javac contract compile failed' }
    & java -cp $out io.github.sirp00m.npcortex.CPlusF0ContractsSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C-F0 Java contract assertions failed' }
    $env:PYTHONPATH = (Join-Path $root 'backend/src')
    Push-Location $root
    try {
        & python -m unittest discover -s backend/tests -p 'test_cplus_f0_contracts.py' -v
        if ($LASTEXITCODE -ne 0) { throw 'C-F0 backend contract validation failed' }
        & python -m unittest discover -s backend/tests -p 'test_c2*.py' -q
        if ($LASTEXITCODE -ne 0) { throw 'Existing C2 backend regression failed' }
    } finally { Pop-Location }
    Write-Host 'C-F0 contract PASS. NOTE: Forge Build and Windows gameplay remain PENDING.'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
