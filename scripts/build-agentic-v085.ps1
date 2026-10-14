# Actual Forge build must run on Windows with JDK 17 and Forge dependencies.
# -SkipTests skips only standalone Python/Java policy tests, not Gradle compilation.
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'

if (-not $SkipTests) {
    & (Join-Path $PSScriptRoot 'test-agentic-v085.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed; aborting Forge build.' }
} else {
    Write-Host 'Skipping standalone tests by request; Forge compile/build still runs.' -ForegroundColor Yellow
}

& (Join-Path $PSScriptRoot 'dev-forge.ps1') -Task build
if ($LASTEXITCODE -ne 0) { throw 'Forge build failed.' }
Write-Host 'Forge build command returned successfully. Minecraft in-game validation remains separate.'
