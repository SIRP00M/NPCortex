# NPCortex B3 headless acceptance, REAL Ollama/Qwen through backend. No cleanup/deletes.
# Default: 4 budgeted social steps. Can pause after 2 to verify restart-and-resume.
param(
    [string]$BaseUrl = 'http://127.0.0.1:8765',
    [ValidateRange(2,24)][int]$MaxSteps = 4,
    [ValidateRange(0,24)][int]$PauseAfter = 0,
    [string]$ResumeRunId = '',
    [string]$VerifyRunId = ''
)
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
function Post-Npc([string]$Path, [hashtable]$Data) {
    Invoke-RestMethod -Uri ($BaseUrl + $Path) -Method Post -ContentType 'application/json' `
      -Body ($Data | ConvertTo-Json -Depth 16 -Compress)
}
function Assert-Npc([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "B3 FAIL: $Message" }
    Write-Host "PASS: $Message" -ForegroundColor Green
}
function Get-Npc([string]$Path) {
    Invoke-RestMethod -Uri ($BaseUrl + $Path)
}
function Show-Run([string]$RunId) {
    $run = Get-Npc ('/v1/social-runs/' + $RunId)
    $dialogue = Get-Npc ('/v1/dialogues/' + $run.dialogue_id)
    Assert-Npc ($run.steps_completed -eq $dialogue.turns.Count) 'Journal and transcript turn counts match'
    Assert-Npc ($dialogue.turns.Count -le $run.max_steps) 'Never exceeds maximum step budget'
    $ids = @($dialogue.participants | Where-Object { $_.speaker_type -eq 'actor' } | ForEach-Object { $_.speaker_id })
    $last = ''
    foreach ($turn in $dialogue.turns) {
        Assert-Npc ($turn.speaker_type -eq 'actor' -and $ids -contains $turn.speaker_id) "Turn $($turn.seq) uses a registered NPC"
        Assert-Npc ($turn.speaker_id -ne $last) "Turn $($turn.seq) is not a repeated speaker"
        $last = $turn.speaker_id
    }
    Write-Host "`nRUN: $($run.run_id)  DIALOGUE: $($run.dialogue_id)"
    Write-Host "STATUS: $($run.status)  COMPLETED: $($run.steps_completed) / $($run.max_steps)"
    $dialogue.turns | Format-Table seq, speaker_type, speaker_id, content -Wrap
    return $run
}
$health = Get-Npc '/health'
Assert-Npc ($health.status -eq 'ok') 'Backend online (not a Qwen readiness check)'
if ($VerifyRunId) {
    $saved = Show-Run $VerifyRunId
    Assert-Npc ($saved.status -in @('completed','stopped')) 'Completed/stopped run persists after backend restart'
    Write-Host "PASS: persisted B3 social-run journal and dialogue survive restart" -ForegroundColor Green
    exit 0
}
if ($ResumeRunId) {
    $run = Get-Npc ('/v1/social-runs/' + $ResumeRunId)
    Assert-Npc ($run.status -eq 'active') 'Social run is active after backend restart'
    Assert-Npc ($run.steps_completed -ge 1) 'Previous turns survived backend restart'
    Write-Host "Resuming existing social run $($run.run_id)" -ForegroundColor Cyan
} else {
    $world = Post-Npc '/v1/worlds' @{name='B3 Headless Acceptance World'}
    $yui1 = Post-Npc '/v1/actors' @{world_id=$world.world_id;dimension_id='minecraft:overworld';character_id='yui'}
    $guide = Post-Npc '/v1/actors' @{world_id=$world.world_id;dimension_id='minecraft:overworld';character_id='guide'}
    $yui2 = Post-Npc '/v1/actors' @{world_id=$world.world_id;dimension_id='minecraft:overworld';character_id='yui'}
    Assert-Npc ($yui1.actor_id -ne $yui2.actor_id) 'Two Yui instances have different UUIDs'
    $dialogue = Post-Npc '/v1/dialogues' @{
      world_id=$world.world_id;dimension_id='minecraft:overworld';
      actor_ids=@($yui1.actor_id,$guide.actor_id,$yui2.actor_id);
      player_ids=@();topic='NPC friends discuss their favorite Minecraft builds';max_turns=$MaxSteps
    }
    $run = Post-Npc '/v1/social-runs' @{dialogue_id=$dialogue.dialogue_id;max_steps=$MaxSteps}
    Assert-Npc ($run.status -eq 'active') 'Social run created and ready'
    Write-Host "`nWORLD: $($world.world_id)" -ForegroundColor Cyan
}
$iteration = 0
while ($run.status -eq 'active') {
    if ($PauseAfter -gt 0 -and $iteration -ge $PauseAfter) { break }
    $iteration++
    $req = [guid]::NewGuid().ToString()
    Write-Host "`nCalling Qwen B3 step $iteration with request_id $req ..." -ForegroundColor Cyan
    $response = Post-Npc ('/v1/social-runs/' + $run.run_id + '/step') @{request_id=$req}
    $replay = Post-Npc ('/v1/social-runs/' + $run.run_id + '/step') @{request_id=$req}
    Assert-Npc ($replay.replayed -eq $true) 'Duplicate step request is replayed without new AI turn'
    Assert-Npc ($replay.step.action -eq $response.step.action) 'Duplicate step preserves model decision'
    if ($response.step.action -eq 'speak') {
        Write-Host "Qwen chose $($response.turn.speaker_id): $($response.turn.content)"
    } else {
        Write-Host "Qwen chose STOP after $iteration step requests"
    }
    $run = Get-Npc ('/v1/social-runs/' + $run.run_id)
}
$saved = Show-Run $run.run_id
if ($saved.status -eq 'active') {
    Write-Host "`nPAUSED. Save the RUN ID: $($saved.run_id)" -ForegroundColor Yellow
    Write-Host 'Restart Backend; do NOT delete SQLite; then run:'
    Write-Host ".\scripts\test-b3-social.ps1 -ResumeRunId '$($saved.run_id)'"
} else {
    Assert-Npc ($saved.steps_completed -ge 2) 'At least two NPC speech turns persisted'
    Assert-Npc ($saved.status -in @('completed','stopped')) 'Run reached a bounded terminal state'
    Write-Host "`nPASS: B3 headless social simulation complete." -ForegroundColor Green
    Write-Host 'After restarting Backend, run:'
    Write-Host ".\scripts\test-b3-social.ps1 -VerifyRunId '$($saved.run_id)'"
}
