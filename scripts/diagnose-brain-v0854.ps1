# Read-only Qwen smoke check to reproduce the repeated-approach incident.
# Synthetic observation only; this does NOT move any Minecraft NPC.
param([string]$BackendUrl = 'http://127.0.0.1:8765')
$ErrorActionPreference = 'Stop'
$base = $BackendUrl.TrimEnd('/')
$world = @{
    goal_item = 'minecraft:oak_log'; goal_count = 1; collected = 0; mined = 0;
    calls = 1; failures = 0; same_failures = 0; stalls = 1;
    last_result = '[Action unchanged: block already visible and in reach; no movement or task progress.]';
    targets = @(@{ref='b0'; id='minecraft:oak_log'; dx=1; dy=0; dz=0; visible=$true; reachable=$true});
    drops = @(); scaffold = 0; paused = $false;
    equipment = @{holding='minecraft:air'; bag=@(); can_scaffold=$false};
    surroundings = @{below='minecraft:grass_block'; feet='minecraft:air'; ahead='minecraft:air'};
    owner_distance = 2.0;
}
$payload = @{
    character_id = 'yui'; player_id = 'npcortex_local_diagnostic';
    session_id = 'npcortex_brain_noop_probe'; message = 'Yui, chop 1 oak log.';
    world = $world;
} | ConvertTo-Json -Depth 18 -Compress
try {
    $answer = Invoke-RestMethod -Uri "$base/v1/brain/step" -Method POST `
       -ContentType 'application/json' -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) `
       -TimeoutSec 55
    Write-Host "Qwen tool=$($answer.tool) target=$($answer.target) reason=$($answer.reason)"
    if ($answer.tool -eq 'approach' -and $answer.target -eq 'b0') {
        Write-Warning 'Model repeated a known no-op. Java will bound the loop (5 stalls), but Qwen did not recover in this probe.'
        exit 2
    }
    Write-Host 'No identical redundant approach selected in this single synthetic probe.' -ForegroundColor Green
    Write-Host 'Forge build and in-game behavior remain unverified.'
} catch {
    Write-Host "Probe failed: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) { Write-Host $_.ErrorDetails.Message }
    exit 1
}
