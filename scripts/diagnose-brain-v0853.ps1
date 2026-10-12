# Read-only local smoke test: asks Qwen to choose one tool from a synthetic world.
# It does NOT call Forge, move NPCs or edit blocks. Run with backend and Ollama online.
param([string]$BackendUrl = 'http://127.0.0.1:8765')
$ErrorActionPreference = 'Stop'
$base = $BackendUrl.TrimEnd('/')
try {
    $health = Invoke-RestMethod -Uri "$base/health" -TimeoutSec 5
    Write-Host "Backend OK; selected model: $($health.model)" -ForegroundColor Green
    $world = @{
        goal_item = 'minecraft:oak_log'; goal_count = 1; collected = 0; mined = 0;
        calls = 0; failures = 0; same_failures = 0; stalls = 0;
        last_result = '[Task accepted by server; no tool executed yet.]';
        targets = @(@{ref='b0'; id='minecraft:oak_log'; dx=2; dy=0; dz=0; visible=$true; reachable=$true});
        drops = @(); scaffold = 0; paused = $false;
        equipment = @{holding='minecraft:air'; bag=@(); can_scaffold=$false};
        surroundings = @{below='minecraft:grass_block'; feet='minecraft:air'; ahead='minecraft:air'};
        owner_distance = 2.0;
    }
    $body = @{
        character_id = 'yui'; player_id = 'npcortex_local_diagnostic';
        session_id = 'npcortex_brain_smoke';
        message = 'Yui, chop 1 oak log.'; world = $world;
    } | ConvertTo-Json -Depth 16 -Compress
    $result = Invoke-RestMethod -Uri "$base/v1/brain/step" -Method Post `
        -ContentType 'application/json' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 55
    Write-Host "Qwen decision accepted: tool=$($result.tool) target=$($result.target)" -ForegroundColor Green
    Write-Host 'This proves the Backend API decision path works for synthetic data; NOT actual Minecraft gameplay.'
} catch {
    Write-Host "Diagnostic request FAILED: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) { Write-Host "Backend response: $($_.ErrorDetails.Message)" }
    Write-Host 'Copy the Python backend console lines containing Brain request rejected, Brain decision rejected or Ollama request failed.'
    exit 1
}
