# G3-1: contract-only dry-run. Independent from Forge build/runClient/backend.
param([switch]$IncludeG2Regression, [switch]$BackendOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previous=$env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH=(Resolve-Path '.\backend\src').Path
        & python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_cplus_e3_g3_terminal.py -v
        if($LASTEXITCODE -ne 0){throw 'G3-1 Python contract tests FAILED'}
        if($IncludeG2Regression) {
            & (Join-Path $PSScriptRoot 'test-cplus-e3-g2-fix1.ps1') -BackendOnly
            if($LASTEXITCODE -ne 0){throw 'G2 regression FAILED'}
        }
    } finally { Pop-Location }
} finally {$env:PYTHONPATH=$previous}
if(-not $BackendOnly) {
    Push-Location (Join-Path $root 'forge-mod')
    try {
        & .\gradlew.bat verifyG3TerminalContract
        if($LASTEXITCODE -ne 0){throw 'G3-1 Java/Gson fixture test FAILED'}
    } finally { Pop-Location }
}
Write-Host 'G3-1 contract-only focused tests PASS. This does NOT complete G3-2/G3-3.'
