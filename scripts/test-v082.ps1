# Run all v0.8.2 Backend/API and pure-Java policy tests (NOT Forge or Minecraft).
param([string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'check-java-syntax-v082.ps1') -ProjectRoot $ProjectRoot
if ($LASTEXITCODE -ne 0) { throw 'Forge Java syntax check failed.' }
& (Join-Path $PSScriptRoot 'test-agentic-v085.ps1') -ProjectRoot $ProjectRoot
if ($LASTEXITCODE -ne 0) { throw 'NPCortex v0.8.2 tests failed.' }
Write-Host 'Standalone tests passed (if no prior error). Forge Build / Qwen Live / Minecraft NOT RUN.' -ForegroundColor Yellow
