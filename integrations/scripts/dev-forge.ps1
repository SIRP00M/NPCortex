# NPCortex Phase 3 — development launcher for Minecraft Forge 1.20.1.
# Uses JDK 17 and the official Forge Gradle wrapper.
param(
    [ValidateSet('runClient', 'build', 'genIntellijRuns', 'genVSCodeRuns', 'runServer')]
    [string]$Task = 'runClient'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$forge = Join-Path $root 'forge-mod'

# Verify JDK 17 before Gradle downloads hundreds of megabytes.
if (-not (Get-Command 'java.exe' -ErrorAction SilentlyContinue)) {
    throw 'Java is not on PATH. Install the JDK 17, then open a fresh PowerShell.'
}
if (-not (Get-Command 'javac.exe' -ErrorAction SilentlyContinue)) {
    throw 'javac not found. Install a full JDK 17 (not just a JRE).'
}
$javaVersionLines = & cmd.exe /c 'java -version 2>&1'
$javaVersionText = $javaVersionLines -join ' '
if ($javaVersionText -notmatch 'version "17(?:[\.\+\-]|\")') {
    throw "NPCortex Forge 1.20.1 requires JDK 17. Detected: $javaVersionText. Set JAVA_HOME and PATH to a JDK 17."
}
$javacText = (& cmd.exe /c 'javac -version 2>&1') -join ' '
if ($javacText -notmatch 'javac 17(?:[\.\+\-]|$)') {
    throw "javac must be version 17. Found: $javacText"
}
Write-Host "Java check passed: $javaVersionText"

$wrapper = Join-Path $forge 'gradlew.bat'
if (-not (Test-Path $wrapper)) {
    Write-Host 'Gradle wrapper not found; downloading verified official Forge MDK wrapper...'
    & (Join-Path $PSScriptRoot 'setup-forge-wrapper.ps1')
}

Push-Location $forge
try {
    Write-Host "Running Gradle task: $Task"
    & .\gradlew.bat $Task
    if ($LASTEXITCODE -ne 0) { throw "Gradle $Task failed (exit code $LASTEXITCODE)." }
    Write-Host "Gradle $Task completed successfully."
} finally {
    Pop-Location
}
