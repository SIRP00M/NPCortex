param([switch]$BackendOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$old=$env:PYTHONPATH
try {
    $env:PYTHONPATH=Join-Path $root 'backend/src'
    Push-Location $root
    try {
        & python -m unittest discover -s backend/tests -v
        if($LASTEXITCODE -ne 0){throw 'Backend suite failed'}
    } finally {Pop-Location}
} finally {$env:PYTHONPATH=$old}
if(-not $BackendOnly){
    Push-Location (Join-Path $root 'forge-mod')
    try {
        & .\gradlew.bat build verifyTransportE verifyTransportOutbox verifyG2Identity verifyG3TerminalContract
        if($LASTEXITCODE -ne 0){throw 'Forge/transport contract gate failed'}
    } finally {Pop-Location}
}
Write-Host 'Automated gates finished. Windows gameplay/fault injection remains separate.'
