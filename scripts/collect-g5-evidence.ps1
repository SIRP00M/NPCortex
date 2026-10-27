# NPCortex Roadmap #2 G5 evidence collector (TEST ONLY, read-only).
# Copies (never moves/deletes) logs, M03 evidence snapshots, outbox records, a SQLite backup and profiler output into a staging
# folder, redacts secrets from text, analyses the [G5] markers against the C2 log lines and writes a machine-readable verdict.
# Verdicts are PASS / FAIL / BLOCKED / PENDING / UNVERIFIED. A Minecraft command being dispatched is never evidence.
param(
    [Parameter(Mandatory = $true)][string]$WorldName,
    [string]$RunDir,
    [string]$Database,
    [string]$BackendLog,
    [string]$BackendUrl = 'http://127.0.0.1:8765',
    [int]$LookbackHours = 12,
    [string]$OutDir
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'g5-common.ps1')
if (-not $RunDir)   { $RunDir   = Join-Path $root 'forge-mod/run' }
if (-not $Database) { $Database = Join-Path $root 'backend/data/npcortex.sqlite3' }
if (-not $OutDir)   { $OutDir   = Join-Path $root 'g5-results' }
$world = Join-Path (Join-Path $RunDir 'saves') $WorldName
if (-not (Test-Path -LiteralPath $world)) { throw "World folder not found: $world" }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = Join-Path ([IO.Path]::GetTempPath()) "g5-stage-$stamp"
foreach ($d in 'logs','world/m03-evidence','world/outbox','world/data','backend','profiler','meta') { New-Item -ItemType Directory -Force -Path (Join-Path $stage $d) | Out-Null }
$cutoff = (Get-Date).AddHours(-$LookbackHours)
$utf8 = New-Object System.Text.UTF8Encoding($false)
$notes = New-Object System.Collections.ArrayList
function Note($m) { [void]$notes.Add($m) }

function Redact([string]$t) {
    $t = $t -replace '(?i)(token|secret|password|passwd|authorization|accesstoken|api[_-]?key)(["'']?\s*[:=]\s*["'']?)[^\s"'',;}\]]+', '$1$2[REDACTED]'
    $t = $t -replace '(?i)(--accessToken\s+)\S+', '$1[REDACTED]'
    $t = $t -replace '(?i)(Bearer\s+)[A-Za-z0-9._\-]+', '$1[REDACTED]'
    $t = $t -replace '\b[0-9A-Fa-f]{64}\b', '[HEX64-REDACTED]'
    return $t
}
function CopyRedacted([string]$src, [string]$dst) {   # read-only on the source
    [IO.File]::WriteAllText($dst, (Redact ([IO.File]::ReadAllText($src))), $utf8)
}

