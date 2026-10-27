# NPCortex G5 owner gate (TEST ONLY, read-only). The data pack stops at AWAIT whenever it has no in-game terminal evidence (always in OWNER_GATED mode,
# and after a timeout/stall/never-moved in ADAPTIVE mode). This script reads the evidence the pack cannot read - M03 snapshots (position + journal
# state + goal/action ids written by production code) and the logged C2 terminal lines - and prints GO only when the actor is TERMINAL and QUIESCENT.
# The owner then releases the scenario with /function npcortex_g5:gate (the pack refuses it unless the scenario is awaiting). A fixed timer never
# progresses a scenario. It never writes to the world, the backend or the game; it appends decisions to g5-results/g5-gate-log.jsonl.
#   usage:  powershell -File scripts\g5-gate.ps1 -WorldName NPCortex_G5_Run1 -Watch        (prints GO and copies the command to the clipboard)
#           powershell -File scripts\g5-gate.ps1 -WorldName NPCortex_G5_Run1               (one evaluation; exit 0 GO, 10 WAIT/RUNNING, 11 HUNG, 12 DONE, 13 STOP, 14 NO_RUN, 15 CHECKPOINT)
param(
    [Parameter(Mandatory = $true)][string]$WorldName,
    [string]$RunDir,
    [string]$OutDir,
    [datetime]$Now,
    [switch]$Watch,
    [int]$IntervalSeconds = 3,
    [double]$HungMinutes = 8,
    [switch]$NoClipboard,
    [switch]$NoBeep,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'g5-common.ps1')
if (-not $RunDir) { $RunDir = Join-Path $root 'forge-mod/run' }
if (-not $OutDir) { $OutDir = Join-Path $root 'g5-results' }
$world = Join-Path (Join-Path $RunDir 'saves') $WorldName
if (-not (Test-Path -LiteralPath $world)) { throw "World folder not found: $world" }
$logPath = Join-Path (Join-Path $RunDir 'logs') 'latest.log'
$evDir = Join-Path $world 'data/npcortex/m03-evidence'
$gateLog = Join-Path $OutDir 'g5-gate-log.jsonl'
$MaxSnapAgeSeconds = 25
$script:HaveNow = $PSBoundParameters.ContainsKey('Now')

function Read-Shared([string]$path) {   # the game keeps the file open: read with FileShare.ReadWrite
    $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    try { $sr = New-Object IO.StreamReader($fs); try { return $sr.ReadToEnd() } finally { $sr.Dispose() } } finally { $fs.Dispose() }
}
function Decision($decision, $scenario, $reason, $command = $null, $ev = $null) {
    return [pscustomobject]@{ decision = $decision; scenario = $scenario; reason = $reason; command = $command; evidence = $ev }
}
function Quiet-Snaps($list) {   # >= 3 snapshots spanning >= 7 s with a position spread <= 0.5 block
    $q = @($list | Select-Object -Last 4)
    if ($q.Count -lt 3) { return @{ ok = $false; why = "only $($q.Count) snapshot(s) so far (need 3 spanning 7 s)" } }
    $span = ($q[$q.Count - 1].t - $q[0].t).TotalSeconds
    if ($span -lt 7) { return @{ ok = $false; why = "last snapshots span only $([int]$span) s (need 7 s)" } }
    $sp = Spread $q
    if ($sp -gt 0.5) { return @{ ok = $false; why = "NPC still moving (position spread $(F1 $sp) blocks over the last $([int]$span) s)" } }
    return @{ ok = $true; why = "stationary for $([int]$span) s (spread $(F1 $sp))" }
}

