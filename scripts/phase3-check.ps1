# Non-destructive Phase 3 diagnostic. Run from any working directory.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Write-Host '=== NPCortex Phase 3 Diagnostics ==='
Write-Host "Project: $root"
Write-Host '--- Java ---'
if (Get-Command java -ErrorAction SilentlyContinue) {
    & cmd.exe /c 'java -version 2>&1'
} else {
    Write-Warning 'Java not found; install JDK 17.'
}
Write-Host '--- Java Compiler ---'
if (Get-Command javac -ErrorAction SilentlyContinue) {
    & cmd.exe /c 'javac -version 2>&1'
} else {
    Write-Warning 'javac not found; install the full JDK 17.'
}
Write-Host '--- Forge Wrapper ---'
if (Test-Path (Join-Path $root 'forge-mod\gradlew.bat')) {
    Write-Host 'Forge wrapper: FOUND'
} else {
    Write-Host 'Forge wrapper: NOT FOUND (dev-forge.ps1 will bootstrap it)'
}
Write-Host '--- Python Backend ---'
try {
    $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 6
    Write-Host "Backend online: $($health.status) | configured model: $($health.model)"
    Write-Host 'Note: health confirms backend process, not Ollama connectivity.'
} catch {
    Write-Warning 'Backend is offline. Start it using .\scripts\dev-backend.ps1 in a separate terminal.'
}
Write-Host '--- Ollama ---'
try {
    $tags = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 6
    if ($tags.models) {
        Write-Host ('Models: ' + (($tags.models | ForEach-Object { $_.name }) -join ', '))
    } else {
        Write-Warning 'Ollama is running but no models are listed.'
    }
} catch {
    Write-Warning 'Ollama API unreachable. Open Ollama and check port 11434.'
}
