$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$env:PYTHONPATH = Join-Path $repoRoot 'backend\src'
Write-Host 'Starting local NPCortex backend. Make sure Ollama is running.'
python -m npcortex_backend