function Get-GateDecision {
    $nowT = if ($script:HaveNow) { $Now } else { Get-Date }
    if (-not (Test-Path -LiteralPath $logPath)) { return Decision 'NO_RUN' $null "no Minecraft log at $logPath" }
    $markers = New-Object System.Collections.ArrayList; $c2 = New-Object System.Collections.ArrayList
    foreach ($ln in ((Read-Shared $logPath) -split "`r?`n")) {
        $m = [regex]::Match($ln, '^\[(\d\d[A-Za-z]{3}\d{4} \d\d:\d\d:\d\d)\.(\d{3})\] \[([^\]]+)\] \[[^\]]*\]: (.*)$')
        if (-not $m.Success -or $m.Groups[3].Value -notlike 'Server thread*') { continue }   # the client chat echo on the Render thread is ignored
        $t = [datetime]::ParseExact($m.Groups[1].Value, 'ddMMMyyyy HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture).AddMilliseconds([int]$m.Groups[2].Value)
        if ($t -gt $nowT) { continue }
        $msg = $m.Groups[4].Value
        $g = [regex]::Match($msg, '\[G5\] (\w+)\s*(\S*)\s*(.*)$')
        if ($g.Success) { [void]$markers.Add([pscustomobject]@{ i = $markers.Count; t = $t; kind = $g.Groups[1].Value; id = $g.Groups[2].Value; rest = $g.Groups[3].Value }); continue }
        $c = [regex]::Match($msg, 'NPCortex C2 goal=(\S+) actor=(\S+) result=(\w+) detail=(.*)$')
        if ($c.Success) { [void]$c2.Add([pscustomobject]@{ t = $t; goal = $c.Groups[1].Value; result = $c.Groups[3].Value }) }
    }
    $starts = @($markers | Where-Object { $_.kind -eq 'START' })
    if (-not $starts.Count) { return Decision 'NO_RUN' $null 'no [G5] START marker in the log (run /function npcortex_g5:start)' }
    $runStart = $starts[$starts.Count - 1]
    $rm = @($markers | Where-Object { $_.i -ge $runStart.i })
    $abort = @($rm | Where-Object { $_.kind -eq 'ABORT' }) | Select-Object -First 1
    if ($abort) { return Decision 'STOP' $null "the pack aborted the run: $($abort.id) $($abort.rest). Nothing further is dispatched; collect the evidence." }
    if (@($rm | Where-Object { $_.kind -eq 'SEQUENCE_DONE' })) { return Decision 'DONE' $null 'sequence finished (SEQUENCE_DONE is not a verdict): run scripts\collect-g5-evidence.ps1' }
    $cp = @($rm | Where-Object { $_.kind -eq 'CHECKPOINT' }) | Select-Object -Last 1
    $rj = @($rm | Where-Object { $_.kind -eq 'REJOIN' }) | Select-Object -Last 1
    if ($cp -and (-not $rj -or $rj.i -lt $cp.i)) { return Decision 'CHECKPOINT' 'p11_savequit_checkpoint' 'checkpoint reached with the NPC mid-goal: Esc > Save and Quit to Title, re-open the world, then run /function npcortex_g5:rejoin' }
    $beg = @($rm | Where-Object { $_.kind -eq 'BEGIN' }) | Select-Object -Last 1
    if (-not $beg) { return Decision 'RUNNING' $null 'run started, no scenario has begun yet' }
    $id = $beg.id
    if (@($rm | Where-Object { $_.kind -eq 'END' -and $_.id -eq $id -and $_.i -gt $beg.i })) { return Decision 'RUNNING' $id 'scenario ended; the next one starts by itself' }
    $aw = @($rm | Where-Object { $_.kind -eq 'AWAIT' -and $_.id -eq $id -and $_.i -gt $beg.i }) | Select-Object -First 1
    if (-not $aw) { return Decision 'RUNNING' $id 'scenario in progress inside its pack-controlled window; the gate is not needed unless the pack prints AWAIT' }
    if (@($rm | Where-Object { $_.kind -eq 'GATE' -and $_.id -eq $id -and $_.i -gt $aw.i })) { return Decision 'RUNNING' $id 'gate released; the scenario is finishing' }
    $sc = $S | Where-Object { $_[0] -eq $id } | Select-Object -First 1
    if (-not $sc) { return Decision 'WAIT' $id "unknown scenario id $id" }
    $type = $sc[2]; $multiN = 1
    $names = @('yui'); if ($type -eq 'multi') { $multiN = [int]($id -replace '^p\d+_multi_(\d+)_npc$', '$1'); $names = @($laneNames[0..($multiN - 1)]) }

    # ---- snapshots (independent of in-game selectors)
    $snaps = New-Object System.Collections.ArrayList; $perfFiles = @()
    if (Test-Path -LiteralPath $evDir) {
        foreach ($f in (Get-ChildItem -LiteralPath $evDir -Filter *.json -File)) {
            try { $o = (Read-Shared $f.FullName) | ConvertFrom-Json } catch { continue }
            if ($o.kind -eq 'performance') { if ($o.npc_count -eq $multiN -and $f.LastWriteTime -ge $beg.t -and $f.LastWriteTime -le $nowT.AddSeconds(5)) { $perfFiles += $f.Name }; continue }
            if (-not $o.captured_utc -or $null -eq $o.x) { continue }
            $lt = ([datetime]::Parse($o.captured_utc)).ToLocalTime()
            if ($lt -gt $nowT -or $lt -lt $runStart.t.AddSeconds(-2)) { continue }
            $j = $o.c2_journal
            [void]$snaps.Add([pscustomobject]@{ t = $lt; name = $o.name; x = $o.x; z = $o.z; tick = $o.capture_tick; state = $(if ($j) { $j.state } else { $null }); goal = $(if ($j) { $j.goal_id } else { $null }); action = $(if ($j) { $j.action_id } else { $null }) })
        }
    }
    $all = @($snaps | Sort-Object t)
    $win = @($all | Where-Object { $_.t -ge $beg.t.AddMilliseconds(-200) -and $names -contains $_.name })
    $prevGoals = @($all | Where-Object { $_.t -lt $beg.t.AddMilliseconds(-200) -and $_.goal } | ForEach-Object { $_.goal } | Select-Object -Unique)
    $own = @($win | Where-Object { $_.goal -and ($prevGoals -notcontains $_.goal) } | ForEach-Object { $_.goal } | Select-Object -Unique)
    $ev = [ordered]@{ scenario = $id; type = $type; await_at = $aw.t.ToString('s'); snapshots_in_window = $win.Count; own_goals = $own }
    if (-not $win.Count) { return Decision 'WAIT' $id 'no M03 snapshot in this scenario window yet (M03 bridge enabled? backend reachable?)' $null $ev }
    $newest = $win[$win.Count - 1]
    $age = ($nowT - $newest.t).TotalSeconds
    $ev['newest_snapshot_age_s'] = [int]$age
    # game-time since AWAIT (server ticks of the snapshots, immune to the game being paused): used for the hung decision
    $base = @($win | Where-Object { $_.t -ge $aw.t }) | Select-Object -First 1
    $gameSec = if ($base) { ([double]$newest.tick - [double]$base.tick) / 20.0 } else { 0 }
    $ev['game_seconds_since_await'] = [int]$gameSec
    $hung = ($gameSec -ge ($HungMinutes * 60))
    function NotReady($why) {
        if ($hung) { return Decision 'HUNG' $id "not terminal/quiescent $([int]($gameSec / 60)) game-minutes after AWAIT: $why. Safe checkpoint: /function npcortex_g5:cancel_current (cancels the NPCs' goals), then run this script again." $null $ev }
        return Decision 'WAIT' $id $why $null $ev
    }
    if ($age -gt $MaxSnapAgeSeconds) { return NotReady "newest M03 snapshot is $([int]$age) s old: the game may be paused or unfocused (press F3+P once to stop pausing on focus loss), or the M03 bridge / backend stopped" }

    function Ready($why) { return Decision 'GO' $id $why "/function npcortex_g5:gate/$id" $ev }
    # ---- type specific terminal / quiescence rules
    switch ($type) {
        'checkpoint' {
            $y = @($win | Where-Object { $_.name -eq 'yui' }); $first = $y[0]
            $disp = Dist $newest $first.x $first.z
            if ($newest.state -and (Test-G5Active $newest.state) -and $newest.state -ne 'CANCEL_PENDING' -and $disp -ge 3) { return Ready "NPC is mid-goal (journal $($newest.state), $(F1 $disp) blocks from its start): the Save&Quit checkpoint can start now" }
            if (Test-G5Terminal $newest.state) { return Decision 'MISSED' $id "the goal already ended ($($newest.state)) before the checkpoint could start. Do not Save&Quit; re-run /function npcortex_g5:only/p11_savequit_checkpoint" $null $ev }
            return NotReady "waiting for a mid-goal snapshot (journal state '$($newest.state)', displacement $(F1 $disp))"
        }
        'rejoin' {
            $y = @($win | Where-Object { $_.name -eq 'yui' })
            if ($y.Count -lt 3) { return NotReady "only $($y.Count) post-rejoin snapshot(s) (need 3 over at least 15 s)" }
            $span = ($y[$y.Count - 1].t - $y[0].t).TotalSeconds
            if ($span -lt 15) { return NotReady "post-rejoin observation spans only $([int]$span) s (need 15 s)" }
            if ((Spread $y) -gt 0.75) { return NotReady "NPC moved after the rejoin (spread $(F1 (Spread $y))): a replayed goal would still be active; waiting for quiescence" }
            if ($newest.state -and (Test-G5Active $newest.state) -and $newest.state -ne 'CANCEL_PENDING') { return NotReady "journal state '$($newest.state)' is an active goal state" }
            return Ready "no movement over $([int]$span) s after the rejoin; journal state '$($newest.state)'"
        }
        'follow1' {
            $y = @($win | Where-Object { $_.name -eq 'yui' }); $q = Quiet-Snaps $y; $d = Dist $newest 30.5 2.5
            if ($d -gt $sc[5]) { return NotReady "NPC is $(F1 $d) blocks from the operator (limit $($sc[5]))" }
            if (-not $q.ok) { return NotReady $q.why }
            return Ready "NPC $(F1 $d) blocks from the operator and $($q.why) (follow itself stays active until p17 unfollows)"
        }
        'follow2' {
            $y = @($win | Where-Object { $_.name -eq 'yui' }); $q = Quiet-Snaps $y
            if (-not $q.ok) { return NotReady $q.why }
            return Ready "NPC $($q.why) while the operator is far away"
        }
        'e3' {
            $y = @($win | Where-Object { $_.name -eq 'yui' }); $q = Quiet-Snaps $y; $disp = Dist $newest $y[0].x $y[0].z
            if (-not $q.ok) { return NotReady $q.why }
            if ($disp -lt 1.0) { return NotReady "NPC has not been displaced yet ($(F1 $disp) blocks)" }
            return Ready "NPC displaced $(F1 $disp) blocks and $($q.why) (E3 acceptance still needs taskmovefinalize + DB review)"
        }
        'multi' {
            foreach ($nm in $names) {
                $l = @($win | Where-Object { $_.name -eq $nm })
                if (-not $l.Count) { return NotReady "no snapshot of $nm yet" }
                $q = Quiet-Snaps $l; if (-not $q.ok) { return NotReady "$nm`: $($q.why)" }
                $last = $l[$l.Count - 1]
                if ($last.goal -and ($own -contains $last.goal) -and (Test-G5Active $last.state)) { return NotReady "$nm journal state '$($last.state)' is not terminal" }
            }
            $term = @($c2 | Where-Object { $_.t -ge $runStart.t -and ($own -contains $_.goal) })
            $ev['terminal_lines'] = $term.Count
            # COMPLETED/FAILED goals must also have their logged 'result=' line (exactly-once cross-check); CANCELLED/INTERRUPTED are journal-terminal and have no such line in production logging
            $needLines = @($own | Where-Object { $g = $_; (@($win | Where-Object { $_.goal -eq $g }) | Select-Object -Last 1).state -in 'COMPLETED', 'FAILED' })
            $haveLines = @($needLines | Where-Object { $g = $_; @($c2 | Where-Object { $_.t -ge $runStart.t -and $_.goal -eq $g }).Count -ge 1 })
            if ($haveLines.Count -lt $needLines.Count) { return NotReady "$($haveLines.Count) of $($needLines.Count) completed/failed goals have a logged terminal line" }
            if (-not $perfFiles.Count) { return NotReady "the M03 performance record for $multiN NPC(s) has not been written yet (120 s measurement window)" }
            return Ready "all $multiN NPC(s) terminal and stationary; $($term.Count) terminal line(s); performance record written"
        }
        default {   # nav, noreach, cancel9, cancel10
            $y = @($win | Where-Object { $_.name -eq 'yui' }); $q = Quiet-Snaps $y
            if ($own.Count -eq 0) {
                if ($newest.state -and (Test-G5Active $newest.state) -and ($prevGoals -notcontains $newest.goal)) { return NotReady "journal state '$($newest.state)' is active" }
                if (-not $q.ok) { return NotReady $q.why }
                return Ready "no goal was evidenced for this scenario and the NPC is $($q.why); the collector will report PENDING/FAIL"
            }
            foreach ($g in $own) {
                $last = @($y | Where-Object { $_.goal -eq $g }) | Select-Object -Last 1
                if (-not (Test-G5Terminal $last.state)) { return NotReady "goal $($g.Substring(0, 8)) journal state '$($last.state)' is not terminal" }
                $lines = @($c2 | Where-Object { $_.t -ge $runStart.t -and $_.goal -eq $g })
                # COMPLETED/FAILED must also be logged (exactly-once cross-check); CANCELLED/INTERRUPTED are terminal in the journal and production logs no 'result=' line for them
                if ($last.state -in 'COMPLETED', 'FAILED' -and -not $lines.Count) { return NotReady "goal $($g.Substring(0, 8)) is $($last.state) in the journal but no C2 terminal line is logged yet" }
            }
            if (-not $q.ok) { return NotReady $q.why }
            $term = @($c2 | Where-Object { $_.t -ge $runStart.t -and ($own -contains $_.goal) })
            $ev['terminal_lines'] = $term.Count
            return Ready "goal terminal ($((@($y | Where-Object { $_.goal -eq $own[0] } | Select-Object -Last 1).state)); $($term.Count) logged terminal line(s)) and the NPC is $($q.why)$(if ($term.Count -gt 1) { '; WARNING: more than one terminal line (the collector will FAIL exactly-once)' })"
        }
    }
}

function Write-GateLog($d, $nowT) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $row = [ordered]@{ time = $nowT.ToString('s'); scenario = $d.scenario; decision = $d.decision; reason = $d.reason; command = $d.command; evidence = $d.evidence }
    [IO.File]::AppendAllText($gateLog, (($row | ConvertTo-Json -Compress -Depth 5) + "`n"), (New-Object System.Text.UTF8Encoding($false)))
}
function Show($d) {
    if ($Quiet) { return }
    $tag = "[G5 GATE] $(if ($d.scenario) { $d.scenario } else { '-' })"
    switch ($d.decision) {
        'GO'         { Write-Host "$tag : GO" -ForegroundColor Green; Write-Host "  $($d.reason)"; Write-Host "  In Minecraft run:  $($d.command)" -ForegroundColor Green }
        'HUNG'       { Write-Host "$tag : HUNG" -ForegroundColor Red; Write-Host "  $($d.reason)" }
        'MISSED'     { Write-Host "$tag : MISSED" -ForegroundColor Red; Write-Host "  $($d.reason)" }
        'CHECKPOINT' { Write-Host "$tag : CHECKPOINT" -ForegroundColor Yellow; Write-Host "  $($d.reason)" }
        'STOP'       { Write-Host "$tag : STOP" -ForegroundColor Red; Write-Host "  $($d.reason)" }
        'DONE'       { Write-Host "$tag : DONE" -ForegroundColor Cyan; Write-Host "  $($d.reason)" }
        default      { Write-Host "$tag : $($d.decision) - $($d.reason)" -ForegroundColor DarkGray }
    }
}
$exitCodes = @{ GO = 0; WAIT = 10; RUNNING = 10; HUNG = 11; MISSED = 11; DONE = 12; STOP = 13; NO_RUN = 14; CHECKPOINT = 15 }

