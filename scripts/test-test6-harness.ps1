# Compatibility entry: historical Test6 source absent at fresh audit.
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location (Join-Path $root 'forge-mod')
try {
    & .\gradlew.bat verifyM03 --console=plain
    if($LASTEXITCODE -ne 0){throw 'Integrated Test6A/fault regression failed'}
} finally {Pop-Location}
Write-Host 'Integrated fault/recovery checks passed. Gameplay 6A and real 6B remain separate gates. See docs/m03-final/README.md.'
