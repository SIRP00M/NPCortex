# Diagnostic using MOCK world data. This is NOT a Minecraft-world observation.
param(
  [string]$Message = 'Yui, what do you see nearby?',
  [string]$CharacterId = 'yui'
)
$ErrorActionPreference = 'Stop'
$body = @{
  character_id = $CharacterId
  player_id = 'local-test'
  session_id = 'mock-world-test'
  message = $Message
  world = @{
    dimension = 'minecraft:overworld'
    biome = 'minecraft:plains'
    time = 'night'
    weather = 'clear'
    player = @{ distance = 3.2; health = 20; holding = 'minecraft:iron_sword' }
    npc = @{ following = $true; health = 20 }
    blocks = @{ below = 'minecraft:grass_block'; feet = 'minecraft:air'; ahead = 'minecraft:air' }
    nearby = @(@{ kind = 'hostile'; type = 'minecraft:creeper'; distance = 5.2 })
  }
} | ConvertTo-Json -Depth 8
Write-Host 'WARNING: This sends a synthetic example observation to the local AI, not actual game state.' -ForegroundColor Yellow
$result = Invoke-RestMethod -Uri 'http://127.0.0.1:8765/v1/agent' -Method Post `
  -ContentType 'application/json' -Body $body -TimeoutSec 100
Write-Host "Dialogue: $($result.reply)"
Write-Host "Proposed action (NOT executed by this script): $($result.action)"
