param([string]$VerifyGoalId='')
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8765'
function ApiPost([string]$path, [hashtable]$body) {
    Invoke-RestMethod -Method Post -Uri ($base+$path) -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 12 -Compress)
}
function Check([bool]$condition,[string]$description) {
    if (-not $condition) { throw "FAIL: $description" }
    Write-Host "PASS: $description" -ForegroundColor Green
}
$health=Invoke-RestMethod "$base/health"
Check ($health.status -eq 'ok') 'Backend online (not a Qwen readiness check)'
if ($VerifyGoalId -ne '') {
    $saved=Invoke-RestMethod "$base/v1/agent/goals/$VerifyGoalId"
    Check ($saved.goal_id -eq $VerifyGoalId) 'C0 goal ID persisted after restart'
    Check ($saved.status -eq 'completed') 'C0 verified goal remains completed'
    # Force the pipeline results into arrays before counting: a single match may
    # otherwise be treated as a scalar on different PowerShell versions.
    $actions = @($saved.actions)
    $unverifiedActions = @($actions | Where-Object { $_.status -eq 'unverified' })
    $succeededActions = @($actions | Where-Object { $_.status -eq 'succeeded' })
    if ($actions.Count -ne 2 -or $unverifiedActions.Count -ne 1 -or $succeededActions.Count -ne 1) {
        Write-Host "Action ledger diagnostics: total=$($actions.Count), unverified=$($unverifiedActions.Count), succeeded=$($succeededActions.Count)" -ForegroundColor Yellow
        $actions | Select-Object action_id, skill_id, status, verdict | Format-Table -AutoSize | Out-String | Write-Host
    }
    Check ($actions.Count -eq 2) 'C0 action ledger persists both attempts'
    Check ($unverifiedActions.Count -eq 1) 'Unverified action status persisted'
    Check ($succeededActions.Count -eq 1) 'Verified action status persisted'
    Write-Host "PASS: C0 persistence verification complete. GOAL: $VerifyGoalId" -ForegroundColor Green
    exit 0
}
$world=ApiPost '/v1/worlds' @{name='C0 Mock Forge World'}
$actor=ApiPost '/v1/actors' @{world_id=$world.world_id;dimension_id='minecraft:overworld';character_id='yui'}
$goal=ApiPost '/v1/agent/goals' @{
    world_id=$world.world_id;dimension_id='minecraft:overworld';actor_id=$actor.actor_id
    objective='Break the observed oak log at 1,64,1 and verify it becomes air.'
    success_criteria=@(@{kind='block_is';position=@(1,64,1);block_id='minecraft:air'})
    max_decisions=3
}
Check ($goal.status -eq 'pending') 'Goal persisted as pending'
$snapshot=@{
    world_id=$world.world_id;dimension_id='minecraft:overworld';actor_id=$actor.actor_id
    position=@(0,64,0);inventory=@{'minecraft:oak_log'=0}
    blocks=@{'1,64,1'='minecraft:oak_log'};available_skills=@('break_block')
    nearby_entities=@();nearby_drops=@()
}
$first=ApiPost "/v1/agent/goals/$($goal.goal_id)/observe" @{observation_id=[string][guid]::NewGuid();snapshot=$snapshot}
Check ($first.goal.status -eq 'active') 'First Forge-like snapshot observed; goal active'
for ($step=1; $step -le 2; $step++) {
    $rid=[string][guid]::NewGuid()
    Write-Host "Calling Qwen decision $step (one advertised skill)..." -ForegroundColor Cyan
    $decision=ApiPost "/v1/agent/goals/$($goal.goal_id)/decide" @{request_id=$rid}
    Check ($decision.decision.action -eq 'use_skill' -and $decision.decision.skill_id -eq 'break_block') 'Qwen selected advertised break_block contract'
    Check ($decision.action_id -ne $null -and $decision.action_id -ne '') 'Action request has durable UUID'
    $again=ApiPost "/v1/agent/goals/$($goal.goal_id)/decide" @{request_id=$rid}
    Check ($again.replayed -eq $true -and $again.action_id -eq $decision.action_id) 'Decision idempotently replayed'
    $post=@{}
    foreach ($key in $snapshot.Keys) { $post[$key]=$snapshot[$key] }
    $post['blocks']=@{'1,64,1'='minecraft:oak_log'}
    if ($step -eq 2) { $post['blocks']=@{'1,64,1'='minecraft:air'} }
    # This script is a MOCK SENSOR. We are not breaking real blocks in Minecraft.
    $result=ApiPost "/v1/agent/actions/$($decision.action_id)/result" @{
        result_id=[string][guid]::NewGuid();observation_id=[string][guid]::NewGuid();
        status='succeeded';snapshot=$post
    }
    if ($step -eq 1) {
        Check ($result.action_status -eq 'unverified' -and $result.goal.status -eq 'active') 'False success claim rejected without observed block change'
    } else {
        Check ($result.action_status -eq 'succeeded' -and $result.goal.status -eq 'completed') 'Verified block state completes goal'
    }
}
Write-Host "GOAL: $($goal.goal_id)" -ForegroundColor Yellow
Write-Host 'PASS: C0 Qwen + mock-Forge headless acceptance complete.' -ForegroundColor Green
Write-Host "After restarting the Backend: .\scripts\test-c0-headless.ps1 -VerifyGoalId '$($goal.goal_id)'"
