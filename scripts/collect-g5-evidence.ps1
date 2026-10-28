# NPCortex Roadmap #2 G5 evidence collector (TEST ONLY, read-only).
# Copies (never moves/deletes) logs, M03 evidence snapshots, outbox records, a SQLite backup and profiler output into a staging
# folder, redacts secrets from text, analyses the [G5] markers against the C2 log lines and writes a machine-readable verdict.
# Verdicts are PASS / FAIL / BLOCKED / PENDING / UNVERIFIED. A Minecraft command being dispatched is never evidence.
# Revision 5: it REFUSES to run unless the backend database is proven isolated (g5-results/run-<id> marker + passing preflight): there is no
# default database any more. Lanes are verified one by one (C1 identity, profile binding, goal, action, terminal result, M03 evidence).
# Revision 6 (collector version below): live logs are read with FileShare.ReadWrite|Delete (an unfinished last line is dropped, never guessed), relative paths are
# resolved against the PowerShell location, the isolated backend logs / health-at-stop are discovered from backend-run.json, the gate log is run-scoped,
# p11 picks the checkpoint-correlated snapshot, p08/p10 use the two production diagnostics (D1: `NPCortex C2 diag result|cancel ...`), p18 needs the typed
# taskmovefinalize/taskmovestatus responses plus the durable database terminal, and first-sample-tainted performance records are flagged. Rev5 reports are never rewritten.
param(
    [Parameter(Mandatory = $true)][string]$WorldName,
    [string]$RunDir,
    [string]$G5RunDir,
    [string]$Database,
    [string]$BackendLog,
    [string]$BackendUrl = 'http://127.0.0.1:8765',
    [int]$LookbackHours = 12,
    [string]$OutDir
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'g5-common.ps1')
$CollectorVersion = 'rev6.0'
if (-not $RunDir)   { $RunDir   = Join-Path $root 'forge-mod/run' }
if (-not $OutDir)   { $OutDir   = Join-Path $root 'g5-results' }
# every user-supplied path is made absolute against the PowerShell location (never against the process directory, e.g. C:\Windows\system32)
foreach ($pn in 'RunDir', 'OutDir', 'G5RunDir', 'Database', 'BackendLog') { $pv = Get-Variable -Name $pn -ValueOnly; if ($pv) { Set-Variable -Name $pn -Value (Resolve-G5Path $pv) } }
# ---- fail-closed isolation proof: nothing is read, copied or written before this passes
$g5Dir = Resolve-G5RunDir $G5RunDir $OutDir
$iso = Test-G5Isolation -G5RunDir $g5Dir -Database $Database -RepoRoot $root
if (-not $iso.ok) { throw ("G5 evidence collection refused: the test database cannot be verified as isolated. " + (($iso.checks | Where-Object { -not $_.ok } | ForEach-Object { $_.name + ': ' + $_.detail }) -join ' | ')) }
$Database = $iso.db
$pre = Read-G5Json (Join-Path $g5Dir 'preflight.json')
if (-not $pre -or $pre.status -ne 'PASS' -or [string]$pre.run_id -ne [string]$iso.marker.run_id) { throw 'G5 evidence collection refused: no passing preflight (scripts/g5-preflight.ps1) is recorded for this run.' }
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
function CopyRedacted([string]$src, [string]$dst) {   # read-only on the source; shared read, so a file a running process keeps open still works
    $r = Read-G5Shared $src
    [IO.File]::WriteAllText($dst, (Redact $r.text), $utf8)
}
$evidenceGaps = New-Object System.Collections.ArrayList   # mandatory evidence that could not be collected: reported, never silently dropped

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
    } else {
        # latest.log is open for writing while Minecraft runs: shared read; an unfinished last line is dropped (and recorded), not parsed
        try { $r = Read-G5Shared $f.FullName -DropPartialLine } catch { [void]$evidenceGaps.Add("MANDATORY log $($f.Name) could not be read: $($_.Exception.Message)"); throw "mandatory Minecraft log $($f.Name) could not be read (shared read failed): $($_.Exception.Message)" }
        $text = $r.text; $out = Join-Path $stage ('logs/' + $f.Name)
        if ($r.partial_line_dropped) { Note "$($f.Name): an unfinished last line (still being written) was not analysed" }
    }
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

