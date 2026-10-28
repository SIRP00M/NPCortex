# TEST-ONLY: exercises scripts/g5-gate.ps1 (the owner gate) on SYNTHETIC fixtures produced from the controller simulation plus small hand-built cases.
# Proves the gate rules (terminal AND quiescent, mid-goal for the checkpoint, never GO for a hanging actor, stale snapshots, checkpoint/done/stop) and that the gate
# agrees with the simulated release moments. It says nothing about NPCortex or Minecraft behaviour.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gate = Join-Path $PSScriptRoot 'g5-gate.ps1'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("g5-gate-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$fails = New-Object System.Collections.ArrayList; $checks = 0
function Check($c, $m) { $script:checks++; if (-not $c) { [void]$script:fails.Add($m) } }
$inv = [Globalization.CultureInfo]::InvariantCulture
function G($fx, [datetime]$now, [double]$hung = 8) {
    $o = & $gate -WorldName NPCortex_G5_fixture -RunDir (Join-Path $fx 'run') -OutDir (Join-Path $fx 'gateout') -Now $now -HungMinutes $hung -Quiet -SkipPreflightCheck
    $code = $LASTEXITCODE
    $d = ($o | Select-Object -Last 1) | ConvertFrom-Json
    $d | Add-Member -NotePropertyName code -NotePropertyValue $code -Force
    return $d
}
function T($s) { return [datetime]::Parse($s, $inv) }