if (-not $Watch) {
    $d = Get-GateDecision
    $nowT = if ($script:HaveNow) { $Now } else { Get-Date }
    if ($d.decision -in 'GO', 'HUNG', 'MISSED', 'STOP', 'CHECKPOINT', 'DONE') { Write-GateLog $d $nowT }
    Show $d
    $d | ConvertTo-Json -Depth 5 -Compress | Write-Output
    exit $exitCodes[$d.decision]
}
$last = ''
Write-Host "[G5 GATE] watching $WorldName (Ctrl+C to stop). Keep the Minecraft window focused or press F3+P once so the game does not pause when it loses focus." -ForegroundColor Cyan
while ($true) {
    $d = Get-GateDecision
    $key = "$($d.decision)|$($d.scenario)|$($d.reason)"
    if ($key -ne $last) {
        $last = $key
        Show $d
        if ($d.decision -in 'GO', 'HUNG', 'MISSED', 'STOP', 'CHECKPOINT', 'DONE') { Write-GateLog $d (Get-Date) }
        if ($d.decision -eq 'GO') {
            if (-not $NoClipboard) { try { Set-Clipboard -Value $d.command; Write-Host '  (command copied to the clipboard: in Minecraft press T, Ctrl+V, Enter)' -ForegroundColor DarkGray } catch { } }
            if (-not $NoBeep) { try { [Console]::Beep(880, 200) } catch { } }
        }
        if ($d.decision -in 'DONE', 'STOP') { break }
    }
    Start-Sleep -Seconds $IntervalSeconds
}
exit $exitCodes[$d.decision]
