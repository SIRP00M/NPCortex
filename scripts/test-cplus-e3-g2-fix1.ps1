# E3-D G2 Fix #1: backend HTTP/ACK and real Java/Gson identity self-test.
# Separate from Forge build, Minecraft runClient, and backend server start.
param([switch]$IncludeFullBackend, [switch]$BackendOnly)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previous = $env:PYTHONPATH
try {
    Push-Location $root
    try {
        & (Join-Path $PSScriptRoot 'test-cplus-e3-g2.ps1') -IncludeFullBackend:$IncludeFullBackend
        if ($LASTEXITCODE -ne 0) { throw 'E3 G2 baseline regression failed' }
        $env:PYTHONPATH=(Resolve-Path '.\backend\src').Path
        & python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_cplus_e3_g2_fix1_contract.py -q
        if ($LASTEXITCODE -ne 0) { throw 'E3 G2 Fix #1 backend contract tests failed' }
    } finally { Pop-Location }
} finally { $env:PYTHONPATH=$previous }
if (-not $BackendOnly) {
    Push-Location (Join-Path $root 'forge-mod')
    try {
        & .\gradlew.bat verifyG2Identity
        if ($LASTEXITCODE -ne 0) { throw 'E3 G2 Fix #1 Java identity self-test failed' }
    } finally { Pop-Location }
}
Write-Host 'E3-D G2 Fix #1 focused tests PASS (all enabled suites).'
