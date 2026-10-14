# Headless B2 smoke test against real local Qwen. Run from repository root.
# Creates new world and actor records, but never deletes an existing world/database.
param([string]$BaseUrl = 'http://127.0.0.1:8765', [string]$VerifyDialogueId = '')
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
function Post-Npc([string]$Path, [hashtable]$Data) {
    Invoke-RestMethod -Uri ($BaseUrl + $Path) -Method Post -ContentType 'application/json' `
      -Body ($Data | ConvertTo-Json -Depth 12 -Compress)
}
function Assert-Npc([bool]$Condition, [string]$Description) {
    if (-not $Condition) { throw "B2 FAILED: $Description" }
    Write-Host "PASS: $Description" -ForegroundColor Green
}
if ($VerifyDialogueId) {
    $saved = Invoke-RestMethod -Uri ($BaseUrl + '/v1/dialogues/' + $VerifyDialogueId)
    Assert-Npc ($saved.turns.Count -ge 1) 'Persisted dialogue transcript exists after restart'
    Write-Host "Dialogue $VerifyDialogueId has $($saved.turns.Count) stored turns; status=$($saved.status)"
    $saved.turns | Format-Table seq, speaker_type, speaker_id, content -Wrap
    exit 0
}
$health = Invoke-RestMethod -Uri ($BaseUrl + '/health')
Assert-Npc ($health.status -eq 'ok') 'Backend online'
$world = Post-Npc '/v1/worlds' @{ name = 'B2 Acceptance Test World' }
$yui1 = Post-Npc '/v1/actors' @{ world_id = $world.world_id; dimension_id = 'minecraft:overworld'; character_id = 'yui' }
$guide = Post-Npc '/v1/actors' @{ world_id = $world.world_id; dimension_id = 'minecraft:overworld'; character_id = 'guide' }
$yui2 = Post-Npc '/v1/actors' @{ world_id = $world.world_id; dimension_id = 'minecraft:overworld'; character_id = 'yui' }
Assert-Npc ($yui1.actor_id -ne $yui2.actor_id) 'Same Character Pack yields distinct actor identities'
$dialogue = Post-Npc '/v1/dialogues' @{
    world_id = $world.world_id; dimension_id = 'minecraft:overworld'
    actor_ids = @($yui1.actor_id, $guide.actor_id, $yui2.actor_id)
    player_ids = @('b2-tester'); topic = 'Meet each other and talk about a Minecraft village'
    max_turns = 4
}
Assert-Npc ($dialogue.participants.Count -eq 4) 'All four participants registered'
$turnPath = '/v1/dialogues/' + $dialogue.dialogue_id + '/turns'
$player = Post-Npc $turnPath @{ speaker_type = 'player'; speaker_id = 'b2-tester'; message = 'Hi everyone! Tell us about yourselves.' }
$requestId = [guid]::NewGuid().ToString()
$turn1 = Post-Npc $turnPath @{ speaker_type = 'actor'; speaker_id = $yui1.actor_id; request_id = $requestId }
$replay = Post-Npc $turnPath @{ speaker_type = 'actor'; speaker_id = $yui1.actor_id; request_id = $requestId }
Assert-Npc ($replay.replayed -eq $true) 'Duplicate request ID replays one persisted turn'
$turn2 = Post-Npc $turnPath @{ speaker_type = 'actor'; speaker_id = $guide.actor_id }
$turn3 = Post-Npc $turnPath @{ speaker_type = 'actor'; speaker_id = $yui2.actor_id }
$history = Invoke-RestMethod -Uri ($BaseUrl + '/v1/dialogues/' + $dialogue.dialogue_id)
Assert-Npc ($history.turns.Count -eq 4) 'Exactly four stored dialogue turns'
Assert-Npc ($history.status -eq 'closed') 'Dialogue closes at max_turns'
Assert-Npc ($history.turns[0].speaker_type -eq 'player') 'Player speaker is explicit'
Assert-Npc ($history.turns[1].speaker_id -eq $yui1.actor_id) 'Yui 1 identity is explicit'
Assert-Npc ($history.turns[2].speaker_id -eq $guide.actor_id) 'Guide identity is explicit'
Assert-Npc ($history.turns[3].speaker_id -eq $yui2.actor_id) 'Yui 2 identity is explicit'
Write-Host "`nWORLD: $($world.world_id)"
Write-Host "DIALOGUE: $($dialogue.dialogue_id)"
Write-Host 'Save DIALOGUE ID for the backend restart test. Do not delete the SQLite DB.'
$history.turns | Format-Table seq, speaker_type, speaker_id, content -Wrap
Write-Host "`nAfter restarting Backend run:"
Write-Host ".\scripts\test-b2-dialogues.ps1 -VerifyDialogueId '$($dialogue.dialogue_id)'"