# ---------------------------------------------------------------- logs
$logDir = Join-Path $RunDir 'logs'
$logFiles = @()
if (Test-Path -LiteralPath $logDir) {
    $logFiles = Get-ChildItem -LiteralPath $logDir -File | Where-Object { ($_.Name -eq 'latest.log' -or $_.Name -like '*.log.gz') -and $_.LastWriteTime -ge $cutoff } | Sort-Object LastWriteTime
}
if (-not $logFiles) { Note "no Minecraft logs within the last $LookbackHours h under $logDir" }
$lines = New-Object System.Collections.ArrayList     # @{t=DateTime; src=string; msg=string}
foreach ($f in $logFiles) {
    if ($f.Name -like '*.gz') {
        $fs = [IO.File]::OpenRead($f.FullName); $gz = New-Object IO.Compression.GzipStream($fs, [IO.Compression.CompressionMode]::Decompress)
        $sr = New-Object IO.StreamReader($gz); $text = $sr.ReadToEnd(); $sr.Dispose(); $gz.Dispose(); $fs.Dispose()
        $out = Join-Path $stage ('logs/' + ($f.Name -replace '\.gz$', ''))
    } else { $text = [IO.File]::ReadAllText($f.FullName); $out = Join-Path $stage ('logs/' + $f.Name) }
    [IO.File]::WriteAllText($out, (Redact $text), $utf8)
    foreach ($ln in ($text -split "`r?`n")) {
        # Forge 1.20.1 format (verified against forge-mod/run/logs): [27Oct2026 06:35:22.281] [Server thread/INFO] [logger/]: message
        $m = [regex]::Match($ln, '^\[(\d\d[A-Za-z]{3}\d{4} \d\d:\d\d:\d\d)\.(\d{3})\] \[([^\]]+)\] \[[^\]]*\]: (.*)$')
        if (-not $m.Success) { continue }
        $t = [datetime]::ParseExact($m.Groups[1].Value, 'ddMMMyyyy HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture).AddMilliseconds([int]$m.Groups[2].Value)
        [void]$lines.Add([pscustomobject]@{ t = $t; thr = $m.Groups[3].Value; src = $f.Name; msg = $m.Groups[4].Value })
    }
}
$crash = Join-Path $RunDir 'crash-reports'
if (Test-Path -LiteralPath $crash) { Get-ChildItem -LiteralPath $crash -File -Filter *.txt | Where-Object { $_.LastWriteTime -ge $cutoff } | ForEach-Object { CopyRedacted $_.FullName (Join-Path $stage ('logs/crash-' + $_.Name)); Note "crash report present: $($_.Name)" } }

$markers = New-Object System.Collections.ArrayList; $c2res = New-Object System.Collections.ArrayList; $c2int = New-Object System.Collections.ArrayList; $npcErr = New-Object System.Collections.ArrayList
foreach ($l in $lines) {
    # each [G5] 'say' is echoed by the client chat on the Render thread: count only the server-side line (otherwise every marker is doubled)
    if ($l.thr -notlike 'Server thread*') { if ($l.msg -match 'NPCortex' -and $l.msg -match 'ERROR|Exception|rejected|HOLD') { [void]$npcErr.Add([pscustomobject]@{ t = $l.t; msg = (Redact $l.msg) }) }; continue }
    $m = [regex]::Match($l.msg, '\[G5\] (\w+)\s*(\S*)\s*(.*)$')
    if ($m.Success) { [void]$markers.Add([pscustomobject]@{ i = $markers.Count; t = $l.t; kind = $m.Groups[1].Value; id = $m.Groups[2].Value; rest = $m.Groups[3].Value }); continue }
    $m = [regex]::Match($l.msg, 'NPCortex C2 goal=(\S+) actor=(\S+) result=(\w+) detail=(.*)$')
    if ($m.Success) { [void]$c2res.Add([pscustomobject]@{ t = $l.t; goal = $m.Groups[1].Value; actor = $m.Groups[2].Value; result = $m.Groups[3].Value; detail = $m.Groups[4].Value }); continue }
    $m = [regex]::Match($l.msg, 'NPCortex C2 interrupted goal=(\S+) actor=(\S+) pendingReconciliation=(\w+) reason=(.*)$')
    if ($m.Success) { [void]$c2int.Add([pscustomobject]@{ t = $l.t; goal = $m.Groups[1].Value; reason = $m.Groups[4].Value }); continue }
    if ($l.msg -match 'NPCortex' -and $l.msg -match 'ERROR|Exception|rejected|HOLD') { [void]$npcErr.Add([pscustomobject]@{ t = $l.t; msg = (Redact $l.msg) }) }
}
$logExtract = @("# G5 markers"); $markers | ForEach-Object { $logExtract += ('{0:HH:mm:ss} {1} {2} {3}' -f $_.t, $_.kind, $_.id, $_.rest) }
$logExtract += "# C2 results"; $c2res | ForEach-Object { $logExtract += ('{0:HH:mm:ss} goal={1} result={2} detail={3}' -f $_.t, $_.goal, $_.result, $_.detail) }
$logExtract += "# C2 interrupted"; $c2int | ForEach-Object { $logExtract += ('{0:HH:mm:ss} goal={1} reason={2}' -f $_.t, $_.goal, $_.reason) }
$logExtract += "# NPCortex warnings/errors"; $npcErr | ForEach-Object { $logExtract += ('{0:HH:mm:ss} {1}' -f $_.t, $_.msg) }
[IO.File]::WriteAllLines((Join-Path $stage 'logs/g5-log-extract.txt'), $logExtract, $utf8)

# ---------------------------------------------------------------- world evidence (copies only)
$snaps = New-Object System.Collections.ArrayList; $perf = New-Object System.Collections.ArrayList
$ev = Join-Path $world 'data/npcortex/m03-evidence'
if (Test-Path -LiteralPath $ev) {
    Get-ChildItem -LiteralPath $ev -Filter *.json -File | Where-Object { $_.LastWriteTime -ge $cutoff } | ForEach-Object {
        CopyRedacted $_.FullName (Join-Path $stage ('world/m03-evidence/' + $_.Name))
        try { $o = [IO.File]::ReadAllText($_.FullName) | ConvertFrom-Json } catch { Note "unreadable evidence $($_.Name)"; return }
        if ($o.kind -eq 'performance') { [void]$perf.Add($o) }
        elseif ($o.captured_utc) { $o | Add-Member -NotePropertyName local -NotePropertyValue ([datetime]::Parse($o.captured_utc).ToLocalTime()) -Force; [void]$snaps.Add($o) }
    }
} else { Note "no m03-evidence folder in the world (M03 bridge never produced a snapshot)" }
$ob = Join-Path $world 'data/npcortex/transport'
if (Test-Path -LiteralPath $ob) { Get-ChildItem -LiteralPath $ob -Filter *.json -File | ForEach-Object { CopyRedacted $_.FullName (Join-Path $stage ('world/outbox/' + $_.Name)) } }
$dataDir = Join-Path $world 'data'
if (Test-Path -LiteralPath $dataDir) { Get-ChildItem -LiteralPath $dataDir -File -Filter *.dat | Where-Object { $_.Name -match 'npcortex|c1|c2' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $stage ('world/data/' + $_.Name)) } }
$dp = Join-Path $world 'datapacks'
$dpInfo = @{}
if (Test-Path -LiteralPath $dp) { Get-ChildItem -LiteralPath $dp -Directory | ForEach-Object { $dpInfo[$_.Name] = (Get-ChildItem -Recurse -File -LiteralPath $_.FullName | Get-FileHash -Algorithm SHA256 | ForEach-Object { $_.Hash } | Sort-Object) -join '' } }
function TreeHash($p) { $h = (Get-ChildItem -Recurse -File -LiteralPath $p | Sort-Object FullName | ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash }) -join ''; return [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($utf8.GetBytes($h))).Replace('-', '') }
$repoPack = Join-Path $root 'integrations/datapacks/NPCortex_G5'
$packMatches = 'UNVERIFIED'
if ((Test-Path (Join-Path $dp 'NPCortex_G5')) -and (Test-Path $repoPack)) { $packMatches = $(if ((TreeHash (Join-Path $dp 'NPCortex_G5')) -eq (TreeHash $repoPack)) { 'IDENTICAL_TO_REPO' } else { 'DIFFERS_FROM_REPO' }) }

