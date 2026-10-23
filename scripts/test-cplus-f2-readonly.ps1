# C-F2 P1-P5 read-only contract regression. Does not open Minecraft or modify worlds.
param([switch]$IncludeForgeBuild, [switch]$IncludeFullBackend)
$ErrorActionPreference = 'Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$prior=$env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH=(Resolve-Path '.\backend\src').Path
        & python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_cplus_f2_observation.py -q
        if ($LASTEXITCODE -ne 0) {throw 'C-F2 backend contract tests failed'}
        $src='forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus/CPlusF2ReadOnlyPolicy.java'
        $test='forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF2ReadOnlyPolicySelfTest.java'
        $out=Join-Path $env:TEMP ('npcortex-cf2-test-'+[guid]::NewGuid().ToString('N'))
        New-Item -Type Directory -Path $out | Out-Null
        try {
            & javac --release 17 -d $out $src $test
            if ($LASTEXITCODE -ne 0) {throw 'C-F2 Java compile failed'}
            & java -cp $out io.github.sirp00m.npcortex.CPlusF2ReadOnlyPolicySelfTest
            if ($LASTEXITCODE -ne 0) {throw 'C-F2 Java contract tests failed'}
        } finally {Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue}
        if ($IncludeFullBackend) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -q
            if ($LASTEXITCODE -ne 0) {throw 'Full Backend suite failed'}
        }
        if ($IncludeForgeBuild) {
            & (Join-Path $PSScriptRoot 'dev-forge.ps1') -Task build
            if ($LASTEXITCODE -ne 0) {throw 'Forge build failed'}
        }
        Write-Host 'PASS C-F2 isolated read-only contract checks (Forge gameplay remains separate).'
    } finally {Pop-Location}
} finally {$env:PYTHONPATH=$prior}
