# NPCortex Phase 4 preflight. Does not compile the Minecraft mod.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Write-Host '=== NPCortex Phase 4 preflight ==='
Write-Host "Root: $root"
$paths = @(
 'forge-mod/src/main/java/io/github/sirp00m/npcortex/entity/CompanionEntity.java',
 'forge-mod/src/main/java/io/github/sirp00m/npcortex/entity/FollowOwnerGoal.java',
 'forge-mod/src/main/java/io/github/sirp00m/npcortex/client/DialogueScreen.java',
 'forge-mod/src/main/java/io/github/sirp00m/npcortex/network/NpcNetwork.java'
)
foreach ($path in $paths) {
 if (-not (Test-Path (Join-Path $root $path))) { throw "Missing Phase 4 file: $path" }
}
Write-Host 'Phase 4 source files: OK'
& java -version
& javac -version
Write-Host '--- Python Backend ---'
try {
 $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/health' -TimeoutSec 4
 Write-Host "Online: $($health.status) | Model: $($health.model)"
} catch { Write-Warning 'Backend not reachable; start .\scripts\dev-backend.ps1 in another terminal.' }
Write-Host '--- Ollama ---'
if (Get-Command ollama -ErrorAction SilentlyContinue) { & ollama list } else { Write-Warning 'ollama not found on PATH.' }
Write-Host 'Next: .\scripts\dev-forge.ps1 -Task build'