$diagCancel = New-Object System.Collections.ArrayList; $diagResult = New-Object System.Collections.ArrayList; $finalizeLines = New-Object System.Collections.ArrayList; $markers = New-Object System.Collections.ArrayList; $c2res = New-Object System.Collections.ArrayList; $c2int = New-Object System.Collections.ArrayList; $npcErr = New-Object System.Collections.ArrayList; $statusLines = New-Object System.Collections.ArrayList
foreach ($l in $lines) {
    # each [G5] 'say' is echoed by the client chat on the Render thread: count only the server-side line (otherwise every marker is doubled)
    if ($l.thr -notlike 'Server thread*') {
        # chat answers of commands the OWNER typed (command feedback from data-pack functions is suppressed by vanilla and never appears here)
        $fm = [regex]::Match($l.msg, '\[CHAT\] (?:\[[^\]]+\] )*(?:NPCortex: )?G3 (COMPLETED|CANCELLED) task=(\S+) durable ACK; actor fence released')
        if ($fm.Success) { [void]$finalizeLines.Add([pscustomobject]@{ t = $l.t; kind = 'FINALIZE'; outcome = $fm.Groups[1].Value; task = $fm.Groups[2].Value; text = (Redact $l.msg) }) }
        $stm = [regex]::Match($l.msg, '\[CHAT\] (?:\[[^\]]+\] )*(?:NPCortex: )?E3 G2=(\w+);')
        if ($stm.Success) { [void]$finalizeLines.Add([pscustomobject]@{ t = $l.t; kind = 'STATUS'; outcome = $stm.Groups[1].Value; task = $null; text = (Redact $l.msg) }) }
        # `c2 status <npc>` answers in chat (client Render thread echo): the only place the production LongState is visible (independent state evidence)
        $sm = [regex]::Match($l.msg, '\[CHAT\] (?:\[[^\]]+\] )*(\w+) C2=(\w+)(.*)$')
        if ($sm.Success) { $lg = [regex]::Match($sm.Groups[3].Value, 'long=(\w+)'); [void]$statusLines.Add([pscustomobject]@{ t = $l.t; npc = $sm.Groups[1].Value; c2 = $sm.Groups[2].Value; long = $(if ($lg.Success) { $lg.Groups[1].Value } else { $null }); text = (Redact $sm.Value) }) }
        if ($l.msg -match 'NPCortex' -and $l.msg -match 'ERROR|Exception|rejected|HOLD') { [void]$npcErr.Add([pscustomobject]@{ t = $l.t; msg = (Redact $l.msg) }) }; continue }
    $m = [regex]::Match($l.msg, '\[G5\] (\w+)\s*(\S*)\s*(.*)$')
    if ($m.Success) { [void]$markers.Add([pscustomobject]@{ i = $markers.Count; t = $l.t; kind = $m.Groups[1].Value; id = $m.Groups[2].Value; rest = $m.Groups[3].Value }); continue }
    # Rev6 production diagnostics D1 (log only): state at the cancel() invocation and the exact result() reason
    $m = [regex]::Match($l.msg, 'NPCortex C2 diag cancel actor=(\S+) goal=(\S+) phase=(.*?) long=(\S+)\s*$')
    if ($m.Success) { [void]$diagCancel.Add([pscustomobject]@{ t = $l.t; actor = $m.Groups[1].Value; goal = $m.Groups[2].Value; phase = $m.Groups[3].Value; long = $m.Groups[4].Value }); continue }
    $m = [regex]::Match($l.msg, 'NPCortex C2 diag result actor=(\S+) goal=(\S+) succeeded=(\w+) phase=(.*?) long=(\S+) reason=(.*)$')
    if ($m.Success) { [void]$diagResult.Add([pscustomobject]@{ t = $l.t; actor = $m.Groups[1].Value; goal = $m.Groups[2].Value; succeeded = ($m.Groups[3].Value -eq 'true'); phase = $m.Groups[4].Value; long = $m.Groups[5].Value; reason = $m.Groups[6].Value }); continue }
    $m = [regex]::Match($l.msg, 'NPCortex C2 goal=(\S+) actor=(\S+) result=(\w+) detail=(.*)$')
    if ($m.Success) { [void]$c2res.Add([pscustomobject]@{ t = $l.t; goal = $m.Groups[1].Value; actor = $m.Groups[2].Value; result = $m.Groups[3].Value; detail = $m.Groups[4].Value }); continue }
    $m = [regex]::Match($l.msg, 'NPCortex C2 interrupted goal=(\S+) actor=(\S+) pendingReconciliation=(\w+) reason=(.*)$')
    if ($m.Success) { [void]$c2int.Add([pscustomobject]@{ t = $l.t; goal = $m.Groups[1].Value; actor = $m.Groups[2].Value; reason = $m.Groups[4].Value }); continue }
    if ($l.msg -match 'NPCortex' -and $l.msg -match 'ERROR|Exception|rejected|HOLD') { [void]$npcErr.Add([pscustomobject]@{ t = $l.t; msg = (Redact $l.msg) }) }
}
$logExtract = @("# G5 markers"); $markers | ForEach-Object { $logExtract += ('{0:HH:mm:ss} {1} {2} {3}' -f $_.t, $_.kind, $_.id, $_.rest) }
$logExtract += "# C2 results"; $c2res | ForEach-Object { $logExtract += ('{0:HH:mm:ss} goal={1} result={2} detail={3}' -f $_.t, $_.goal, $_.result, $_.detail) }
$logExtract += "# C2 interrupted"; $c2int | ForEach-Object { $logExtract += ('{0:HH:mm:ss} goal={1} reason={2}' -f $_.t, $_.goal, $_.reason) }
$logExtract += "# D1 diag cancel (state at cancel() invocation)"; $diagCancel | ForEach-Object { $logExtract += ('{0:HH:mm:ss.fff} actor={1} goal={2} phase={3} long={4}' -f $_.t, $_.actor, $_.goal, $_.phase, $_.long) }
$logExtract += "# D1 diag result (exact reason)"; $diagResult | ForEach-Object { $logExtract += ('{0:HH:mm:ss.fff} actor={1} goal={2} succeeded={3} phase={4} long={5} reason={6}' -f $_.t, $_.actor, $_.goal, $_.succeeded, $_.phase, $_.long, $_.reason) }
$logExtract += "# typed taskmovefinalize / taskmovestatus chat responses"; $finalizeLines | ForEach-Object { $logExtract += ('{0:HH:mm:ss.fff} {1} {2} {3}' -f $_.t, $_.kind, $_.outcome, $_.task) }
$logExtract += "# c2 status chat samples"; $statusLines | ForEach-Object { $logExtract += ('{0:HH:mm:ss.fff} {1}' -f $_.t, $_.text) }
$logExtract += "# NPCortex warnings/errors"; $npcErr | ForEach-Object { $logExtract += ('{0:HH:mm:ss} {1}' -f $_.t, $_.msg) }
[IO.File]::WriteAllLines((Join-Path $stage 'logs/g5-log-extract.txt'), $logExtract, $utf8)

