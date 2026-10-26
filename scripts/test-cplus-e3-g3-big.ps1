# G3 Combined Sprint: separate from Forge build and runClient.
param([switch]$BackendOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$old=$env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH=(Resolve-Path '.\backend\src').Path
        $tests=@('test_cplus_e3_g3_empty_status.py','test_cplus_e3_g3_lifecycle.py','test_cplus_e3_g3_http.py',
                 'test_cplus_e3_g3_terminal.py','test_cplus_e3_g2_motion.py',
                 'test_cplus_e3_g2_http.py')
        foreach($test in $tests) {
            & python -m unittest discover -s backend/tests -p $test -q
            if($LASTEXITCODE -ne 0){throw "G3 test failed: $test"}
        }
    } finally {Pop-Location}
} finally {$env:PYTHONPATH=$old}
if(-not $BackendOnly) {
    Push-Location (Join-Path $root 'forge-mod')
    try {
        & .\gradlew.bat verifyG3TerminalContract verifyG2Identity
        if($LASTEXITCODE -ne 0){throw 'Java contract self-tests failed'}
    } finally {Pop-Location}
}
Write-Host 'G3 combined focused tests finished. Real gameplay and Forge build are separate Quality Gates.'