# ---------------------------------------------------------------- backend (SQLite online backup, never the live file)
$dbInfo = 'NOT_COLLECTED'
if (Test-Path -LiteralPath $Database) {
    $py = @'
import sqlite3, sys, json
src, dst, out = sys.argv[1:4]
s = sqlite3.connect('file:' + src.replace('\\', '/') + '?mode=ro', uri=True)
d = sqlite3.connect(dst); s.backup(d); s.close(); d.close()
c = sqlite3.connect(dst); c.row_factory = sqlite3.Row
tables = [r[0] for r in c.execute("select name from sqlite_master where type='table' order by name")]
info = {'tables': {}, 'recent': {}}
bad = ('token', 'secret', 'password', 'key')
for t in tables:
    info['tables'][t] = c.execute('select count(*) from "%s"' % t).fetchone()[0]
for t in ('agent_goals', 'agent_actions', 'cplus_e3_g2_moves', 'cplus_e3_g3_terminals', 'cplus_e3_g3_cancel_intents'):
    if t in tables:
        rows = []
        for r in c.execute('select * from "%s" order by rowid desc limit 300' % t):
            rows.append({k: (v.hex() if isinstance(v, bytes) else v) for k, v in dict(r).items() if not any(b in k.lower() for b in bad)})
        info['recent'][t] = rows
json.dump(info, open(out, 'w'), indent=1, default=str)
'@
    $pyFile = Join-Path $stage 'meta/db_backup.py'; [IO.File]::WriteAllText($pyFile, $py, $utf8)
    try {
        & python -I $pyFile $Database (Join-Path $stage 'backend/npcortex-backup.sqlite3') (Join-Path $stage 'backend/db-summary.json')
        if ($LASTEXITCODE -ne 0) { throw "python exit $LASTEXITCODE" }
        $dbInfo = 'BACKUP_OK'
    } catch { $dbInfo = "BACKUP_FAILED: $($_.Exception.Message)"; Note "SQLite backup failed ($($_.Exception.Message)); live file NOT copied" }
} else { Note "database not found: $Database" }
$health = 'UNREACHABLE'
try { $health = (Invoke-WebRequest -UseBasicParsing -Uri ($BackendUrl.TrimEnd('/') + '/health') -TimeoutSec 4).Content } catch { }
[IO.File]::WriteAllText((Join-Path $stage 'backend/health.json'), $health, $utf8)
if ($BackendLog -and (Test-Path -LiteralPath $BackendLog)) { CopyRedacted $BackendLog (Join-Path $stage 'backend/backend.log') } else { Note 'backend log not supplied (-BackendLog); backend console output is UNVERIFIED' }

# ---------------------------------------------------------------- profiler (vanilla /debug start|stop output)
$prof = Join-Path $RunDir 'debug'
if (Test-Path -LiteralPath $prof) { Get-ChildItem -LiteralPath $prof -File | Where-Object { $_.LastWriteTime -ge $cutoff } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $stage ('profiler/' + $_.Name)) } }
else { Note 'no run/debug folder: no /debug profiler output (G4 MSPT percentiles PENDING)' }
$perfSummary = Join-Path $stage 'world/m03-performance-summary.json'
if ((Test-Path (Join-Path $root 'scripts/m03_performance.py')) -and $perf.Count -gt 0) { try { & python -I (Join-Path $root 'scripts/m03_performance.py') (Join-Path $stage 'world/m03-evidence') --output $perfSummary | Out-Null } catch { Note 'm03_performance.py failed' } }

# ---------------------------------------------------------------- analysis (evidence-first)
# Ground truth = M03 snapshots (position, journal state, goal/action ids written by production code) + the logged C2 terminal lines.
# The data pack's own OBS markers are a cross-check only: a disagreement is an INCONSISTENT_EVIDENCE failure, never a pass.
# Dist / Spread / States / F1, the scenario table $S and $laneNames come from g5-common.ps1 (shared with g5-gate.ps1)

$snapObjs = @($snaps | ForEach-Object {
    $j = $_.c2_journal
    [pscustomobject]@{ t = $_.local; name = $_.name; x = $_.x; z = $_.z; tick = $_.capture_tick; profile = $_.profile_id
                       state = $(if ($j) { $j.state } else { $null }); goal = $(if ($j) { $j.goal_id } else { $null }); action = $(if ($j) { $j.action_id } else { $null }) }
} | Where-Object { $null -ne $_.x } | Sort-Object t)

# --- run boundary: only the LAST [G5] START counts; earlier runs, stale snapshots and old C2 lines are ignored
$starts = @($markers | Where-Object { $_.kind -eq 'START' })
$runStart = $null; if ($starts.Count) { $runStart = $starts[$starts.Count - 1] }
if ($starts.Count -gt 1) { Note "$($starts.Count - 1) earlier G5 run(s) in the logs were ignored; analysed run: $($runStart.id) at $($runStart.t.ToString('s'))" }
if (-not $runStart) { Note 'no [G5] START marker in the collected logs: nothing can be attributed to a run' }
$rm = @(); if ($runStart) { $rm = @($markers | Where-Object { $_.i -ge $runStart.i }) }
$c2run = @(); if ($runStart) { $c2run = @($c2res | Where-Object { $_.t -ge $runStart.t }) }
$doneM = @($rm | Where-Object { $_.kind -eq 'SEQUENCE_DONE' }) | Select-Object -Last 1
$abortM = @($rm | Where-Object { $_.kind -eq 'ABORT' })
$endBound = if ($doneM) { $doneM.t.AddSeconds(150) } else { Get-Date }
if ($abortM.Count) { Note ("run aborted by the data pack: " + (($abortM | ForEach-Object { "$($_.id) $($_.rest)" }) -join ' | ')) }
if ($doneM -and ($doneM.id -like 'outcome=HAD_BLOCKED*')) { Note 'SEQUENCE_DONE reports blocked scenarios: the completion marker is not a verdict' }

function ModeAt($t) {
    $m = 'OWNER_GATED'   # an unprobed start is owner-gated too (the pack announces mode=OWNER_GATED_UNPROBED)
    foreach ($x in $rm) { if ($x.t -gt $t) { break }; if ($x.kind -eq 'PROBE' -and $x.id -like 'mode=*') { $m = $x.id.Substring(5) } elseif ($x.kind -eq 'NOTE' -and $x.id -like 'mode=*') { $m = 'OWNER_GATED' } }
    if ($m -eq 'FALLBACK') { $m = 'OWNER_GATED' }   # revision-3 packs called the snapshot-only mode FALLBACK
    return $m
}