# ---------------------------------------------------------------- 1. gate vs the simulated full runs
foreach ($mode in 'fb', 'ok') {
    $fx = Join-Path $tmp $mode
    & node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $fx $mode | Out-Null
    $meta = Get-Content -Raw (Join-Path $fx 'meta.json') | ConvertFrom-Json
    $gates = @($meta.gates)
    Check ($gates.Count -ge 1) "$mode fixture has gate releases"
    foreach ($g in $gates) {
        $d = G $fx ((T $g.time).AddMilliseconds(-200))
        Check ($d.decision -eq 'GO' -and $d.command -eq "/function npcortex_g5:gate/$($g.id)" -and $d.code -eq 0) "${mode}: the script says GO for $($g.id) no later than the simulated release (got $($d.decision): $($d.reason))"
        $d2 = G $fx ((T $g.time).AddMilliseconds(400))
        Check ($d2.decision -in 'RUNNING', 'CHECKPOINT') "${mode}: after the release of $($g.id) the script no longer says GO (got $($d2.decision))"
    }
    foreach ($b in @($meta.begins | Select-Object -Skip 1)) {
        $d = G $fx ((T $b.time).AddMilliseconds(500))
        Check ($d.decision -eq 'RUNNING' -and $null -eq $d.command) "${mode}: right after BEGIN $($b.id) there is no GO (got $($d.decision))"
    }
    $d = G $fx ((T $meta.checkpoint).AddSeconds(1)); Check ($d.decision -eq 'CHECKPOINT' -and $d.code -eq 15 -and $d.reason -match 'Save and Quit') "${mode}: CHECKPOINT decision tells the owner to Save and Quit, then rejoin"
    $d = G $fx ((T $meta.rejoin).AddSeconds(1)); Check ($d.decision -ne 'CHECKPOINT') "${mode}: after the rejoin the checkpoint decision is gone"
    $d = G $fx ((T $meta.done).AddSeconds(1)); Check ($d.decision -eq 'DONE' -and $d.code -eq 12 -and $d.reason -match 'not a verdict') "${mode}: DONE after SEQUENCE_DONE"
    $glog = Join-Path $fx 'gateout/g5-gate-log.jsonl'
    Check ((Test-Path $glog) -and (@(Get-Content $glog | Where-Object { $_ -match '"decision":"GO"' }).Count -ge $gates.Count)) "${mode}: GO decisions are logged for the evidence ZIP"
    # the first gate: just after AWAIT the actor is not yet proven quiescent for scenarios that were still walking -> never a GO before 3 snapshots / 8 s exist
    $aw = $meta.awaits | Select-Object -First 1
    $d0 = G $fx ((T $aw.time).AddMilliseconds(100)); Check ($d0.decision -in 'GO', 'WAIT') "${mode}: decision right after the first AWAIT is GO or WAIT, never an error"
}
# ---------------------------------------------------------------- 1b. Rev6: p18 needs the OWNER-TYPED taskmovefinalize answer before GO; decisions are written run-scoped
$fx = Join-Path $tmp 'nofin'; & node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $fx 'nofinalize' | Out-Null
$meta = Get-Content -Raw (Join-Path $fx 'meta.json') | ConvertFrom-Json; $g18 = @($meta.gates | Where-Object { $_.id -eq 'p18_regress_e3_taskmove' }) | Select-Object -First 1
Check ($null -ne $g18) 'nofinalize fixture has a p18 gate release'
$d = G $fx ((T $g18.time).AddMilliseconds(-200)); Check ($d.decision -eq 'WAIT' -and $null -eq $d.command -and $d.reason -match 'taskmovefinalize' -and $d.reason -match 'taskmovestatus') "p18 without the typed finalize answer: WAIT and the reason names the typed commands (got $($d.decision): $($d.reason))"
$fx = Join-Path $tmp 'ok'; $meta = Get-Content -Raw (Join-Path $fx 'meta.json') | ConvertFrom-Json; $g18 = @($meta.gates | Where-Object { $_.id -eq 'p18_regress_e3_taskmove' }) | Select-Object -First 1
$d = G $fx ((T $g18.time).AddMilliseconds(-200)); Check ($d.decision -eq 'GO' -and $d.reason -match 'G3 COMPLETED task=SIM-TASK') "p18 with the typed finalize answer in the chat log: GO (SAFE_PROGRESSION only; got $($d.decision): $($d.reason))"
$runF = Join-Path $fx 'g5/run-fixture'
$null = & $gate -WorldName NPCortex_G5_fixture -RunDir (Join-Path $fx 'run') -OutDir (Join-Path $fx 'gateout') -G5RunDir $runF -Now ((T $g18.time).AddMilliseconds(-200)) -Quiet -SkipPreflightCheck
$lastRow = (Get-Content (Join-Path $runF 'gate-log.jsonl') | Select-Object -Last 1) | ConvertFrom-Json
Check ($lastRow.run_id -eq 'fixture' -and $lastRow.decision -eq 'GO' -and $lastRow.meaning -eq 'SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT' -and $lastRow.world -eq 'NPCortex_G5_fixture') "gate decisions carry the run id and are appended to the run folder's own log ($($lastRow | ConvertTo-Json -Compress))"
# ---------------------------------------------------------------- 2. an actor that never ends: never GO, HUNG after the game-time limit, stale snapshots detected
$fx = Join-Path $tmp 'hang'; & node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $fx 'hang' | Out-Null
$meta = Get-Content -Raw (Join-Path $fx 'meta.json') | ConvertFrom-Json; $aw = T (@($meta.awaits)[0].time)
foreach ($sec in 5, 30, 120, 300, 420) {
    $d = G $fx $aw.AddSeconds($sec); Check ($d.decision -eq 'WAIT' -and $null -eq $d.command) "hang: +$sec s after AWAIT the gate says WAIT, never GO (got $($d.decision): $($d.reason))"
}
foreach ($sec in 600, 1200, 3000) {
    $d = G $fx $aw.AddSeconds($sec); Check ($d.decision -eq 'HUNG' -and $d.code -eq 11 -and $null -eq $d.command -and $d.reason -match 'cancel_current') "hang: +$sec s after AWAIT the gate says HUNG and names the safe checkpoint cancel_current (got $($d.decision))"
}
$lastSnap = (Get-ChildItem (Join-Path $fx 'run/saves/NPCortex_G5_fixture/data/npcortex/m03-evidence') -Filter *.json | ForEach-Object { $o = Get-Content -Raw $_.FullName | ConvertFrom-Json; if ($o.captured_utc) { ([datetime]::Parse($o.captured_utc)).ToLocalTime() } } | Sort-Object | Select-Object -Last 1)
$d = G $fx $lastSnap.AddSeconds(60); Check ($d.decision -in 'WAIT', 'HUNG' -and $d.reason -match 'old' -and $d.reason -match 'F3\+P') "hang: stale snapshots (game paused/unfocused) are named, never GO (got $($d.decision): $($d.reason))"
# ---------------------------------------------------------------- 3. hand-built cases for each rule
function Mini($name, $lines, $snaps, $perf = @()) {
    $d = Join-Path $tmp $name; $ev = Join-Path $d 'run/saves/NPCortex_G5_fixture/data/npcortex/m03-evidence'; New-Item -ItemType Directory -Force -Path (Join-Path $d 'run/logs'), $ev | Out-Null
    $out = @(); foreach ($l in $lines) { $out += ("[{0}] [Server thread/INFO] [x.Y/]: {1}" -f $l[0].ToString('ddMMMyyyy HH:mm:ss.fff', $inv), $l[1]) }
    [IO.File]::WriteAllLines((Join-Path $d 'run/logs/latest.log'), $out)
    $i = 0; foreach ($s in $snaps) {
        $j = [ordered]@{ name = $s.name; x = $s.x; z = $s.z; capture_tick = [int]($s.at * 20); captured_utc = $s.t.ToUniversalTime().ToString('o'); profile_id = 'p' }
        if ($s.state) { $j['c2_journal'] = @{ goal_id = $s.goal; action_id = 'a-' + $s.goal; state = $s.state } }
        [IO.File]::WriteAllText((Join-Path $ev ('s{0:d4}.json' -f ($i++))), ($j | ConvertTo-Json -Depth 4))
    }
    foreach ($p in $perf) { $f = Join-Path $ev $p.file; [IO.File]::WriteAllText($f, ($p.obj | ConvertTo-Json)); (Get-Item $f).LastWriteTime = $p.mtime }
    return $d
}
$base = (Get-Date).AddHours(-2); $G1 = '11111111-1111-1111-1111-111111111111'; $G2 = '22222222-2222-2222-2222-222222222222'
function Std($id, $extra = @()) { return @(@($base, '[Not Secure] [Server] [G5] START ALL'), @($base.AddSeconds(1), "[Not Secure] [Server] [G5] BEGIN $id title"), @($base.AddSeconds(40), "[Not Secure] [Server] [G5] AWAIT $id owner gate required")) + $extra }
function Sn([double]$at, [double]$x, [double]$z, $state, $goal = $G1, $name = 'yui') { return [pscustomobject]@{ at = $at; t = $base.AddSeconds($at); name = $name; x = $x; z = $z; state = $state; goal = $goal } }
$c2done = ,@($base.AddSeconds(20), "NPCortex C2 goal=$G1 actor=a result=COMPLETED detail=ok")
$nowAt = { param($sec) $base.AddSeconds($sec) }
# nav: terminal + logged line + stationary -> GO
$m = Mini 'nav_go' ((Std 'p02_navlong_20') + $c2done) @((Sn 5 2 2 'MOVING'), (Sn 20 22.5 2.5 'MOVING'), (Sn 30 22.5 2.5 'COMPLETED'), (Sn 35 22.5 2.5 'COMPLETED'), (Sn 40 22.5 2.5 'COMPLETED'), (Sn 45 22.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'GO' -and $d.command -eq '/function npcortex_g5:gate/p02_navlong_20') "nav terminal + logged line + stationary -> GO (got $($d.decision): $($d.reason))"
# nav: journal still MOVING
$m = Mini 'nav_moving' (Std 'p02_navlong_20') @((Sn 30 10 2 'MOVING'), (Sn 35 12 2 'MOVING'), (Sn 40 14 2 'MOVING'), (Sn 45 16 2 'MOVING'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT' -and $d.reason -match 'not terminal') "nav: goal still MOVING -> WAIT (got $($d.decision): $($d.reason))"
# nav: terminal in the journal but no logged line (COMPLETED) -> WAIT
$m = Mini 'nav_noline' (Std 'p02_navlong_20') @((Sn 30 22.5 2.5 'COMPLETED'), (Sn 35 22.5 2.5 'COMPLETED'), (Sn 40 22.5 2.5 'COMPLETED'), (Sn 45 22.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT' -and $d.reason -match 'no C2 terminal line') "nav: COMPLETED without a logged terminal line -> WAIT (got $($d.decision): $($d.reason))"
# nav: CANCELLED needs no result= line (production logs none for cancel) -> GO when stationary
$m = Mini 'nav_cancelled' (Std 'p09_cancel_leg_active') @((Sn 30 30 2 'CANCELLED'), (Sn 35 30 2 'CANCELLED'), (Sn 40 30 2 'CANCELLED'), (Sn 45 30 2 'CANCELLED'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'GO') "nav: CANCELLED + stationary needs no result line -> GO (got $($d.decision): $($d.reason))"
# nav: CANCEL_PENDING is still active (fenced until the terminal ACK)
$m = Mini 'nav_cancelpending' (Std 'p09_cancel_leg_active') @((Sn 30 30 2 'CANCEL_PENDING'), (Sn 35 30 2 'CANCEL_PENDING'), (Sn 40 30 2 'CANCEL_PENDING'), (Sn 45 30 2 'CANCEL_PENDING'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT') "nav: CANCEL_PENDING is not terminal -> WAIT (got $($d.decision))"
# nav: terminal but the NPC is still moving
$m = Mini 'nav_drift' ((Std 'p02_navlong_20') + $c2done) @((Sn 30 22.5 2.5 'COMPLETED'), (Sn 35 23.5 2.5 'COMPLETED'), (Sn 40 24.5 2.5 'COMPLETED'), (Sn 45 25.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT' -and $d.reason -match 'still moving') "nav: terminal state but the position still changes -> WAIT (got $($d.decision): $($d.reason))"
# nav: only two snapshots so far
$m = Mini 'nav_two' ((Std 'p02_navlong_20') + $c2done) @((Sn 40 22.5 2.5 'COMPLETED'), (Sn 45 22.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT' -and $d.reason -match 'snapshot') "nav: fewer than 3 snapshots -> WAIT (got $($d.decision): $($d.reason))"
# nav: duplicate terminal line is still releasable but warned (the collector FAILs exactly-once)
$m = Mini 'nav_dup' ((Std 'p02_navlong_20') + $c2done + @(,@($base.AddSeconds(21), "NPCortex C2 goal=$G1 actor=a result=COMPLETED detail=again"))) @((Sn 30 22.5 2.5 'COMPLETED'), (Sn 35 22.5 2.5 'COMPLETED'), (Sn 40 22.5 2.5 'COMPLETED'), (Sn 45 22.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'GO' -and $d.reason -match 'WARNING') "nav: duplicate terminal lines -> GO with a WARNING (got $($d.decision): $($d.reason))"
# a previous scenario's goal must not count as this scenario's goal
$m = Mini 'nav_prevgoal' ((Std 'p03_navlong_40')) @((Sn -30 5 2 'COMPLETED' $G2), (Sn 30 -37.5 2.5 'MOVING' $G1), (Sn 35 -30 2.5 'MOVING' $G1), (Sn 40 -25 2.5 'MOVING' $G1), (Sn 45 -20 2.5 'MOVING' $G1))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT' -and $d.reason -match $G1.Substring(0, 8)) "nav: ownership - the new goal is judged, the earlier goal's COMPLETED is ignored (got $($d.decision): $($d.reason))"
# no goal evidenced at all but quiescent: releasable (collector reports PENDING)
$m = Mini 'nav_nogoal' (Std 'p02_navlong_20') @((Sn 30 2 2 $null $null), (Sn 35 2 2 $null $null), (Sn 40 2 2 $null $null), (Sn 45 2 2 $null $null))
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'GO' -and $d.reason -match 'no goal was evidenced') "nav: nothing dispatched and quiescent -> GO (got $($d.decision): $($d.reason))"
# p11: mid-goal -> GO; goal already finished -> MISSED (do not Save&Quit)
$std11 = @(@($base, '[Not Secure] [Server] [G5] START ALL'), @($base.AddSeconds(1), '[Not Secure] [Server] [G5] BEGIN p11_savequit_checkpoint t'), @($base.AddSeconds(15), '[Not Secure] [Server] [G5] AWAIT p11_savequit_checkpoint owner gate required'))
$m = Mini 'p11_mid' $std11 @((Sn 5 2 2 'DECIDED'), (Sn 12 20 2 'MOVING'), (Sn 17 40 2 'MOVING'))
$d = G $m (& $nowAt 18); Check ($d.decision -eq 'GO' -and $d.command -eq '/function npcortex_g5:gate/p11_savequit_checkpoint') "p11: NPC mid-goal -> GO (got $($d.decision): $($d.reason))"
$m = Mini 'p11_missed' $std11 @((Sn 5 2 2 'DECIDED'), (Sn 12 90 2 'COMPLETED'), (Sn 17 152.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 18); Check ($d.decision -eq 'MISSED' -and $d.command -eq $null -and $d.reason -match 'only/p11') "p11: goal already finished -> MISSED, no checkpoint (got $($d.decision): $($d.reason))"
# p12: any movement after the rejoin keeps waiting
$std12 = @(@($base, '[Not Secure] [Server] [G5] START ALL'), @($base.AddSeconds(1), '[Not Secure] [Server] [G5] BEGIN p12_rejoin_no_replay t'), @($base.AddSeconds(30), '[Not Secure] [Server] [G5] AWAIT p12_rejoin_no_replay owner gate required'))
$m = Mini 'p12_moves' $std12 @((Sn 5 10 2 'CANCEL_PENDING'), (Sn 12 11 2 'CANCEL_PENDING'), (Sn 20 12 2 'CANCEL_PENDING'), (Sn 28 13 2 'CANCEL_PENDING'))
$d = G $m (& $nowAt 31); Check ($d.decision -eq 'WAIT' -and $d.reason -match 'moved after the rejoin') "p12: movement after the rejoin -> WAIT (got $($d.decision): $($d.reason))"
$m = Mini 'p12_still' $std12 @((Sn 5 10 2 'CANCEL_PENDING'), (Sn 12 10 2 'CANCEL_PENDING'), (Sn 20 10 2 'CANCEL_PENDING'), (Sn 28 10 2 'CANCEL_PENDING'))
$d = G $m (& $nowAt 31); Check ($d.decision -eq 'GO') "p12: stationary for 23 s with a fenced journal -> GO (got $($d.decision): $($d.reason))"
# multi: needs every lane quiescent AND the performance record
$std19 = @(@($base, '[Not Secure] [Server] [G5] START ALL'), @($base.AddSeconds(1), '[Not Secure] [Server] [G5] BEGIN p19_multi_1_npc t'), @($base.AddSeconds(40), '[Not Secure] [Server] [G5] AWAIT p19_multi_1_npc owner gate required'), @($base.AddSeconds(20), "NPCortex C2 goal=$G1 actor=a result=COMPLETED detail=ok"))
$sn19 = @((Sn 30 42.5 2.5 'COMPLETED'), (Sn 35 42.5 2.5 'COMPLETED'), (Sn 40 42.5 2.5 'COMPLETED'), (Sn 45 42.5 2.5 'COMPLETED'))
$m = Mini 'multi_noperf' $std19 $sn19; $d = G $m (& $nowAt 46); Check ($d.decision -eq 'WAIT' -and $d.reason -match 'performance record') "multi: no performance record yet -> WAIT (got $($d.decision): $($d.reason))"
$m = Mini 'multi_ok' $std19 $sn19 @(@{ file = 'performance-1.json'; obj = @{ kind = 'performance'; npc_count = 1 }; mtime = $base.AddSeconds(42) })
$d = G $m (& $nowAt 46); Check ($d.decision -eq 'GO') "multi: lane terminal + stationary + performance record -> GO (got $($d.decision): $($d.reason))"
# STOP and NO_RUN
$m = Mini 'abort' ((Std 'p03_navlong_40') + @(,@($base.AddSeconds(41), '[Not Secure] [Server] [G5] ABORT NPC observation lost in adaptive mode'))) @((Sn 30 1 1 'MOVING'))
$d = G $m (& $nowAt 50); Check ($d.decision -eq 'STOP' -and $d.code -eq 13) "ABORT marker -> STOP (got $($d.decision))"
$m = Mini 'norun' @(,@($base, 'unrelated line')) @(); $d = G $m (& $nowAt 5); Check ($d.decision -eq 'NO_RUN' -and $d.code -eq 14) "no START -> NO_RUN (got $($d.decision))"
# a newer START supersedes everything before it (stale run protection)
$m = Mini 'stale' @(@($base, '[Not Secure] [Server] [G5] START ALL'), @($base.AddSeconds(1), '[Not Secure] [Server] [G5] BEGIN p02_navlong_20 t'), @($base.AddSeconds(40), '[Not Secure] [Server] [G5] AWAIT p02_navlong_20 owner gate required'), @($base.AddSeconds(100), '[Not Secure] [Server] [G5] START ALL')) @((Sn 30 22.5 2.5 'COMPLETED'), (Sn 35 22.5 2.5 'COMPLETED'), (Sn 40 22.5 2.5 'COMPLETED'), (Sn 45 22.5 2.5 'COMPLETED'))
$d = G $m (& $nowAt 110); Check ($d.decision -eq 'RUNNING' -and $null -eq $d.command) "a newer START ignores the earlier run's AWAIT (got $($d.decision): $($d.reason))"

# revision 5: the gate STOPs without a passing preflight of the isolated run, and a GO is recorded as safe progression only (never a verdict)
$m = Mini 'gate_pf' (Std 'p02_navlong_20') @((Sn 30 2 2 $null $null), (Sn 35 2 2 $null $null), (Sn 40 2 2 $null $null), (Sn 45 2 2 $null $null))
$g5 = Join-Path $m 'g5run'; New-Item -ItemType Directory -Force -Path $g5 | Out-Null
function RunGatePf { $o = & $gate -WorldName NPCortex_G5_fixture -RunDir (Join-Path $m 'run') -OutDir (Join-Path $m 'gateout') -Now (& $nowAt 46) -Quiet -G5RunDir $g5; $c = $LASTEXITCODE; $r = ($o | Select-Object -Last 1) | ConvertFrom-Json; $r | Add-Member -NotePropertyName code -NotePropertyValue $c -Force; return $r }
$d = RunGatePf; Check ($d.decision -eq 'STOP' -and $d.code -eq 13 -and $d.reason -match 'preflight MISSING') "no preflight.json -> STOP (got $($d.decision): $($d.reason))"
[IO.File]::WriteAllText((Join-Path $g5 'preflight.json'), '{"status":"FAIL","run_id":"x"}')
$d = RunGatePf; Check ($d.decision -eq 'STOP' -and $d.reason -match 'preflight FAIL' -and $d.reason -match 'nothing is reset') "failed preflight -> STOP, nothing repaired (got $($d.decision): $($d.reason))"
[IO.File]::WriteAllText((Join-Path $g5 'preflight.json'), '{"status":"PASS","run_id":"x"}')
$d = RunGatePf; Check ($d.decision -eq 'GO') "passing preflight -> the normal rules apply (got $($d.decision): $($d.reason))"
$row = (Get-Content -Raw (Join-Path $m 'gateout/g5-gate-log.jsonl') -Encoding UTF8 -ErrorAction SilentlyContinue)
Check ($row -match 'SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT' -and $row -match '"preflight":"PASS"') 'a recorded GO says it is safe progression only and names the preflight state'

Remove-Item -LiteralPath $tmp -Recurse -Force
[pscustomobject]@{ checks = $checks; failures = $fails.Count; status = $(if ($fails.Count) { 'FAIL' } else { 'PASS' }); scope = 'owner-gate rules on synthetic fixtures only' } | ConvertTo-Json
$fails | ForEach-Object { "FAIL: $_" }
if ($fails.Count) { exit 1 }