# ---------------------------------------------------------------- world evidence (copies only)
$snaps = New-Object System.Collections.ArrayList; $perf = New-Object System.Collections.ArrayList
$ev = Join-Path $world 'data/npcortex/m03-evidence'
if (Test-Path -LiteralPath $ev) {
    Get-ChildItem -LiteralPath $ev -Filter *.json -File | Where-Object { $_.LastWriteTime -ge $cutoff } | ForEach-Object {
        CopyRedacted $_.FullName (Join-Path $stage ('world/m03-evidence/' + $_.Name))
        try { $o = (Read-G5Shared $_.FullName).text | ConvertFrom-Json } catch { Note "unreadable evidence $($_.Name)"; [void]$evidenceGaps.Add("M03 evidence $($_.Name) unreadable"); return }
        if ($o.kind -eq 'performance') { $o | Add-Member -NotePropertyName local -NotePropertyValue $_.LastWriteTime -Force; [void]$perf.Add($o) }
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
if 'actors' in tables:
    info['actors'] = [dict(r) for r in c.execute('select actor_id, character_id, dimension_id from actors order by actor_id limit 500')]
if 'character_profiles' in tables:
    info['profiles'] = [dict(r) for r in c.execute('select p.actor_id, p.revision, p.source, p.display_name, p.composition, p.content_sha256 from character_profiles p where p.revision = (select max(revision) from character_profiles q where q.actor_id = p.actor_id) order by p.actor_id limit 500')]
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
# health BEFORE shutdown (start-g5-backend.ps1 -Stop records it): post-shutdown UNREACHABLE is expected and is not a failure during gameplay
$stopRec = Read-G5Json (Join-Path $g5Dir 'backend-stop.json')
$healthState = $(if ($health -ne 'UNREACHABLE') { 'OK_AT_COLLECTION' } elseif ($stopRec) { $(if ($stopRec.health_before_stop -and $stopRec.health_before_stop -ne 'UNREACHABLE') { 'HEALTHY_BEFORE_STOP_UNREACHABLE_AFTER_SHUTDOWN (expected)' } else { 'UNREACHABLE_ALSO_BEFORE_STOP' }) } else { 'UNREACHABLE_BACKEND_NOT_STOPPED_BY_LAUNCHER' })
if ($stopRec) { [IO.File]::WriteAllText((Join-Path $stage 'backend/health-before-stop.json'), (@{ stopped_utc = $stopRec.stopped_utc; health_before_stop = $stopRec.health_before_stop } | ConvertTo-Json), $utf8) }
if ($healthState -like 'UNREACHABLE*') { Note "backend health: $healthState" }
# backend console logs: an explicit -BackendLog wins; otherwise the logs recorded by the verified launcher metadata (backend-run.json) of THIS isolated run
$bRec = Read-G5Json (Join-Path $g5Dir 'backend-run.json')
$backendLogs = [ordered]@{}
if ($BackendLog) { $backendLogs['backend.log'] = $BackendLog }
elseif ($bRec) {
    foreach ($kv in @(@('backend.err.log', $bRec.err_log), @('backend.out.log', $bRec.out_log))) {
        $lp = [string]$kv[1]
        # only logs INSIDE this run folder are trusted (the record must not point anywhere else)
        if ($lp -and ((Resolve-G5Path $lp).StartsWith($g5Dir.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase))) { $backendLogs[$kv[0]] = $lp }
    }
}
$backendLogStats = [ordered]@{}
foreach ($k in $backendLogs.Keys) {
    $bp = $backendLogs[$k]
    if (Test-Path -LiteralPath $bp) {
        try {
            $br = Read-G5Shared $bp -DropPartialLine
            [IO.File]::WriteAllText((Join-Path $stage ('backend/' + $k)), (Redact $br.text), $utf8)
            $bl = @($br.text -split "`r?`n" | Where-Object { $_ })
            $backendLogStats[$k] = [ordered]@{ lines = $bl.Count; http_4xx_5xx = @($bl | Where-Object { $_ -match '"\s[45]\d\d\s' }).Count; tracebacks = @($bl | Where-Object { $_ -match 'Traceback' }).Count }
        } catch { [void]$evidenceGaps.Add("backend log $k could not be read: $($_.Exception.Message)"); Note "backend log $k could not be read ($($_.Exception.Message))" }
    } else { [void]$evidenceGaps.Add("backend log $k not found: $bp") }
}
if (-not $backendLogs.Count) { Note 'backend log not located (no -BackendLog and no backend-run.json): backend console output is UNVERIFIED' }

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
    [pscustomobject]@{ t = $_.local; name = $_.name; x = $_.x; z = $_.z; tick = $_.capture_tick; profile = $_.profile_id; actor = $_.actor_id
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
$gateRows = @(); $gateLogPath = Join-Path $g5Dir 'gate-log.jsonl'
if (-not (Test-Path -LiteralPath $gateLogPath)) { $gateLogPath = Join-Path $OutDir 'g5-gate-log.jsonl' }
$gateIgnored = 0
if (Test-Path -LiteralPath $gateLogPath) {
    $kept = @()
    foreach ($gl in ((Read-G5Shared $gateLogPath -DropPartialLine).text -split "`r?`n")) {
        if (-not $gl.Trim()) { continue }
        try { $r = $gl | ConvertFrom-Json; $r | Add-Member -NotePropertyName tt -NotePropertyValue ([datetime]::Parse($r.time)) -Force } catch { continue }
        # Rev6: only rows written for THIS run count (run id from the gate script; rows of older runs or without a run id are ignored and counted)
        if ([string]$r.run_id -eq [string]$iso.marker.run_id -and ($null -eq $runStart -or $r.tt -ge $runStart.t.AddSeconds(-5))) { $gateRows += $r; $kept += $gl } else { $gateIgnored++ }
    }
    [IO.File]::WriteAllText((Join-Path $stage 'gate-log.jsonl'), (($kept -join "`n") + $(if ($kept.Count) { "`n" } else { '' })), $utf8)
    if ($gateIgnored) { Note "$gateIgnored gate-log row(s) not attributed to this run (older run or no run id) were ignored" }
} else { Note 'no g5-gate-log.jsonl: owner-gate decisions of scripts/g5-gate.ps1 are not recorded (only matters when a scenario awaited the gate)' }
$results = New-Object System.Collections.ArrayList
$correlation = New-Object System.Collections.ArrayList
$laneTable = New-Object System.Collections.ArrayList
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
        # Rev6: the exact failure reason comes from the production diagnostic D1-A (`NPCortex C2 diag result ... reason=`), never from the path shape
        $drs = @($diagResult | Where-Object { ($goalIds -contains $_.goal) -and $_.t -ge $b.t -and $_.t -lt $hi.AddSeconds(2) })
        $evd['diag_result_lines'] = $drs.Count
        if ($drs.Count) { $evd['failure_reason'] = $drs[0].reason; $evd['failure_phase'] = $drs[0].phase; $evd['failure_long_state'] = $drs[0].long }
        if ($goalIds.Count -ne 1) { Result $id $cls 'PENDING' "expected exactly one goal, found $($goalIds.Count)" $evd }
        elseif ($d -le 3.0) { Result $id $cls 'PENDING' 'NPC reached the target: the wall was bypassed, so the obstacle did not force a bounded failure' $evd }
        elseif ($completed.Count -ge 1) { Result $id $cls 'FAIL' 'INCONSISTENT_EVIDENCE: goal COMPLETED although the NPC stopped short of the target' $evd }
        elseif ($terms.Count -eq 0) { Result $id $cls 'FAIL' 'no terminal result before the window ended (possible hang)' $evd }
        elseif (-not ($failedT.Count -eq 1 -and $once)) { Result $id $cls 'PENDING' "terminal lines: $($terms.Count)" $evd }
        elseif ($drs.Count -eq 0) { Result $id $cls 'UNVERIFIED' "bounded terminal observed (NPC stopped $(F1 $d) blocks short, exactly one FAILED line) but no D1 diagnostic line `NPCortex C2 diag result` exists for this goal, so the failure REASON is not evidenced (build without D1, or the log was cut); $ids" $evd }
        elseif ($drs.Count -gt 1 -or $drs[0].succeeded -or $drs[0].actor -ne $final.actor -or $drs[0].goal -ne $goalIds[0]) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: the D1 diagnostic does not match the scenario's single failed goal/actor ($($drs.Count) line(s))" $evd }
        elseif ($drs[0].reason -match '^LONG_(NO_PROGRESS|OSCILLATION)\b') { Result $id $cls 'PASS' "bounded failure with the intended reason captured from production: '$($drs[0].reason)' (actor and goal correlated with the journal and the single FAILED terminal; NPC stopped $(F1 $d) blocks short); $ids" $evd }
        else { Result $id $cls 'FAIL' "scenario expectation not met (not by itself a production defect): the production reason is '$($drs[0].reason)', not LONG_NO_PROGRESS/LONG_OSCILLATION; $ids" $evd }
      }
      'cancel9' {
        $note = @($rm | Where-Object { $_.kind -eq 'NOTE' -and $_.id -eq 'cancel' -and $_.t -ge $b.t -and $_.t -le $hi }) | Select-Object -First 1
        if (-not $note) { Result $id $cls 'PENDING' 'cancel was never issued by the data pack (trigger not met / window ended)' $evd; break }
        $atC = @($ss | Where-Object { $_.state -and [math]::Abs(($_.t - $note.t).TotalSeconds) -le 1.5 } | Sort-Object { [math]::Abs(($_.t - $note.t).TotalSeconds) }) | Select-Object -First 1
        $post = @($ss | Where-Object { $_.t -ge $note.t.AddSeconds(6) })
        $evd['journal_state_at_cancel'] = $(if ($atC) { $atC.state } else { $null }); $evd['post_cancel_snapshots'] = $post.Count; $evd['post_cancel_spread'] = (F1 (Spread $post))
        $dcs = @($diagCancel | Where-Object { $atC -and [math]::Abs(($_.t - $note.t).TotalSeconds) -le 1.5 -and $_.goal -eq $atC.goal -and $_.actor -eq $atC.actor })
        if ($dcs.Count -eq 1) { $evd['diag_cancel'] = "phase=$($dcs[0].phase) long=$($dcs[0].long)" }
        $final = $ss | Select-Object -Last 1
        $safety = $null; $safetyWhy = $null
        if (-not $atC) { $safety = 'PENDING'; $safetyWhy = 'no journal state captured at the cancel instant' }
        elseif ($obs -contains 'MOVED_AFTER_CANCEL') { $safety = 'FAIL'; $safetyWhy = 'NPC moved again after cancel (in-game observation)' }
        elseif (@($completed | Where-Object { $_.t -le $note.t.AddSeconds(0.5) }).Count -ge 1) { $safety = 'PENDING'; $safetyWhy = 'the goal COMPLETED at or before the cancel instant: cancellation NOT exercised (cancel too late)' }
        elseif ($completed.Count -ge 1) { $safety = 'FAIL'; $safetyWhy = 'goal COMPLETED after it was cancelled' }
        elseif (Test-G5Terminal $atC.state) { $safety = 'PENDING'; $safetyWhy = "the cancel landed after the goal had already ended (journal $($atC.state)): cancellation NOT exercised" }
        elseif ($post.Count -lt 2) { $safety = 'PENDING'; $safetyWhy = "only $($post.Count) snapshot(s) >= 6 s after the cancel: stopped-and-stayed-stopped needs at least two" }
        elseif ((Spread $post) -gt 0.75) { $safety = 'FAIL'; $safetyWhy = "NPC kept moving after cancel (spread $(F1 (Spread $post)) blocks across post-cancel snapshots)" }
        elseif ($d -le $tol) { $safety = 'PENDING'; $safetyWhy = 'NPC reached the target before the cancel took effect (cancel too late)' }
        elseif ($final.state -eq 'CANCELLED') { $safety = 'PASS'; $safetyWhy = "cancel issued while the goal was active (journal $($atC.state)); NPC stopped and stayed stopped (post-cancel spread $(F1 (Spread $post))); journal ends CANCELLED; no COMPLETED result" }
        elseif ($final.state -in 'CANCEL_PENDING', 'INTERRUPTED') { $safety = 'PENDING'; $safetyWhy = "NPC stopped but the journal is '$($final.state)': reconciliation not evidenced" }
        else { $safety = 'FAIL'; $safetyWhy = "NPC stopped but the final journal state is '$($final.state)', not CANCELLED" }
        $evd['cancellation_safety'] = "$safety : $safetyWhy"
        if ($safety -eq 'PASS' -and $atC.state -ne 'MOVING') { Result $id $cls 'PENDING' "cancellation safety PASS but the cancel landed in journal state '$($atC.state)', wanted 'MOVING'" $evd }
        else { Result $id $cls $safety $(if ($safety -eq 'PASS') { "cancellation safety: $safetyWhy; $ids" } else { $safetyWhy }) $evd }
      }
      'cancel10' {
        # Rev6 p10: up to FIVE attempts (the pack's per-tick trigger); each attempt is judged on its own goal. Coverage needs the production diagnostic D1-B.
        $anotes = @($rm | Where-Object { $_.kind -eq 'NOTE' -and $_.id -eq 'attempt' -and $_.t -ge $b.t -and $_.t -le $hi })
        if (-not $anotes.Count) { Result $id $cls 'PENDING' 'no attempt marker: p10 did not start an attempt' $evd; break }
        if ($anotes.Count -gt 5) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: $($anotes.Count) attempts logged, the contract allows at most 5" $evd; break }
        $rows = @(); $seenAtt = @()
        for ($ai = 0; $ai -lt $anotes.Count; $ai++) {
            $an = $anotes[$ai]; $aEnd = $(if ($ai + 1 -lt $anotes.Count) { $anotes[$ai + 1].t } else { $hi }); $an_n = [int]($an.rest.Trim() -replace '[^0-9].*$', '')
            $aSn = @($ss | Where-Object { $_.t -ge $an.t.AddMilliseconds(-200) -and $_.t -lt $aEnd })
            $aGoals = @($aSn | Where-Object { $_.goal -and ($own -contains $_.goal) -and ($seenAtt -notcontains $_.goal) } | ForEach-Object { $_.goal } | Select-Object -Unique); $seenAtt += $aGoals
            $cns = @($rm | Where-Object { $_.kind -eq 'NOTE' -and $_.id -eq 'cancel' -and $_.rest -match "^attempt\s+$an_n\b" -and $_.t -ge $an.t -and $_.t -lt $aEnd })
            $row = [ordered]@{ attempt = $an_n; goal = ($aGoals -join ','); cancel_at = $null; journal_state_at_cancel = $null; diag_long = $null; diag_phase = $null; safety = 'NOT_EXERCISED'; safety_why = ''; coverage = 'NOT_EXERCISED'; coverage_why = '' }
            if ($cns.Count -gt 1) { $row.safety = 'FAIL'; $row.safety_why = "$($cns.Count) cancel dispatches in one attempt (duplicate dispatch)"; $row.coverage = 'FAIL'; $rows += [pscustomobject]$row; continue }
            if ($cns.Count -eq 0) {
                $aTerm = @($c2run | Where-Object { $aGoals -contains $_.goal })
                $row.safety_why = $(if (@($aTerm | Where-Object { $_.result -eq 'COMPLETED' }).Count) { 'goal COMPLETED without a cancel (trigger never fired): the cancel race was not exercised' } else { 'no cancel dispatched in this attempt (trigger not met / attempt timed out and was cleaned up)' }); $row.coverage_why = $row.safety_why
                $rows += [pscustomobject]$row; continue
            }
            $cn = $cns[0]; $row.cancel_at = $cn.t.ToString('s')
            $atC = @($aSn | Where-Object { $_.state -and [math]::Abs(($_.t - $cn.t).TotalSeconds) -le 1.5 } | Sort-Object { [math]::Abs(($_.t - $cn.t).TotalSeconds) }) | Select-Object -First 1
            $post = @($aSn | Where-Object { $_.t -ge $cn.t.AddSeconds(6) }); $aFinal = $aSn | Select-Object -Last 1
            $aTerm = @($c2run | Where-Object { $aGoals -contains $_.goal }); $aComp = @($aTerm | Where-Object { $_.result -eq 'COMPLETED' })
            $moved = @($rm | Where-Object { $_.kind -eq 'OBS' -and $_.id -eq $id -and $_.rest -like '*MOVED_AFTER_CANCEL*' -and $_.t -ge $cn.t -and $_.t -lt $aEnd }).Count
            $row.journal_state_at_cancel = $(if ($atC) { $atC.state } else { $null })
            $sf = $null; $sw = $null
            if (-not $atC) { $sf = 'PENDING'; $sw = 'no journal state captured at the cancel instant' }
            elseif ($moved) { $sf = 'FAIL'; $sw = 'NPC moved again after cancel (in-game observation)' }
            elseif (@($aComp | Where-Object { $_.t -le $cn.t.AddSeconds(0.5) }).Count -ge 1) { $sf = 'PENDING'; $sw = 'the goal COMPLETED at or before the cancel instant: cancellation NOT exercised (cancel too late)' }
            elseif ($aComp.Count -ge 1) { $sf = 'FAIL'; $sw = 'goal COMPLETED after it was cancelled' }
            elseif (Test-G5Terminal $atC.state) { $sf = 'PENDING'; $sw = "the cancel landed after the goal had already ended (journal $($atC.state))" }
            elseif ($post.Count -lt 2) { $sf = 'PENDING'; $sw = "only $($post.Count) snapshot(s) >= 6 s after the cancel (need 2)" }
            elseif ((Spread $post) -gt 0.75) { $sf = 'FAIL'; $sw = "NPC kept moving after cancel (spread $(F1 (Spread $post)))" }
            elseif ((Dist $aFinal $tx $tz) -le $tol) { $sf = 'PENDING'; $sw = 'NPC reached the target before the cancel took effect' }
            elseif ($aFinal.state -eq 'CANCELLED') { $sf = 'PASS'; $sw = "cancel issued while the goal was active (journal $($atC.state)); NPC stopped and stayed stopped (spread $(F1 (Spread $post))); journal ends CANCELLED; no COMPLETED result" }
            elseif ($aFinal.state -in 'CANCEL_PENDING', 'INTERRUPTED') { $sf = 'PENDING'; $sw = "NPC stopped but the journal is '$($aFinal.state)': reconciliation not evidenced" }
            else { $sf = 'FAIL'; $sw = "NPC stopped but the final journal state is '$($aFinal.state)', not CANCELLED" }
            $row.safety = $sf; $row.safety_why = $sw
            # coverage: the production diagnostic (state at the cancel() invocation) for THIS actor and goal, in the cancel tick
            $dcs = @($diagCancel | Where-Object { [math]::Abs(($_.t - $cn.t).TotalSeconds) -le 1.5 })
            $dc = $null; if ($dcs.Count -eq 1) { $dc = $dcs[0] }
            if ($dcs.Count -eq 0) { $row.coverage = 'UNVERIFIED'; $row.coverage_why = 'no D1-B diagnostic line `NPCortex C2 diag cancel` near the cancel: the longState at the invocation is not evidenced (build without D1, or the log was cut)' }
            elseif ($dcs.Count -gt 1) { $row.coverage = 'UNVERIFIED'; $row.coverage_why = "$($dcs.Count) diagnostic cancel lines near the cancel: the one belonging to this attempt cannot be told apart" }
            else {
                $row.diag_long = $dc.long; $row.diag_phase = $dc.phase
                if (-not $atC -or $dc.goal -ne $atC.goal -or $dc.actor -ne $atC.actor -or ($aGoals -notcontains $dc.goal)) { $row.coverage = 'UNVERIFIED'; $row.coverage_why = 'the diagnostic names another actor/goal than the journal snapshot taken in the cancel tick' }
                elseif ($dc.long -ne 'LEG_QUIESCING') { $row.coverage = 'NOT_COVERED'; $row.coverage_why = "production state at the cancel was long=$($dc.long) phase=$($dc.phase), not LEG_QUIESCING" }
                elseif ($sf -ne 'PASS') { $row.coverage = 'UNVERIFIED'; $row.coverage_why = "long=LEG_QUIESCING was seen but cancellation safety is $sf ($sw)" }
                else { $row.coverage = 'COVERED'; $row.coverage_why = "D1-B recorded long=LEG_QUIESCING (phase=$($dc.phase)) at the cancel() invocation for the same actor and goal; safety PASS; no contradictory terminal or movement evidence" }
            }
            $rows += [pscustomobject]$row
        }
        $evd['attempts'] = $rows
        $evd['attempts_run'] = $rows.Count
        $failedA = @($rows | Where-Object { $_.safety -eq 'FAIL' }); $coveredA = @($rows | Where-Object { $_.coverage -eq 'COVERED' }); $issuedA = @($rows | Where-Object { $_.cancel_at }); $safeA = @($rows | Where-Object { $_.safety -eq 'PASS' })
        $evd['cancellation_safety'] = $(if ($failedA.Count) { 'FAIL' } elseif ($safeA.Count) { "PASS ($($safeA.Count) of $($issuedA.Count) cancel(s))" } elseif ($issuedA.Count) { 'PENDING' } else { 'NOT_EXERCISED' })
        $evd['leg_quiescing_coverage'] = $(if ($coveredA.Count) { "COVERED (attempt $($coveredA[0].attempt))" } elseif ($issuedA.Count) { 'UNVERIFIED: ' + ((@($issuedA | ForEach-Object { "a$($_.attempt)=$(if ($_.diag_long) { $_.diag_long } else { $_.coverage })" })) -join ' ') } else { 'NOT_EXERCISED' })
        $summaryA = ($rows | ForEach-Object { "a$($_.attempt):$($_.safety)/$($_.coverage)" }) -join ' '
        if ($failedA.Count) { Result $id $cls 'FAIL' "cancellation safety FAIL in attempt $($failedA[0].attempt): $($failedA[0].safety_why)" $evd }
        elseif ($coveredA.Count) { Result $id $cls 'PASS' "cancellation safety PASS and LEG_QUIESCING race coverage COVERED in attempt $($coveredA[0].attempt) ($($coveredA[0].coverage_why)); attempts: $summaryA" $evd }
        elseif ($safeA.Count) { Result $id $cls 'UNVERIFIED' "cancellation safety PASS ($($safeA.Count) cancel(s)) but LEG_QUIESCING was not reached/evidenced in $($rows.Count) attempt(s): $summaryA" $evd }
        elseif ($issuedA.Count) { Result $id $cls 'PENDING' "cancels issued but cancellation safety not established: $summaryA" $evd }
        else { Result $id $cls 'PENDING' "no cancel was issued in $($rows.Count) attempt(s) (trigger not met / goals completed first): $summaryA" $evd }
      }
      'checkpoint' {
        $cp = @($rm | Where-Object { $_.kind -eq 'CHECKPOINT' -and $_.t -ge $b.t }) | Select-Object -First 1
        if (-not $cp) { Result $id $cls 'PENDING' "checkpoint not reached ($($obs -join ','))" $evd; break }
        # Rev6: the snapshot CORRELATED with the checkpoint instant = the one closest in time inside the (unchanged) +-1.5 s window, ties -> the later one; it must
        # belong to this scenario's own goal. (Rev5 took the first snapshot of the window, an earlier HTTP_PENDING one, and so missed the MOVING snapshot taken in the checkpoint tick.)
        $cands = @($ss | Where-Object { $_.state -and [math]::Abs(($_.t - $cp.t).TotalSeconds) -le 1.5 -and $_.goal -and ($own -contains $_.goal) })
        $atC = @($cands | Sort-Object @{ Expression = { [math]::Abs(($_.t - $cp.t).TotalSeconds) }; Ascending = $true }, @{ Expression = { $_.t }; Descending = $true }) | Select-Object -First 1
        if ($atC) { $evd['checkpoint_snapshot'] = [ordered]@{ goal = $atC.goal; action = $atC.action; state = $atC.state; x = $atC.x; offset_s = (F1 (($atC.t - $cp.t).TotalSeconds)); candidates = $cands.Count } }
        if ($atC -and $atC.state -in 'MOVING', 'REPLANNING', 'DECIDED') { Result $id $cls 'PASS' "harness reached the Save&Quit checkpoint; M03 snapshot correlated with the checkpoint (goal $($atC.goal.Substring(0, 8)), action $(if ($atC.action) { $atC.action.Substring(0, 8) } else { 'n/a' })) shows journal state $($atC.state) at x=$($atC.x) (the quit itself is MANUAL; judged by p12); $ids" $evd }
        else { Result $id $cls 'PENDING' 'checkpoint reached but no snapshot of this scenario goal proves the NPC was mid-goal at that instant' $evd }
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
        $g2rows = @(); $g3rows = @()
        $sum = Join-Path $stage 'backend/db-summary.json'
        if (Test-Path $sum) { $j = Get-Content -Raw $sum | ConvertFrom-Json; if ($j.recent.cplus_e3_g2_moves) { $g2rows = @($j.recent.cplus_e3_g2_moves); $evd['latest_e3_g2_rows'] = @($g2rows | Select-Object -First 3 | ForEach-Object { ($_ | ConvertTo-Json -Compress) }) }; if ($j.recent.cplus_e3_g3_terminals) { $g3rows = @($j.recent.cplus_e3_g3_terminals) } }
        if (-not ($disp -ge 1.0 -and $sp -le 0.75)) { Result $id $cls 'FAIL' "E3 move not evidenced (displacement $(F1 $disp), final spread $(F1 $sp))" $evd; break }
        # ---- Rev6 acceptance: movement is NOT acceptance. Needed: C2 COMPLETED once, the TYPED taskmovefinalize response (durable ACK, fence released) for THIS task, the TYPED taskmovestatus after it,
        # and the durable G3 terminal row in the database for the same task. Anything missing is named; nothing is inferred.
        $g2 = @($g2rows | Where-Object { $_.goal_id -and ($goalIds -contains $_.goal_id) }) | Select-Object -First 1
        $missing = @()
        if (-not ($completed.Count -eq 1 -and $once)) { $missing += 'exactly one C2 COMPLETED terminal line for the p18 goal' }
        if (-not $g2) { $missing += 'the cplus_e3_g2_moves row of the p18 goal in the database summary' }
        $task = $(if ($g2) { [string]$g2.task_id } else { $null })
        $fin = $null; $stt = $null
        if ($task) {
            $fin = @($finalizeLines | Where-Object { $_.kind -eq 'FINALIZE' -and $_.outcome -eq 'COMPLETED' -and $_.task -eq $task -and $_.t -ge $b.t }) | Select-Object -First 1
            $wrong = @($finalizeLines | Where-Object { $_.kind -eq 'FINALIZE' -and $_.t -ge $b.t -and $_.task -ne $task })
            if ($wrong.Count) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: a typed taskmovefinalize response names another task ($($wrong[0].task)) than the p18 goal's task $task" $evd; break }
        }
        if (-not $fin) { $missing += 'the typed `taskmovefinalize yui` response "G3 COMPLETED task=<id> durable ACK; actor fence released" in the client chat log' }
        else { $stt = @($finalizeLines | Where-Object { $_.kind -eq 'STATUS' -and $_.t -ge $fin.t }) | Select-Object -First 1; if (-not $stt) { $missing += 'the typed `taskmovestatus yui` response after the finalize' } elseif ($stt.outcome -ne 'COMPLETED' -or $stt.text -notmatch 'G3 durable terminal recorded') { $missing += "taskmovestatus did not report E3 G2=COMPLETED with a durable G3 terminal (got $($stt.outcome))" } }
        $g3 = $null; if ($task) { $g3 = @($g3rows | Where-Object { [string]$_.task_id -eq $task }) | Select-Object -First 1 }
        if (-not $g3) { $missing += 'the durable cplus_e3_g3_terminals row for the task in the database' } elseif ($g3.outcome -ne 'COMPLETED') { $missing += "the durable G3 terminal outcome is '$($g3.outcome)', not COMPLETED" }
        $evd['e3'] = [ordered]@{ displacement = (F1 $disp); task_id = $task; finalize_response = $(if ($fin) { $fin.text } else { $null }); status_response = $(if ($stt) { $stt.text } else { $null }); g3_terminal_outcome = $(if ($g3) { $g3.outcome } else { $null }); g2_row_state_at_collection = $(if ($g2) { $g2.state } else { $null }); missing = $missing }
        if ($missing.Count) { Result $id $cls 'PENDING' "E3 guarded move displaced the NPC by $(F1 $disp) blocks and C2 reached its terminal, but G3 acceptance is incomplete: missing $($missing -join '; ')" $evd }
        else { Result $id $cls 'PASS' "E3 TaskMove accepted: displaced $(F1 $disp) blocks, one C2 COMPLETED, typed taskmovefinalize returned the durable ACK with the actor fence released, typed taskmovestatus reports the durable G3 terminal, database holds the COMPLETED terminal for task $task; $ids" $evd }
      }
      'multi' {
        $n = [int]($id -replace '^p\d+_multi_(\d+)_npc$', '$1')
        $perfRow = $perf | Where-Object { $_.npc_count -eq $n -and $_.local -ge $b.t -and $_.local -le $hi.AddSeconds(5) } | Select-Object -Last 1
        if ($perfRow) {
            # Rev6: the M03 tick-event timer is not reset at measurement start, so the FIRST sample of a round can be a stale-clock difference (hours/seconds) that poisons mean and max.
            # Such records are flagged; their mean/max are shown only as raw values and are NOT recalculated or accepted. G4 stays MEASURED_NOT_ACCEPTED.
            $pMax = [double]$perfRow.tick_event_max_ms; $pMean = [double]$perfRow.tick_event_mean_ms; $pP95 = [double]$perfRow.tick_event_p95_ms
            $taint = ($pMax -gt 1000) -or ($pP95 -gt 0 -and $pMean -gt (3 * $pP95))
            $evd['performance'] = [ordered]@{ status = $perfRow.status; samples = $perfRow.samples; tick_event_p95_ms = $perfRow.tick_event_p95_ms; server_average_tick_ms = $perfRow.server_average_tick_ms
                                              tick_event_mean_ms_raw = $perfRow.tick_event_mean_ms; tick_event_max_ms_raw = $perfRow.tick_event_max_ms
                                              first_sample_contamination = $(if ($taint) { 'SUSPECTED: raw mean/max are NOT usable (an uncorrected stale first sample); not recalculated here' } else { 'not detected' })
                                              usable_metrics = $(if ($taint) { 'tick_event_p95_ms, server_average_tick_ms' } else { 'tick_event_mean_ms_raw, tick_event_p95_ms, tick_event_max_ms_raw, server_average_tick_ms' })
                                              acceptance = 'MEASURED_NOT_ACCEPTED'; baseline = 'PENDING_NO_G0_BASELINE' }
            if ($taint) { Note "$id performance: first-sample contamination suspected (raw max $pMax ms); mean/max not usable" }
        }
        $dbj = $null; $dbSum = Join-Path $stage 'backend/db-summary.json'; if (Test-Path $dbSum) { $dbj = Get-Content -Raw $dbSum | ConvertFrom-Json }
        $lanes = @(); $perNpc = [ordered]@{}; $reachedN = 0
        for ($k = 0; $k -lt $n; $k++) {
            $nm = $laneNames[$k]; $lsn = @($w.snaps | Where-Object { $_.name -eq $nm }); $f = $lsn | Select-Object -Last 1
            $dd = Dist $f 42.5 (8 * $k + 2.5); $perNpc[$nm] = $(if ($f) { F1 $dd } else { 'no snapshot' }); $atTarget = ($f -and $dd -le $tol); if ($atTarget) { $reachedN++ }
            $lg = @($lsn | Where-Object { $_.goal -and ($own -contains $_.goal) } | ForEach-Object { $_.goal } | Select-Object -Unique)
            $lact = @($lsn | Where-Object { $_.action -and ($lg -contains $_.goal) } | ForEach-Object { $_.action } | Select-Object -Unique)
            $lterm = @($c2run | Where-Object { $lg -contains $_.goal }); $lcomp = @($lterm | Where-Object { $_.result -eq 'COMPLETED' })
            $lint = @($c2int | Where-Object { $lg -contains $_.goal })
            $actors = @(($lterm | ForEach-Object { $_.actor }) + ($lint | ForEach-Object { $_.actor }) | Where-Object { $_ } | Select-Object -Unique)
            $prof = $null; if ($dbj -and ($dbj.PSObject.Properties.Name -contains 'profiles')) { $prof = @($dbj.profiles | Where-Object { $_.display_name -eq $nm }) | Select-Object -First 1 }
            $gameProfile = ($lsn | Where-Object { $_.profile } | Select-Object -Last 1); if ($gameProfile) { $gameProfile = $gameProfile.profile }
            # registration state: BOUND | MISMATCH | REGISTRATION_FAILED | NOT_REGISTERED_YET | UNKNOWN | NOT_BOUND_UNEXPLAINED
            $laneActors = @(($actors) + @($lsn | Where-Object { $_.actor } | ForEach-Object { [string]$_.actor }) | Where-Object { $_ -and $_ -ne 'a' } | Select-Object -Unique)
            $regFail = @($c2int | Where-Object { $_.reason -match 'Character Profile NOT registered' -and $_.t -ge $b.t -and $_.t -lt $hi -and (($lg -contains $_.goal) -or ($laneActors -contains $_.actor)) }) | Select-Object -First 1
            if ($prof) { $reg = $(if ($actors.Count -and ($actors -notcontains [string]$prof.actor_id)) { 'MISMATCH' } else { 'BOUND' }) }
            elseif ($regFail) { $reg = 'REGISTRATION_FAILED' }
            elseif ($lg.Count -eq 0 -and $lterm.Count -eq 0) { $reg = 'NOT_REGISTERED_YET' }
            elseif (-not $dbj -or -not ($dbj.PSObject.Properties.Name -contains 'profiles')) { $reg = 'UNKNOWN' }
            else { $reg = 'NOT_BOUND_UNEXPLAINED' }
            $lst = 'FAIL'; $lwhy = ''
            if ($reg -eq 'REGISTRATION_FAILED') { $lwhy = "prerequisite failed: the Character Profile was NOT registered ($($regFail.reason))" }
            elseif ($reg -eq 'MISMATCH') { $lwhy = "INCONSISTENT_EVIDENCE: the profile row's actor $($prof.actor_id) is not the actor of the goal ($($actors -join ','))" }
            elseif ($reg -in 'NOT_REGISTERED_YET', 'UNKNOWN') { $lst = 'PENDING'; $lwhy = "registration $reg (no goal evidenced for this lane, or no profile table in the database summary)" }
            elseif ($reg -eq 'NOT_BOUND_UNEXPLAINED') { $lwhy = 'a goal ran but no profile row exists and no registration error was logged' }
            elseif ($lg.Count -ne 1) { $lwhy = "expected exactly one goal for this lane, found $($lg.Count)" }
            elseif ($lterm.Count -ne 1 -or $lcomp.Count -ne 1) { $lwhy = "expected exactly one COMPLETED terminal line, found $($lterm.Count) line(s) [$((@($lterm | ForEach-Object { $_.result }) -join ','))]" }
            elseif (-not $atTarget) { $lwhy = "target not reached ($($perNpc[$nm]) blocks away)" }
            else { $lst = 'PASS'; $lwhy = "profile bound (source=$($prof.source) revision=$($prof.revision)), goal $($lg[0].Substring(0, 8)) COMPLETED once, $($perNpc[$nm]) blocks from the target" }
            $row = [ordered]@{ scenario = $id; lane = $nm; status = $lst; registration = $reg; profile_source = $(if ($prof) { "$($prof.source) rev $($prof.revision)" } else { $null }); actor_id = $(if ($actors.Count) { $actors -join ',' } else { $null }); game_profile_id = $gameProfile
                               goal = ($lg -join ','); action = ($lact -join ','); states = $(if ($lg.Count) { (States $lsn $lg[0]) -join '>' } else { '' }); terminal = (@($lterm | ForEach-Object { $_.result }) -join ','); distance = $perNpc[$nm]; why = $lwhy }
            $lanes += [pscustomobject]$row; [void]$laneTable.Add([pscustomobject]$row)
        }
        $evd['distance_to_target_by_npc'] = $perNpc; $evd['lanes'] = $lanes
        $completedN = @($lanes | Where-Object { $_.terminal -eq 'COMPLETED' }).Count; $evd['completed_goals'] = $completedN
        $dupGoals = @($terms | Group-Object goal | Where-Object { $_.Count -gt 1 })
        $dupIds = @(); foreach ($fld in 'goal', 'actor_id', 'game_profile_id') { $vals = @($lanes | Where-Object { $_.$fld } | ForEach-Object { $_.$fld }); if (@($vals | Select-Object -Unique).Count -lt $vals.Count) { $dupIds += $fld } }
        $badLanes = @($lanes | Where-Object { $_.status -eq 'FAIL' }); $pendLanes = @($lanes | Where-Object { $_.status -eq 'PENDING' })
        $summaryLanes = ($lanes | ForEach-Object { "$($_.lane)=$($_.status)/$($_.registration)" }) -join ' '
        if ($obs -contains 'ALL_REACHED' -and $reachedN -lt $n) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: selector said ALL_REACHED but only $reachedN of $n NPCs are at their targets in the M03 snapshots" $evd }
        elseif ($dupGoals.Count) { Result $id $cls 'FAIL' 'a goal logged more than one terminal result (exactly-once violated)' $evd }
        elseif ($dupIds.Count) { Result $id $cls 'FAIL' "INCONSISTENT_EVIDENCE: lanes share a $($dupIds -join '/') (every lane must have its own)" $evd }
        elseif ($badLanes.Count) { Result $id $cls 'FAIL' "$($badLanes.Count) of $n lane(s) failed: $((@($badLanes | ForEach-Object { "$($_.lane): $($_.why)" }) -join ' | '))" $evd }
        elseif ($pendLanes.Count) { Result $id $cls 'PENDING' "$($pendLanes.Count) of $n lane(s) not evidenced: $summaryLanes" $evd }
        elseif ($perfRow -and $perfRow.status -ne 'HOLD') { Result $id $cls 'PASS' "all $n lanes verified independently ($summaryLanes); every lane its own goal/actor/GameProfile id; tick samples recorded (comparison to a baseline is PENDING)" $evd }
        else { Result $id $cls 'PENDING' "all $n lanes verified but the performance record is missing/HOLD" $evd }
      }
    }
}
$count = @{}; foreach ($r in $results) { $count[$r.status] = 1 + [int]$count[$r.status] }
$unobservable = @('LongState/leg counts (c2 status is chat-only; transitions only inside M03 measurement windows)', 'the failure reason text of a long goal unless the Rev6 diagnostic line `C2 diag result` is in the log (p08 stays UNVERIFIED otherwise)', 'FIFO wait distribution (not logged)', 'LongState at a cancel unless the Rev6 diagnostic line `C2 diag cancel` is in the log (p10 coverage stays UNVERIFIED otherwise)', 'segment-start capture cost (no timing log)', 'real MSPT percentiles unless /debug start|stop output is supplied', 'dedicated-server and fault-injected shutdown (not exercised)')
$summary = [ordered]@{ run_id = "G5-$stamp"; collected_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'; world = $WorldName; datapack = $packMatches; counts = $count; mode = $(if ($runStart) { ModeAt $endBound } else { 'UNKNOWN' }); probe_capabilities = $script:probeCaps; owner_gate = $gateStats; correlation = $correlation; scenarios = $results; unobservable = $unobservable; lane_table = $laneTable; isolation = [ordered]@{ run_id = $iso.marker.run_id; database = $Database; preflight = $pre.status; preflight_phase = $pre.phase; checks = @($iso.checks | ForEach-Object { "$($_.name)=$($_.ok)" }) }; notes = $notes; note_on_status = 'PASS requires the independent M03 snapshot position AND exactly one logged C2 terminal line for the scenario goal; a dispatched command or a data-pack marker is never a PASS. A scenario that awaited the owner gate but was never released is PENDING; one that ended without a recorded release is a FAIL (timer-only progression).' }
function StatusOf($ids) {
    $st = @($ids | ForEach-Object { $i = $_; ($results | Where-Object { $_.id -eq $i }).status })
    if (-not $st.Count -or ($st -contains $null)) { return 'PENDING' }
    if ($st -contains 'FAIL') { return 'FAIL' }; if ($st -contains 'BLOCKED') { return 'BLOCKED' }
    if (@($st | Where-Object { $_ -ne 'PASS' }).Count -eq 0) { return 'PASS' }
    if ($st -contains 'UNVERIFIED' -and -not ($st -contains 'PENDING')) { return 'UNVERIFIED' }
    return 'PENDING'
}
$p10r = $results | Where-Object { $_.id -eq 'p10_cancel_quiescing' }
# the dimensions are reported SEPARATELY on purpose: none of them is the milestone verdict, and an Owner gate GO is never one of them
$dimensions = [ordered]@{
    functional_navigation = StatusOf @('p02_navlong_20', 'p03_navlong_40', 'p04_navlong_80', 'p05_navlong_150', 'p06_navlong_diag60', 'p07_small_wall_detour', 'p13_new_navlong_after_recovery', 'p14_regress_c2_navigate', 'p15_regress_c2_move', 'p19_multi_1_npc', 'p20_multi_3_npc', 'p21_multi_5_npc', 'p22_multi_10_npc')
    cancellation_safety_and_quiescing_coverage = [ordered]@{ p09 = (StatusOf @('p09_cancel_leg_active')); p10 = $(if ($p10r) { $p10r.status } else { 'PENDING' }); p10_leg_quiescing_coverage = $(if ($p10r -and $p10r.evidence.leg_quiescing_coverage) { $p10r.evidence.leg_quiescing_coverage } else { 'NOT_EXERCISED' }) }
    long_goal_failure_reason = StatusOf @('p08_large_wall_no_progress')
    recovery = StatusOf @('p11_savequit_checkpoint', 'p12_rejoin_no_replay')
    e3_taskmove_finalization = StatusOf @('p18_regress_e3_taskmove')
    performance = 'MEASURED_NOT_ACCEPTED'
    owner_gate = 'SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT'
    milestone = 'NOT_ACCEPTED_BY_THE_COLLECTOR: acceptance needs the Owner gameplay review of this report'
}
$summary['collector_version'] = $CollectorVersion
$summary['status_dimensions'] = $dimensions
$summary['evidence_gaps'] = @($evidenceGaps)
$summary['backend'] = [ordered]@{ health_at_collection = $health; health_state = $healthState; logs = $backendLogStats }
$summary['provenance'] = [ordered]@{ collector_version = $CollectorVersion; collector_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash; g5_run_id = $iso.marker.run_id
                                     datapack_state = $packMatches; rev5_reports_are_never_rewritten = $true; interpretation = 'A Rev6 report is a NEW document; the report of an earlier collector version for the same evidence stays as it was.' }
[IO.File]::WriteAllText((Join-Path $stage 'summary.json'), ($summary | ConvertTo-Json -Depth 8), $utf8)

# ---------------------------------------------------------------- environment, report, manifest
$javaVer = try { (& cmd.exe /c 'java -version 2>&1') -join ' ' } catch { 'unavailable' }
$opts = @{}; $of = Join-Path $RunDir 'options.txt'; if (Test-Path $of) { Get-Content $of | Where-Object { $_ -match '^(renderDistance|simulationDistance):' } | ForEach-Object { $k, $v = $_ -split ':', 2; $opts[$k] = $v } }
$forgeLine = ($lines | Where-Object { $_.msg -match 'Forge|Minecraft Version' } | Select-Object -First 1).msg
$srcHashes = [ordered]@{}; foreach ($f in 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2AgentRuntime.java', 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2LongGoalPolicy.java', 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2PlanQueue.java', 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C2Diagnostics.java', 'forge-mod/src/main/java/io/github/sirp00m/npcortex/NpcTabName.java') { $p = Join-Path $root $f; if (Test-Path $p) { $srcHashes[$f] = (Get-FileHash -Algorithm SHA256 -LiteralPath $p).Hash } }
$env = [ordered]@{ collector_version = $CollectorVersion; backend_health_state = $healthState; run_id = "G5-$stamp"; os = [Environment]::OSVersion.VersionString; powershell = $PSVersionTable.PSVersion.ToString(); java = $javaVer; minecraft_forge = $forgeLine; world = $WorldName; options = $opts; datapack_state = $packMatches; installed_datapacks = @($dpInfo.Keys); database = $dbInfo; isolation = [ordered]@{ run_id = $iso.marker.run_id; database = $Database; preflight = $pre.status }; backend_health = $health; lookback_hours = $LookbackHours; source_hashes = $srcHashes; log_files = @($logFiles | ForEach-Object { $_.Name }) }
[IO.File]::WriteAllText((Join-Path $stage 'meta/environment.json'), ($env | ConvertTo-Json -Depth 5), $utf8)
$rep = @("# NPCortex G5 test report $stamp (collector $CollectorVersion)", '', "World: $WorldName | datapack: $packMatches | database: $dbInfo", '', '| Scenario | Class | Status | Reason |', '|---|---|---|---|')
foreach ($r in $results) { $rep += "| $($r.id) | $($r.class) | **$($r.status)** | $($r.reason -replace '\|', '/') |" }
$rep += ''; $rep += 'Correlation (scenario / goal id / action id / journal states / terminal lines):'; $rep += ''; $rep += '| Scenario | Mode | Goal | Action | Journal states | Terminal | Owner gate |'; $rep += '|---|---|---|---|---|---|---|'
foreach ($c in $correlation) { $rep += "| $($c.scenario) | $($c.mode) | $($c.goal) | $($c.action) | $($c.states) | $($c.terminal) | $($c.gate) |" }
if ($script:probeCaps) { $rep += ''; $rep += 'p00 probe capabilities (PASS / FAIL / NOT_ATTEMPTED; NOT_ATTEMPTED is never a failure):'; $rep += ''; foreach ($k in $script:probeCaps.Keys) { $rep += "- $k = $($script:probeCaps[$k])" } }
if ($laneTable.Count) {
    $rep += ''; $rep += 'Lane-by-lane (multi-NPC) verification - every lane has its own profile binding, goal, action and terminal line:'; $rep += ''
    $rep += '| Scenario | Lane | Status | Registration | Profile | Actor | GameProfile | Goal | Action | Journal states | Terminal | Distance | Why |'; $rep += '|---|---|---|---|---|---|---|---|---|---|---|---|---|'
    foreach ($r in $laneTable) { $rep += "| $($r.scenario) | $($r.lane) | **$($r.status)** | $($r.registration) | $($r.profile_source) | $($r.actor_id) | $($r.game_profile_id) | $($r.goal) | $($r.action) | $($r.states) | $($r.terminal) | $($r.distance) | $($r.why -replace '\|', '/') |" }
}
$rep += ''; $rep += 'Status dimensions (reported separately; none of them is the milestone verdict, and an Owner gate GO is never one of them):'; $rep += ''
foreach ($dk in $dimensions.Keys) { $dv = $dimensions[$dk]; $rep += "- $dk = $(if ($dv -is [System.Collections.IDictionary]) { (($dv.Keys | ForEach-Object { "$_=$($dv[$_])" }) -join ', ') } else { $dv })" }
$rep += ''; $rep += "Provenance: collector $CollectorVersion (sha256 $((Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.Substring(0, 16))...), G5 run $($iso.marker.run_id); backend health: $healthState. Earlier reports of other collector versions are not rewritten by this one."
if ($evidenceGaps.Count) { $rep += ''; $rep += 'EVIDENCE GAPS (mandatory evidence that could not be collected):'; $evidenceGaps | ForEach-Object { $rep += "- $_" } }
$rep += ''; $rep += "Isolation: run $($iso.marker.run_id), database $Database, preflight $($pre.status) ($($pre.phase))"
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