# --- scenario windows [BEGIN, next BEGIN) in log order (immune to focus pauses) and goal ownership
$beginList = @($rm | Where-Object { $_.kind -eq 'BEGIN' })
$win = @{}; $seenGoals = New-Object 'System.Collections.Generic.HashSet[string]'; $claimed = New-Object 'System.Collections.Generic.HashSet[string]'
for ($k = 0; $k -lt $beginList.Count; $k++) {
    $b = $beginList[$k]
    $hi = if ($k + 1 -lt $beginList.Count) { $beginList[$k + 1].t } else { $endBound }
    $ss = @($snapObjs | Where-Object { $_.t -ge $b.t.AddMilliseconds(-200) -and $_.t -lt $hi })
    $ownG = @(); foreach ($g in ($ss | Where-Object { $_.goal } | ForEach-Object { $_.goal } | Select-Object -Unique)) { if (-not $seenGoals.Contains($g)) { $ownG += $g; [void]$seenGoals.Add($g) } }
    foreach ($g in ($ss | Where-Object { $_.goal } | ForEach-Object { $_.goal })) { [void]$claimed.Add($g) }
    $nextB = $null; if ($k + 1 -lt $beginList.Count) { $nextB = $beginList[$k + 1] }
    $win[$b.id] = [pscustomobject]@{ b = $b; hi = $hi; snaps = $ss; own = $ownG; hasEnd = $false; next = $nextB }
}
foreach ($id in @($win.Keys)) {
    $w = $win[$id]
    $e = @($rm | Where-Object { $_.kind -eq 'END' -and $_.id -eq $id -and $_.t -ge $w.b.t }) | Select-Object -First 1
    $w.hasEnd = [bool]$e
}

# --- owner-gate log written by g5-gate.ps1 (GO decisions): a released gate should have a recorded GO from the gate script
$gateRows = @(); $gateLogPath = Join-Path $OutDir 'g5-gate-log.jsonl'
if (Test-Path -LiteralPath $gateLogPath) {
    foreach ($gl in [IO.File]::ReadAllLines($gateLogPath)) { if ($gl.Trim()) { try { $r = $gl | ConvertFrom-Json; $r | Add-Member -NotePropertyName tt -NotePropertyValue ([datetime]::Parse($r.time)) -Force; $gateRows += $r } catch { } } }
    Copy-Item -LiteralPath $gateLogPath -Destination (Join-Path $stage 'gate-log.jsonl')
} else { Note 'no g5-gate-log.jsonl: owner-gate decisions of scripts/g5-gate.ps1 are not recorded (only matters when a scenario awaited the gate)' }
$results = New-Object System.Collections.ArrayList
$correlation = New-Object System.Collections.ArrayList
$script:probeCaps = $null
$gateStats = [ordered]@{ awaited = 0; released = 0; released_with_recorded_go = 0; released_without_recorded_go = 0; awaiting_never_released = 0 }
function Result($id, $cls, $status, $why, $ev) { [void]$results.Add([ordered]@{ id = $id; class = $cls; status = $status; reason = $why; evidence = $ev }) }

foreach ($sc in $S) {
    $id = $sc[0]; $cls = $sc[1]; $type = $sc[2]; $tx = [double]$sc[3]; $tz = [double]$sc[4]; $tol = [double]$sc[5]
    if (-not $runStart) { Result $id $cls 'PENDING' 'no [G5] START marker in the collected logs' @{}; continue }
    $w = $win[$id]
    if (-not $w) { Result $id $cls 'PENDING' 'not run: no BEGIN marker in the analysed run' @{}; continue }
    $b = $w.b; $hi = $w.hi
    $mode = ModeAt $b.t
    if ($type -eq 'probe') { $mode = ModeAt $hi }
    $ss = @($w.snaps); if ($type -ne 'multi') { $ss = @($ss | Where-Object { $_.name -eq 'yui' }) }
    $obs = @($rm | Where-Object { $_.kind -eq 'OBS' -and $_.id -eq $id -and $_.t -ge $b.t -and $_.t -le $hi } | ForEach-Object { $_.rest.Trim() })
    $own = @($w.own)
    $cand = @($c2run | Where-Object { ($own -contains $_.goal) -or ($_.t -ge $b.t -and $_.t -lt $hi.AddSeconds(2) -and -not $claimed.Contains($_.goal)) })
    $goalIds = @($own + @($cand | ForEach-Object { $_.goal }) | Select-Object -Unique)
    $actionIds = @($ss | Where-Object { $own -contains $_.goal -and $_.action } | ForEach-Object { $_.action } | Select-Object -Unique)
    $final = $ss | Select-Object -Last 1
    $evd = [ordered]@{ scenario_id = $id; mode = $mode; begin = $b.t.ToString('s'); end = $null; observations = $obs; snapshots = $ss.Count; goal_ids = $goalIds; action_ids = $actionIds
                       journal_states = @(); terminal_results = @($cand | ForEach-Object { "$($_.result) goal=$($_.goal) detail=$($_.detail)" }); final_position = $(if ($final) { "x=$($final.x) z=$($final.z) tick=$($final.tick)" } else { $null }) }
    if ($own.Count) { $evd['journal_states'] = @(States $ss $own[0]) }
    # owner gate bookkeeping (AWAIT -> GATE RELEASED -> END)
    $awM = @($rm | Where-Object { $_.kind -eq 'AWAIT' -and $_.id -eq $id -and $_.t -ge $b.t -and $_.t -le $hi }) | Select-Object -First 1
    $gmM = @($rm | Where-Object { $_.kind -eq 'GATE' -and $_.id -eq $id -and $_.t -ge $b.t -and $_.t -le $hi -and $awM -and $_.i -gt $awM.i }) | Select-Object -First 1
    $gateState = 'not needed'
    if ($awM) {
        $gateStats.awaited++
        if (-not $gmM) { $gateState = 'awaiting (never released)'; $gateStats.awaiting_never_released++ }
        else {
            $gateStats.released++
            $go = @($gateRows | Where-Object { $_.scenario -eq $id -and $_.decision -eq 'GO' -and $_.tt -ge $awM.t.AddSeconds(-1) -and $_.tt -le $gmM.t.AddSeconds(2) }) | Select-Object -First 1
            if ($go) { $gateState = 'released after GO'; $gateStats.released_with_recorded_go++ } else { $gateState = 'released WITHOUT recorded GO'; $gateStats.released_without_recorded_go++; Note "gate of $id was released without a GO decision recorded by g5-gate.ps1 (the scenario's own evidence is still judged; the progression was not verified by the script)" }
        }
    }
    $evd['await'] = $(if ($awM) { $awM.t.ToString('s') } else { $null }); $evd['gate'] = $gateState
    [void]$correlation.Add([pscustomobject]@{ scenario = $id; mode = $mode; goal = ($goalIds -join ','); action = ($actionIds -join ','); states = ($evd['journal_states'] -join '>'); terminal = (@($cand | ForEach-Object { $_.result }) -join ','); gate = $gateState })
    $blocked = $obs | Where-Object { $_ -like 'BLOCKED_*' } | Select-Object -First 1
    if ($blocked) { Result $id $cls 'BLOCKED' "datapack precondition: $blocked" $evd; continue }
    if ($awM -and -not $gmM -and $w.hasEnd) { Result $id $cls 'FAIL' 'INCONSISTENT_EVIDENCE: the scenario ended without a recorded owner-gate release (timer-only progression)' $evd; continue }
    if ($awM -and -not $gmM) { Result $id $cls 'PENDING' 'AWAITING_OWNER_GATE: the scenario never reached terminal/quiescent evidence that the owner released (scripts/g5-gate.ps1)' $evd; continue }
    if (-not $w.hasEnd -and $type -ne 'checkpoint') { Result $id $cls 'PENDING' 'BEGIN without END: interrupted, aborted or still running' $evd; continue }
    if ($ss.Count -eq 0) { Result $id $cls 'PENDING' 'no M03 evidence snapshot for this scenario (M03 bridge not enabled, snapshot dropped, or evidence folder missing)' $evd; continue }
    $d = Dist $final $tx $tz
    $selReached = $obs -contains 'REACHED'
    $selOther = @($obs | Where-Object { $_ -in 'TIMEOUT', 'STALLED_TERMINAL_INFERRED', 'NEVER_MOVED' }).Count -gt 0
    $adaptive = ($mode -eq 'ADAPTIVE')
    $terms = @($cand); $completed = @($terms | Where-Object { $_.result -eq 'COMPLETED' }); $failedT = @($terms | Where-Object { $_.result -eq 'FAILED' })
    $once = ($terms.Count -eq 1)
    $ids = "goal=$($goalIds -join ',') action=$($actionIds -join ',') states=$($evd['journal_states'] -join '>')"
    switch ($type) {
      'probe' {
        $pr = [ordered]@{}; foreach ($m in ($rm | Where-Object { $_.kind -eq 'PROBE' -and $_.t -ge $b.t -and $_.t -le $hi })) { $kv = $m.id -split '=', 2; if ($kv.Count -eq 2) { $pr[$kv[0]] = $kv[1] } }
        $evd['capabilities'] = $pr
        $script:probeCaps = $pr
        $legacy = $pr.Contains('selector') -or $pr.Contains('tag')   # revision-3 vocabulary (selector=/tag=): the unboxed type=player selector it used cannot see the NPC
        $dec = @('ADAPTIVE', 'OWNER_GATED', 'FALLBACK') | Where-Object { $obs -contains $_ } | Select-Object -First 1
        $d0 = Dist ($ss | Select-Object -First 1) $tx $tz
        $capTxt = (@('playerlist_control', 'world_limited_player', 'typeless_entity', 'tag_v1', 'tag_v2', 'entity_data', 'position') | ForEach-Object { "$_=$(if ($pr.Contains($_)) { $pr[$_] } else { 'not-reported' })" }) -join ' '
        if ($legacy) { $capTxt = 'LEGACY rev3 probe: ' + (($pr.Keys | ForEach-Object { "$_=$($pr[$_])" }) -join ' ') + ' (its tag/entity_data FAIL lines were never attempted when selector=NONE)' }
        if (-not $dec) { Result $id $cls 'PENDING' 'no mode decision logged' $evd }
        elseif ($d0 -gt $tol) { Result $id $cls 'FAIL' "M03 snapshot does not place the NPC at the expected spawn (2,2): distance $(F1 $d0)" $evd }
        elseif ($dec -eq 'ADAPTIVE' -and -not $legacy -and -not ($pr['entity_data'] -eq 'PASS' -and $pr['position'] -eq 'PASS' -and $pr['selector_chosen'] -and $pr['selector_chosen'] -ne 'NONE')) { Result $id $cls 'FAIL' 'INCONSISTENT_EVIDENCE: ADAPTIVE without confirmed entity data, position and a chosen selector kind' $evd }
        elseif ($dec -eq 'OWNER_GATED' -and -not $legacy -and $pr['entity_data'] -eq 'PASS' -and $pr['position'] -eq 'PASS' -and $pr['selector_chosen'] -and $pr['selector_chosen'] -ne 'NONE') { Result $id $cls 'FAIL' 'INCONSISTENT_EVIDENCE: every capability passed but the pack chose OWNER_GATED' $evd }
        else { Result $id $cls 'PASS' "probe completed, mode=$dec; $capTxt; independent M03 snapshot places the NPC at the spawn block (distance $(F1 $d0))" $evd }
      }
      'nav' {
        if ($selReached -and $d -gt ($tol + 1.0)) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: in-game selector said REACHED but the M03 snapshot is $(F1 $d) blocks from the target" $evd; continue }
        if ($adaptive -and $selOther -and $d -le $tol) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: in-game observation said $($obs -join ',') but the M03 snapshot is at the target" $evd; continue }
        if ($goalIds.Count -gt 1) { Result $id $cls 'FAIL' "more than one goal in one scenario ($($goalIds.Count)): unexpected second goal/replan" $evd; continue }
        if ($goalIds.Count -eq 0) { Result $id $cls 'PENDING' 'no goal id evidenced (no journal in the snapshots, no C2 result line)' $evd; continue }
        if ($terms.Count -gt 1) { Result $id $cls 'FAIL' "terminal result logged $($terms.Count) times for one goal (exactly-once violated)" $evd; continue }
        if ($d -le $tol) {
            if ($completed.Count -eq 1 -and $once) {
                $why = "M03 final position $(F1 $d) blocks from the target; $ids; exactly one terminal line COMPLETED"
                if ($adaptive) { $why += $(if ($selReached) { '; in-game selector agrees' } else { '; in-game observation absent' }) } else { $why += '; owner-gated mode (no in-game selector evidence; progression through the owner gate)' }
                Result $id $cls 'PASS' $why $evd
            } elseif ($terms.Count -eq 0) { Result $id $cls 'PENDING' "NPC at the target ($(F1 $d)) but no C2 terminal line was logged (completion not evidenced); $ids" $evd }
            else { Result $id $cls 'FAIL' "NPC at the target but the terminal result is $($terms[0].result), not COMPLETED; $ids" $evd }
        } else {
            if ($terms.Count -eq 1) { Result $id $cls 'FAIL' "target not reached ($(F1 $d) blocks away); terminal $($terms[0].result) $($terms[0].detail); $ids" $evd }
            else { Result $id $cls 'FAIL' "window elapsed without terminal result and without reaching the target ($(F1 $d) blocks away): possible hang; $ids" $evd }
        }
      }
      'noreach' {
        if ($goalIds.Count -ne 1) { Result $id $cls 'PENDING' "expected exactly one goal, found $($goalIds.Count)" $evd }
        elseif ($d -le 3.0) { Result $id $cls 'PENDING' 'NPC reached the target: the wall was bypassed, so the obstacle did not force a bounded failure' $evd }
        elseif ($completed.Count -ge 1) { Result $id $cls 'FAIL' 'INCONSISTENT_EVIDENCE: goal COMPLETED although the NPC stopped short of the target' $evd }
        elseif ($failedT.Count -eq 1 -and $once) { Result $id $cls 'UNVERIFIED' "bounded terminal observed (NPC stopped $(F1 $d) blocks short, exactly one FAILED line) but the failure REASON (LONG_NO_PROGRESS vs OSCILLATION vs other) is not logged by production code; $ids" $evd }
        elseif ($terms.Count -eq 0) { Result $id $cls 'FAIL' 'no terminal result before the window ended (possible hang)' $evd }
        else { Result $id $cls 'PENDING' "terminal lines: $($terms.Count)" $evd }
      }
      { $_ -in 'cancel9', 'cancel10' } {
        $note = @($rm | Where-Object { $_.kind -eq 'NOTE' -and $_.id -eq 'cancel' -and $_.t -ge $b.t -and $_.t -le $hi }) | Select-Object -First 1
        if (-not $note) { Result $id $cls 'PENDING' 'cancel was never issued by the data pack (trigger not met / window ended)' $evd; break }
        $atC = @($ss | Where-Object { $_.state -and [math]::Abs(($_.t - $note.t).TotalSeconds) -le 1.5 } | Sort-Object { [math]::Abs(($_.t - $note.t).TotalSeconds) }) | Select-Object -First 1
        $post = @($ss | Where-Object { $_.t -ge $note.t.AddSeconds(6) })
        $evd['journal_state_at_cancel'] = $(if ($atC) { $atC.state } else { $null }); $evd['post_cancel_snapshots'] = $post.Count; $evd['post_cancel_spread'] = (F1 (Spread $post))
        $want = $(if ($type -eq 'cancel9') { 'MOVING' } else { 'REPLANNING' })
        if (-not $atC) { Result $id $cls 'PENDING' 'no journal state captured at the cancel instant' $evd }
        elseif ($obs -contains 'MOVED_AFTER_CANCEL') { Result $id $cls 'FAIL' 'NPC moved again after cancel (in-game observation)' $evd }
        elseif ($completed.Count -ge 1) { Result $id $cls 'FAIL' 'goal COMPLETED although it was cancelled' $evd }
        elseif ($post.Count -lt 2) { Result $id $cls 'PENDING' "only $($post.Count) snapshot(s) >= 6 s after the cancel: stopped-and-stayed-stopped needs at least two" $evd }
        elseif ((Spread $post) -gt 0.75) { Result $id $cls 'FAIL' "NPC kept moving after cancel (spread $(F1 (Spread $post)) blocks across post-cancel snapshots)" $evd }
        elseif ($d -le $tol) { Result $id $cls 'PENDING' 'NPC reached the target before the cancel took effect (cancel too late)' $evd }
        elseif ($atC.state -ne $want) { Result $id $cls 'PENDING' "cancel landed in journal state '$($atC.state)', wanted '$want'$(if ($type -eq 'cancel10') { ' (re-run only/p10_cancel_quiescing, wait 3 min)' })" $evd }
        else { Result $id $cls 'PASS' "cancel issued in journal state $want (snapshot in the same tick); NPC stopped and stayed stopped (post-cancel spread $(F1 (Spread $post))); no COMPLETED result; $ids" $evd }
      }
      'checkpoint' {
        $cp = @($rm | Where-Object { $_.kind -eq 'CHECKPOINT' -and $_.t -ge $b.t }) | Select-Object -First 1
        if (-not $cp) { Result $id $cls 'PENDING' "checkpoint not reached ($($obs -join ','))" $evd; break }
        $atC = @($ss | Where-Object { $_.state -and [math]::Abs(($_.t - $cp.t).TotalSeconds) -le 1.5 }) | Select-Object -First 1
        if ($atC -and $atC.state -in 'MOVING', 'REPLANNING', 'DECIDED') { Result $id $cls 'PASS' "harness reached the Save&Quit checkpoint; M03 snapshot shows journal state $($atC.state) at x=$($atC.x) (the quit itself is MANUAL; judged by p12); $ids" $evd }
        else { Result $id $cls 'PENDING' 'checkpoint reached but no snapshot proves the NPC was mid-goal at that instant' $evd }
      }
      'rejoin' {
        $last = $final; $sp = Spread $ss
        $evd['spread'] = (F1 $sp); $evd['last_state'] = $(if ($last) { $last.state } else { $null })
        $stateOk = (-not $last.state) -or ($last.state -in 'CANCEL_PENDING', 'CANCELLED', 'COMPLETED', 'FAILED', 'INTERRUPTED', 'NONE')
        if ($obs -contains 'MOVED_AFTER_REJOIN' -or ($ss.Count -ge 2 -and $sp -gt 0.75)) { Result $id $cls 'FAIL' "NPC moved after rejoin (spread $(F1 $sp)): old goal replayed" $evd }
        elseif ($ss.Count -lt 2) { Result $id $cls 'PENDING' 'fewer than two post-rejoin snapshots' $evd }
        elseif ($stateOk) { Result $id $cls 'PASS' "no movement across $($ss.Count) post-rejoin snapshots (spread $(F1 $sp)); journal state '$($last.state)' (fenced/terminal/no active goal)" $evd }
        else { Result $id $cls 'PENDING' "no movement after rejoin but journal state is '$($last.state)' (reconciliation not evidenced)" $evd }
      }
      'follow1' {
        if ($selReached -and $d -gt 8) { Result $id $cls 'FAIL' 'INCONSISTENT_EVIDENCE: in-game selector said REACHED but the M03 snapshot disagrees' $evd }
        elseif ($d -le $tol) { Result $id $cls 'PASS' "M03 position is $(F1 $d) blocks from the operator (follow works)$(if ($adaptive) { '; selector agrees' } else { '; owner-gated mode' })" $evd }
        else { Result $id $cls 'FAIL' "NPC is $(F1 $d) blocks from the operator after the follow window" $evd }
      }
      'follow2' {
        $last2 = @($ss | Select-Object -Last 2); $sp = Spread $last2
        if ($final.x -lt 15) { Result $id $cls 'FAIL' "NPC followed the operator away after unfollow (x=$($final.x))" $evd }
        elseif ($sp -gt 0.75) { Result $id $cls 'FAIL' "NPC still moving after unfollow (spread $(F1 $sp))" $evd }
        else { Result $id $cls 'PASS' "NPC stationary near x=$($final.x) while the operator is 90 blocks away (spread $(F1 $sp))" $evd }
      }
      'e3' {
        $first = $ss | Select-Object -First 1; $disp = [math]::Sqrt(([double]$final.x - $first.x) * ([double]$final.x - $first.x) + ([double]$final.z - $first.z) * ([double]$final.z - $first.z)); $sp = Spread @($ss | Select-Object -Last 2)
        $sum = Join-Path $stage 'backend/db-summary.json'
        if (Test-Path $sum) { $j = Get-Content -Raw $sum | ConvertFrom-Json; if ($j.recent.cplus_e3_g2_moves) { $evd['latest_e3_g2_rows'] = @($j.recent.cplus_e3_g2_moves | Select-Object -First 3 | ForEach-Object { ($_ | ConvertTo-Json -Compress) }) } }
        if ($disp -ge 1.0 -and $sp -le 0.75) { Result $id $cls 'PENDING' "E3 guarded move displaced the NPC by $(F1 $disp) blocks and it stopped; terminal/ACK acceptance needs the manual G3 finalize step and DB review" $evd }
        else { Result $id $cls 'FAIL' "E3 move not evidenced (displacement $(F1 $disp), final spread $(F1 $sp))" $evd }
      }
      'multi' {
        $n = [int]($id -replace '^p\d+_multi_(\d+)_npc$', '$1')
        $perfRow = $perf | Where-Object { $_.npc_count -eq $n } | Select-Object -Last 1
        if ($perfRow) { $evd['performance'] = [ordered]@{ status = $perfRow.status; samples = $perfRow.samples; tick_event_mean_ms = $perfRow.tick_event_mean_ms; tick_event_p95_ms = $perfRow.tick_event_p95_ms; tick_event_max_ms = $perfRow.tick_event_max_ms; server_average_tick_ms = $perfRow.server_average_tick_ms; baseline = 'PENDING_NO_G0_BASELINE' } }
        $perNpc = [ordered]@{}; $reachedN = 0
        for ($k = 0; $k -lt $n; $k++) {
            $nm = $laneNames[$k]; $f = @($w.snaps | Where-Object { $_.name -eq $nm }) | Select-Object -Last 1
            $dd = Dist $f 42.5 (8 * $k + 2.5); $perNpc[$nm] = $(if ($f) { F1 $dd } else { 'no snapshot' }); if ($f -and $dd -le $tol) { $reachedN++ }
        }
        $evd['distance_to_target_by_npc'] = $perNpc
        $completedN = $completed.Count; $evd['completed_goals'] = $completedN
        $dupGoals = @($terms | Group-Object goal | Where-Object { $_.Count -gt 1 })
        if ($obs -contains 'ALL_REACHED' -and $reachedN -lt $n) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: selector said ALL_REACHED but only $reachedN of $n NPCs are at their targets in the M03 snapshots" $evd }
        elseif ($dupGoals.Count) { Result $id $cls 'FAIL' 'a goal logged more than one terminal result (exactly-once violated)' $evd }
        elseif ($reachedN -lt $n) { Result $id $cls 'FAIL' "only $reachedN of $n NPCs reached their targets (M03 snapshots): $($perNpc | ConvertTo-Json -Compress)" $evd }
        elseif ($completedN -ge $n -and $perfRow -and $perfRow.status -ne 'HOLD') { Result $id $cls 'PASS' "all $n NPCs at their targets per M03 snapshots, $completedN goals COMPLETED exactly once; tick samples recorded (comparison to a baseline is PENDING)" $evd }
        else { Result $id $cls 'PENDING' "all $n NPCs at their targets but completions=$completedN and/or performance record missing/HOLD" $evd }
      }
    }
}
$count = @{}; foreach ($r in $results) { $count[$r.status] = 1 + [int]$count[$r.status] }
$unobservable = @('LongState/leg counts (c2 status is chat-only; transitions only inside M03 measurement windows)', 'LONG_NO_PROGRESS vs OSCILLATION reason text (not logged)', 'FIFO wait distribution (not logged)', 'segment-start capture cost (no timing log)', 'real MSPT percentiles unless /debug start|stop output is supplied', 'dedicated-server and fault-injected shutdown (not exercised)')
$summary = [ordered]@{ run_id = "G5-$stamp"; collected_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'; world = $WorldName; datapack = $packMatches; counts = $count; mode = $(if ($runStart) { ModeAt $endBound } else { 'UNKNOWN' }); probe_capabilities = $script:probeCaps; owner_gate = $gateStats; correlation = $correlation; scenarios = $results; unobservable = $unobservable; notes = $notes; note_on_status = 'PASS requires the independent M03 snapshot position AND exactly one logged C2 terminal line for the scenario goal; a dispatched command or a data-pack marker is never a PASS. A scenario that awaited the owner gate but was never released is PENDING; one that ended without a recorded release is a FAIL (timer-only progression).' }
[IO.File]::WriteAllText((Join-Path $stage 'summary.json'), ($summary | ConvertTo-Json -Depth 8), $utf8)

# ---------------------------------------------------------------- environment, report, manifest
$javaVer = try { (& cmd.exe /c 'java -version 2>&1') -join ' ' } catch { 'unavailable' }
$opts = @{}; $of = Join-Path $RunDir 'options.txt'; if (Test-Path $of) { Get-Content $of | Where-Object { $_ -match '^(renderDistance|simulationDistance):' } | ForEach-Object { $k, $v = $_ -split ':', 2; $opts[$k] = $v } }
$forgeLine = ($lines | Where-Object { $_.msg -match 'Forge|Minecraft Version' } | Select-Object -First 1).msg
$srcHashes = [ordered]@{}; foreach ($f in 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2AgentRuntime.java', 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2LongGoalPolicy.java', 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2PlanQueue.java') { $p = Join-Path $root $f; if (Test-Path $p) { $srcHashes[$f] = (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash } }
$env = [ordered]@{ run_id = "G5-$stamp"; os = [Environment]::OSVersion.VersionString; powershell = $PSVersionTable.PSVersion.ToString(); java = $javaVer; minecraft_forge = $forgeLine; world = $WorldName; options = $opts; datapack_state = $packMatches; installed_datapacks = @($dpInfo.Keys); database = $dbInfo; backend_health = $health; lookback_hours = $LookbackHours; source_hashes = $srcHashes; log_files = @($logFiles | ForEach-Object { $_.Name }) }
[IO.File]::WriteAllText((Join-Path $stage 'meta/environment.json'), ($env | ConvertTo-Json -Depth 5), $utf8)
$rep = @("# NPCortex G5 test report $stamp", '', "World: $WorldName | datapack: $packMatches | database: $dbInfo", '', '| Scenario | Class | Status | Reason |', '|---|---|---|---|')
foreach ($r in $results) { $rep += "| $($r.id) | $($r.class) | **$($r.status)** | $($r.reason -replace '\|', '/') |" }
$rep += ''; $rep += 'Correlation (scenario / goal id / action id / journal states / terminal lines):'; $rep += ''; $rep += '| Scenario | Mode | Goal | Action | Journal states | Terminal | Owner gate |'; $rep += '|---|---|---|---|---|---|---|'
foreach ($c in $correlation) { $rep += "| $($c.scenario) | $($c.mode) | $($c.goal) | $($c.action) | $($c.states) | $($c.terminal) | $($c.gate) |" }
if ($script:probeCaps) { $rep += ''; $rep += 'p00 probe capabilities (PASS / FAIL / NOT_ATTEMPTED; NOT_ATTEMPTED is never a failure):'; $rep += ''; foreach ($k in $script:probeCaps.Keys) { $rep += "- $k = $($script:probeCaps[$k])" } }
$rep += ''; $rep += "Owner gate: awaited=$($gateStats.awaited) released=$($gateStats.released) with recorded GO=$($gateStats.released_with_recorded_go) without=$($gateStats.released_without_recorded_go) never released=$($gateStats.awaiting_never_released)"
$rep += ''; $rep += 'Counts: ' + (($count.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', '); $rep += ''
$rep += 'Not observable with the existing production logging:'; $unobservable | ForEach-Object { $rep += "- $_" }
if ($notes.Count) { $rep += ''; $rep += 'Collector notes:'; $notes | ForEach-Object { $rep += "- $_" } }
[IO.File]::WriteAllLines((Join-Path $stage 'REPORT.md'), $rep, $utf8)
$man = Get-ChildItem -Recurse -File -LiteralPath $stage | Where-Object { $_.Name -ne 'manifest.json' } | ForEach-Object { [ordered]@{ path = $_.FullName.Substring($stage.Length + 1).Replace('\', '/'); bytes = $_.Length; sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash } }
[IO.File]::WriteAllText((Join-Path $stage 'manifest.json'), (@{ run_id = "G5-$stamp"; files = $man } | ConvertTo-Json -Depth 4), $utf8)

# ---------------------------------------------------------------- ZIP (forward-slash entries)
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zipPath = Join-Path $OutDir "NPCortex-G5-TestResults-$stamp.zip"
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
try { foreach ($f in Get-ChildItem -Recurse -File -LiteralPath $stage) { $name = $f.FullName.Substring($stage.Length + 1).Replace('\', '/'); [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $name, [IO.Compression.CompressionLevel]::Optimal) } } finally { $zip.Dispose() }
Remove-Item -LiteralPath $stage -Recurse -Force
"Evidence ZIP: $zipPath"
($count.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join '  '
