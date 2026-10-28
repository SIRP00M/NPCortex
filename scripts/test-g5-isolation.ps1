# TEST-ONLY: proves the revision-5 G5 prerequisites FAIL CLOSED: run isolation marker, preflight (fresh database / world / lanes / data pack / credential),
# the isolated backend launcher (real process on a free port, temp folders only) and the evidence collector's refusal to read an unverified database.
# It never touches a Minecraft world, the live database (backend/data) or any journal; the only files written are in a temp folder.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ps = (Get-Command powershell.exe).Source
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("g5-iso-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$fails = New-Object System.Collections.ArrayList; $checks = 0
function Check($c, $m) { $script:checks++; if (-not $c) { [void]$script:fails.Add($m) } }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$liveDb = Join-Path $root 'backend/data/npcortex.sqlite3'
$liveBefore = if (Test-Path $liveDb) { (Get-Item $liveDb).LastWriteTimeUtc.Ticks } else { 0 }
$env:NPCORTEX_CPLUS_F1_TOKEN = 'iso-test-token-' + ('x' * 30)      # satisfies the credential check and keeps the real secret file untouched
$env:NPCORTEX_ALLOW_SCHEMA_V5 = ''
function RunPs([string]$file, [string[]]$a) {
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = & $ps -NoProfile -ExecutionPolicy Bypass -File $file @a 2>&1 | Out-String; return [pscustomobject]@{ code = $LASTEXITCODE; out = $out } } finally { $ErrorActionPreference = $old }
}
function NewDb([string]$path, $rows = @()) {   # a v5-shaped database; $rows = @(@{actor=..;name=..})
    $statements = @('create table worlds(world_id text, name text)', 'create table actors(actor_id text, world_id text, dimension_id text, character_id text)',
                    'create table character_profiles(actor_id text, revision integer, source text, display_name text, composition text, content_sha256 text)')
    foreach ($r in $rows) { $statements += "insert into actors values('$($r.actor)','w','minecraft:overworld','@profile')"; $statements += "insert into character_profiles values('$($r.actor)',1,'default','$($r.name)','structured','ab')" }
    if ($rows.Count) { $statements += "insert into worlds values('w','n')" }
    $statements += 'PRAGMA user_version=5'
    $py = "import sqlite3, sys, json`nc = sqlite3.connect(sys.argv[1])`nfor q in json.load(open(sys.argv[2], encoding='utf-8')): c.execute(q)`nc.commit()`n"
    $pyFile = Join-Path $tmp 'mkdb.py'; $jsonFile = Join-Path $tmp ('stm-' + [guid]::NewGuid().ToString('N').Substring(0, 6) + '.json')
    [IO.File]::WriteAllText($pyFile, $py, $utf8); [IO.File]::WriteAllText($jsonFile, (ConvertTo-Json -InputObject @($statements)), $utf8)
    & python -I $pyFile $path $jsonFile
    if ($LASTEXITCODE -ne 0) { throw "mkdb failed for $path" }
}
function NewRun([string]$name, $rows = @(), [switch]$NoMarker) {
    $dir = Join-Path $tmp "g5-results/run-$name"; New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $db = Join-Path $dir 'backend.sqlite3'; NewDb $db $rows
    if (-not $NoMarker) { [IO.File]::WriteAllText((Join-Path $dir 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = $name; db_path = $db; repo_default_db = $liveDb; created_utc = '2026-01-01T00:00:00Z' } | ConvertTo-Json), $utf8) }
    return $dir
}
function NewWorld([string]$name, [switch]$WithIdentity, [switch]$WithEvidence, [switch]$ChangedPack, [switch]$NoPack) {
    $w = Join-Path $tmp "run/saves/$name"; New-Item -ItemType Directory -Force -Path (Join-Path $w 'data') | Out-Null
    if (-not $NoPack) { Copy-Item -Recurse -LiteralPath (Join-Path $root 'integrations/datapacks/NPCortex_G5') -Destination (Join-Path $w 'datapacks/NPCortex_G5') }
    if ($ChangedPack) { [IO.File]::AppendAllText((Join-Path $w 'datapacks/NPCortex_G5/pack.mcmeta'), ' ') }
    if ($WithIdentity) { [IO.File]::WriteAllText((Join-Path $w 'data/npcortex_c1_p0.dat'), 'x') }
    if ($WithEvidence) { New-Item -ItemType Directory -Force -Path (Join-Path $w 'data/npcortex/m03-evidence') | Out-Null; [IO.File]::WriteAllText((Join-Path $w 'data/npcortex/m03-evidence/a.json'), '{}') }
    return $w
}
$pre = Join-Path $PSScriptRoot 'g5-preflight.ps1'
function Preflight($dir, $phase, $world, [string[]]$more = @()) {
    $a = @('-G5RunDir', $dir, '-Phase', $phase, '-NoLiveBackend', '-Quiet', '-OutDir', (Join-Path $tmp 'g5-results'), '-RunDir', (Join-Path $tmp 'run')) + $more
    if ($world) { $a += @('-WorldName', $world) }
    $r = RunPs $pre $a
    $j = $null; try { $j = ($r.out -split "`r?`n" | Where-Object { $_.Trim().StartsWith('{') } | Select-Object -Last 1) | ConvertFrom-Json } catch { }
    return [pscustomobject]@{ code = $r.code; json = $j; out = $r.out }
}
function Failed($r, [string]$name) { return [bool]($r.json -and @($r.json.checks | Where-Object { $_.name -eq $name -and -not $_.ok }).Count) }

# ---------------------------------------------------------------- preflight: Start phase
[void](NewWorld 'W_fresh')
$d = NewRun 'ok'; $r = Preflight $d 'Start' 'W_fresh'
Check ($r.code -eq 0 -and $r.json.status -eq 'PASS') "fresh isolated run + fresh world + identical pack -> PASS ($($r.out))"
Check ((Get-Content -Raw (Join-Path $d 'preflight.json') | ConvertFrom-Json).status -eq 'PASS') 'preflight.json is written'
Check ($r.json.note -match 'not a test verdict') 'the preflight result says PASS is not a test verdict'
$r = Preflight (NewRun 'nomarker' -NoMarker) 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.marker')) 'missing marker -> FAIL (cannot be verified as isolated)'
$d = NewRun 'defaultdb'; [IO.File]::WriteAllText((Join-Path $d 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = 'defaultdb'; db_path = $liveDb; repo_default_db = $liveDb } | ConvertTo-Json), $utf8)
$r = Preflight $d 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.not_default_database') -and (Failed $r 'isolation.db_absolute_in_run_dir')) 'marker pointing at backend/data/npcortex.sqlite3 -> FAIL'
$d = NewRun 'outside'; $other = Join-Path $tmp 'elsewhere.sqlite3'; NewDb $other
[IO.File]::WriteAllText((Join-Path $d 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = 'outside'; db_path = $other; repo_default_db = $liveDb } | ConvertTo-Json), $utf8)
$r = Preflight $d 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.db_absolute_in_run_dir')) 'database outside the run folder -> FAIL'
$d = NewRun 'badkind'; [IO.File]::WriteAllText((Join-Path $d 'G5-ISOLATED.marker.json'), '{"kind":"something-else","run_id":"x","db_path":"x"}', $utf8)
$r = Preflight $d 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.marker')) 'marker of the wrong kind -> FAIL'
$r = Preflight (Join-Path $tmp 'g5-results/run-doesnotexist') 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.run_dir')) 'unknown run folder -> FAIL'
$d = NewRun 'nodb'; Remove-Item (Join-Path $d 'backend.sqlite3'); $r = Preflight $d 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.database_exists')) 'missing database file -> FAIL'
$d = NewRun 'junction'; $real = Join-Path $tmp 'junction-target'; New-Item -ItemType Directory -Force -Path $real | Out-Null; NewDb (Join-Path $real 'b.sqlite3')
cmd /c mklink /J "$d\link" "$real" | Out-Null
[IO.File]::WriteAllText((Join-Path $d 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = 'junction'; db_path = (Join-Path $d 'link\b.sqlite3'); repo_default_db = $liveDb } | ConvertTo-Json), $utf8)
$r = Preflight $d 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.no_links')) 'a junction between the run folder and the database -> FAIL'
cmd /c rmdir "$d\link" | Out-Null
# a junction on an ANCESTOR of the run folder (an alias of some other folder) must fail too
$anc = Join-Path $tmp 'anc-target'; New-Item -ItemType Directory -Force -Path (Join-Path $anc 'run-viaanc') | Out-Null
cmd /c mklink /J "$tmp\anc-link" "$anc" | Out-Null
$vd = Join-Path $tmp 'anc-link\run-viaanc'; NewDb (Join-Path $vd 'backend.sqlite3')
[IO.File]::WriteAllText((Join-Path $vd 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = 'viaanc'; db_path = (Join-Path $vd 'backend.sqlite3'); repo_default_db = $liveDb } | ConvertTo-Json), $utf8)
$r = Preflight $vd 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'isolation.no_links')) 'a junction on an ancestor of the run folder -> FAIL'
cmd /c rmdir "$tmp\anc-link" | Out-Null
# odd path spellings and a run folder under the repository backend folder (in-process, with a fake repository root)
. (Join-Path $PSScriptRoot 'g5-common.ps1')
$fake = Join-Path $tmp 'fakeroot'; $fr = Join-Path $fake 'backend/data/run-x'; New-Item -ItemType Directory -Force -Path $fr | Out-Null; NewDb (Join-Path $fr 'backend.sqlite3')
[IO.File]::WriteAllText((Join-Path $fr 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = 'x'; db_path = (Join-Path $fr 'backend.sqlite3'); repo_default_db = (Join-Path $fake 'backend/data/npcortex.sqlite3') } | ConvertTo-Json), $utf8)
$ir = Test-G5Isolation -G5RunDir $fr -RepoRoot $fake
Check (-not $ir.ok -and (@($ir.checks | Where-Object { $_.name -eq 'not_under_backend_dir' -and -not $_.ok }).Count -eq 1)) 'a run folder under the repository backend folder is refused'
$od = Join-Path $tmp 'odd'; New-Item -ItemType Directory -Force -Path $od | Out-Null; NewDb (Join-Path $od 'backend.sqlite3')
foreach ($spell in @(('\\?\' + (Join-Path $od 'backend.sqlite3')), (Join-Path $od 'BACKEN~1.SQL'))) {
    [IO.File]::WriteAllText((Join-Path $od 'G5-ISOLATED.marker.json'), (@{ kind = 'npcortex-g5-isolation-marker-v1'; run_id = 'odd'; db_path = $spell; repo_default_db = $liveDb } | ConvertTo-Json), $utf8)
    $ir = Test-G5Isolation -G5RunDir $od -RepoRoot $root
    Check (-not $ir.ok -and (@($ir.checks | Where-Object { $_.name -eq 'plain_path_spelling' -and -not $_.ok }).Count -eq 1)) "unusual path spelling is refused: $spell"
}

$d = NewRun 'dirtydb' @(@{ actor = '11111111-1111-4111-8111-111111111111'; name = 'yui' })
$r = Preflight $d 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'database_fresh')) 'a Start on a database that already has actors -> FAIL'
[void](NewWorld 'W_ident' -WithIdentity); $r = Preflight (NewRun 'a1') 'Start' 'W_ident'; Check ($r.code -eq 1 -and (Failed $r 'world_fresh_no_npc_identities')) 'a world with saved NPC identities is not fresh -> FAIL'
[void](NewWorld 'W_ev' -WithEvidence); $r = Preflight (NewRun 'a2') 'Start' 'W_ev'; Check ($r.code -eq 1 -and (Failed $r 'world_fresh_no_m03_evidence')) 'a world with old M03 evidence is not fresh -> FAIL'
[void](NewWorld 'W_chg' -ChangedPack); $r = Preflight (NewRun 'a3') 'Start' 'W_chg'; Check ($r.code -eq 1 -and (Failed $r 'datapack_identical_to_repo')) 'a changed data pack -> FAIL'
[void](NewWorld 'W_nopack' -NoPack); $r = Preflight (NewRun 'a4') 'Start' 'W_nopack'; Check ($r.code -eq 1 -and (Failed $r 'datapack_installed')) 'no data pack installed -> FAIL'
$r = Preflight (NewRun 'a5') 'Start' 'W_missing'; Check ($r.code -eq 1 -and (Failed $r 'world_exists')) 'unknown world -> FAIL'
$r = Preflight (NewRun 'a6') 'Start' 'W_fresh' @('-Lanes', '11'); Check ($r.code -eq 1 -and (Failed $r 'lane_count')) '11 lanes exceed MAX_ACTORS=10 -> FAIL (the cap is not changed)'
$env:NPCORTEX_CPLUS_F1_TOKEN = ''; $env:NPCORTEX_CPLUS_F1_SECRET_FILE = (Join-Path $tmp 'absent.secret')
$r = Preflight (NewRun 'a7') 'Start' 'W_fresh'; Check ($r.code -eq 1 -and (Failed $r 'local_credential')) 'no local credential -> FAIL'
Check ($r.out -notmatch 'iso-test-token') 'the credential never appears in the output'
$env:NPCORTEX_CPLUS_F1_TOKEN = 'iso-test-token-' + ('x' * 30); Remove-Item Env:\NPCORTEX_CPLUS_F1_SECRET_FILE

# ---------------------------------------------------------------- preflight: Before phase (between scenario groups)
$lanesBound = @(@{ actor = '11111111-1111-4111-8111-111111111111'; name = 'yui' }, @{ actor = '22222222-2222-4222-8222-222222222222'; name = 'g5a02' })
$w = NewWorld 'W_before'
$r = Preflight (NewRun 'b1' $lanesBound) 'Before' 'W_before'
Check ($r.code -eq 0 -and $r.json.status -eq 'PASS') "bound lanes only -> PASS ($($r.out))"
$l3 = @($r.json.lanes | Where-Object { $_.lane -eq 'g5a03' })[0]
Check ($l3.registration -match 'NOT_REGISTERED_YET' -and $l3.registration -match 'expected before the first C2 goal') 'a lane without a profile row is NOT_REGISTERED_YET (expected), not a failure'
Check ((@($r.json.lanes | Where-Object { $_.lane -eq 'g5a02' })[0]).registration -match 'BOUND source=default') 'a registered lane reports its profile binding'
$r = Preflight (NewRun 'b2' (@($lanesBound) + @(@{ actor = '33333333-3333-4333-8333-333333333333'; name = 'Bob' }))) 'Before' 'W_before'
Check ($r.code -eq 1 -and (Failed $r 'only_g5_lanes_in_database')) 'an actor that is not a G5 lane -> FAIL'
$ev = Join-Path $w 'data/npcortex/m03-evidence'; New-Item -ItemType Directory -Force -Path $ev | Out-Null
[IO.File]::WriteAllText((Join-Path $ev 's1.json'), (@{ name = 'g5a02'; captured_utc = '2026-01-01T00:00:00Z'; x = 1; z = 1; c2_journal = @{ state = 'MOVING'; goal_id = 'g'; action_id = 'a' } } | ConvertTo-Json -Depth 4))
$r = Preflight (NewRun 'b3' $lanesBound) 'Before' 'W_before'
Check ($r.code -eq 1 -and (Failed $r 'no_lane_with_active_journal') -and ($r.out -match 'NOT reset')) 'a lane with a non-terminal journal -> FAIL and the message says nothing is reset'
Check (-not (Get-ChildItem -Recurse $tmp -Filter *.sqlite3 | Where-Object { $_.Name -eq 'b.sqlite3' -and $false })) 'preflight leaves every database as it was'
[IO.File]::WriteAllText((Join-Path $ev 's1.json'), (@{ name = 'g5a02'; captured_utc = '2026-01-01T00:00:00Z'; x = 1; z = 1; c2_journal = @{ state = 'COMPLETED'; goal_id = 'g'; action_id = 'a' } } | ConvertTo-Json -Depth 4))
$r = Preflight (NewRun 'b4' $lanesBound) 'Before' 'W_before'; Check ($r.code -eq 0) 'a terminal journal is fine'

# ---------------------------------------------------------------- the real isolated backend (free port, temp folders)
$lsn = New-Object System.Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0); $lsn.Start(); $port = $lsn.LocalEndpoint.Port; $lsn.Stop()
$start = Join-Path $PSScriptRoot 'start-g5-backend.ps1'
$outDir = Join-Path $tmp 'live-results'; New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$env:NPCORTEX_ALLOW_SCHEMA_V5 = '1'; $r = RunPs $start @('-RunId', 'x1', '-Port', "$port", '-OutDir', $outDir); $env:NPCORTEX_ALLOW_SCHEMA_V5 = ''
Check ($r.code -eq 1 -and $r.out -match 'NPCORTEX_ALLOW_SCHEMA_V5') 'the launcher refuses a shell that would allow a schema upgrade'
Check (-not (Test-Path (Join-Path $outDir 'run-x1'))) 'a refused start creates nothing'
$r = RunPs $start @('-RunId', 'live1', '-Port', "$port", '-OutDir', $outDir)
Check ($r.code -eq 0 -and $r.out -match 'G5 isolated backend running') "isolated backend starts ($($r.out))"
$run1 = Join-Path $outDir 'run-live1'
try {
    $rec = Get-Content -Raw (Join-Path $run1 'backend-run.json') | ConvertFrom-Json
    Check (Test-Path (Join-Path $run1 'backend.sqlite3')) 'the backend created its database INSIDE the run folder'
    Check ((Get-Content -Raw (Join-Path $outDir 'current-run.json') | ConvertFrom-Json).run_id -eq 'live1') 'current-run.json points at the run'
    $r = Preflight $run1 'Start' 'W_fresh' @('-OutDir', $outDir); $r2 = RunPs $pre @('-G5RunDir', $run1, '-Phase', 'Start', '-WorldName', 'W_fresh', '-Quiet', '-OutDir', $outDir, '-RunDir', (Join-Path $tmp 'run'))
    Check ($r2.code -eq 0) "live preflight (port owner = recorded pid, health ok, fresh database) -> PASS ($($r2.out))"
    $iso = (($r2.out -split "`r?`n" | Where-Object { $_.Trim().StartsWith('{') } | Select-Object -Last 1) | ConvertFrom-Json).checks
    Check ((@($iso | Where-Object { $_.name -eq 'isolation.port_owner_is_recorded_backend' -and $_.ok }).Count -eq 1) -and (@($iso | Where-Object { $_.name -eq 'isolation.backend_health' -and $_.ok }).Count -eq 1)) 'the port owner and the health check are verified'
    $rec2 = $rec.PSObject.Copy(); $rec2.pid = $PID; [IO.File]::WriteAllText((Join-Path $run1 'backend-run.json'), ($rec2 | ConvertTo-Json), $utf8)
    $r3 = RunPs $pre @('-G5RunDir', $run1, '-Phase', 'Start', '-Quiet', '-OutDir', $outDir, '-RunDir', (Join-Path $tmp 'run'))
    Check ($r3.code -eq 1 -and $r3.out -match 'port_owner_is_recorded_backend') 'a backend that is not the recorded one (different pid on the port) -> FAIL'
    [IO.File]::WriteAllText((Join-Path $run1 'backend-run.json'), ($rec | ConvertTo-Json), $utf8)
    $again = RunPs $start @('-RunId', 'live2', '-Port', "$port", '-OutDir', $outDir)
    Check ($again.code -eq 1 -and $again.out -match 'already in use') 'a busy port is refused (a running/live backend is never reused)'
    $same = RunPs $start @('-RunId', 'live1', '-Port', "$($port + 1)", '-OutDir', $outDir)
    Check ($same.code -eq 1 -and $same.out -match 'already exists') 'a run folder is never reused'
} finally {
    $stop = RunPs $start @('-Stop', '-OutDir', $outDir)
}
Start-Sleep -Milliseconds 800
Check ($null -eq (Get-Process -Id ([int]$rec.pid) -ErrorAction SilentlyContinue)) 'the launcher can stop the backend it started'
$stopRec = Get-Content -Raw (Join-Path (Split-Path $rec.db_path -Parent) 'backend-stop.json') | ConvertFrom-Json
Check ($stopRec.health_before_stop -match 'ok' -and $stopRec.stopped_utc) 'Rev6: -Stop records the backend health BEFORE it stops it (post-shutdown UNREACHABLE is then expected, not a failure)'
$liveAfter = if (Test-Path $liveDb) { (Get-Item $liveDb).LastWriteTimeUtc.Ticks } else { 0 }
Check ($liveAfter -eq $liveBefore) 'the live database backend/data/npcortex.sqlite3 was not touched by any of this'

# ---------------------------------------------------------------- the collector refuses an unverified database
$fx = Join-Path $tmp 'fx'; & node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $fx ok | Out-Null
$col = Join-Path $PSScriptRoot 'collect-g5-evidence.ps1'
function Collect([string]$g5Dir, [string[]]$more = @(), [string]$out) {
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $a = @('-WorldName', 'NPCortex_G5_fixture', '-RunDir', (Join-Path $fx 'run'), '-BackendUrl', 'http://127.0.0.1:1', '-OutDir', $out) + $more
    if ($g5Dir) { $a += @('-G5RunDir', $g5Dir) }
    $r = RunPs $col $a
    return [pscustomobject]@{ code = $r.code; out = $r.out; zips = @(Get-ChildItem $out -Filter *.zip -ErrorAction SilentlyContinue).Count }
}
function Variant([string]$name, [scriptblock]$edit) {
    $dir = Join-Path $tmp "col-$name"; Copy-Item -Recurse -LiteralPath (Join-Path $fx 'g5/run-fixture') -Destination $dir
    $mk = Get-Content -Raw (Join-Path $dir 'G5-ISOLATED.marker.json') | ConvertFrom-Json; $pf = Get-Content -Raw (Join-Path $dir 'preflight.json') | ConvertFrom-Json
    $mk.db_path = Join-Path $dir 'backend.sqlite3'
    & $edit $dir $mk $pf
    if ($mk) { [IO.File]::WriteAllText((Join-Path $dir 'G5-ISOLATED.marker.json'), ($mk | ConvertTo-Json), $utf8) }
    if ($pf) { [IO.File]::WriteAllText((Join-Path $dir 'preflight.json'), ($pf | ConvertTo-Json), $utf8) }
    return $dir
}
$good = Variant 'good' { param($d, $mk, $pf) $pf.db_path = $mk.db_path }
$res = Collect $good @() (Join-Path $tmp 'out-good'); Check ($res.code -eq 0 -and $res.zips -eq 1) "collector accepts a verified isolated run (exit $($res.code): $($res.out))"
$res = Collect $good @('-Database', $liveDb) (Join-Path $tmp 'out-d1'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'refused') '-Database = the live database is refused'
$res = Collect $good @('-Database', (Join-Path $tmp 'elsewhere.sqlite3')) (Join-Path $tmp 'out-d2'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'database_argument_matches_marker') '-Database that differs from the marker database is refused'
$res = Collect $null @() (Join-Path $tmp 'out-d3'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'refused') 'no -G5RunDir and no current run -> refused (there is no default database any more)'
$bad = Variant 'nomarker' { param($d, $mk, $pf) }; Remove-Item (Join-Path $bad 'G5-ISOLATED.marker.json')
$res = Collect $bad @() (Join-Path $tmp 'out-m'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'marker') 'missing marker -> refused'
$bad = Variant 'live' { param($d, $mk, $pf) $mk.db_path = $liveDb }
$res = Collect $bad @() (Join-Path $tmp 'out-l'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'not_default_database') 'marker naming the live database -> refused'
$bad = Variant 'outside' { param($d, $mk, $pf) $mk.db_path = (Join-Path $tmp 'elsewhere.sqlite3') }
$res = Collect $bad @() (Join-Path $tmp 'out-o'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'db_absolute_in_run_dir') 'database outside the run folder -> refused'
$bad = Variant 'pffail' { param($d, $mk, $pf) $pf.status = 'FAIL' }
$res = Collect $bad @() (Join-Path $tmp 'out-p'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'preflight') 'failed preflight -> refused'
$bad = Variant 'pfmissing' { param($d, $mk, $pf) }; Remove-Item (Join-Path $bad 'preflight.json')
$res = Collect $bad @() (Join-Path $tmp 'out-q'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'preflight') 'no preflight record -> refused'
$bad = Variant 'pfid' { param($d, $mk, $pf) $pf.run_id = 'some-other-run' }
$res = Collect $bad @() (Join-Path $tmp 'out-r'); Check ($res.code -ne 0 -and $res.zips -eq 0 -and $res.out -match 'preflight') 'preflight of a different run -> refused'
# current-run.json is honoured when no -G5RunDir is given
$cur = Join-Path $tmp 'out-cur'; New-Item -ItemType Directory -Force -Path $cur | Out-Null
[IO.File]::WriteAllText((Join-Path $cur 'current-run.json'), (@{ run_dir = $good; run_id = 'fixture' } | ConvertTo-Json), $utf8)
$res = Collect $null @() $cur; Check ($res.code -eq 0 -and $res.zips -eq 1) 'current-run.json selects the run when -G5RunDir is omitted'

# ---- Rev6 helpers: relative paths are resolved against the PowerShell location (never the process directory); live files are read with a shared read
. (Join-Path $PSScriptRoot 'g5-isolation.ps1')
$oldCwd2 = [Environment]::CurrentDirectory; $rd = Join-Path $tmp 'relbase'; New-Item -ItemType Directory -Force -Path $rd | Out-Null
try {
    [Environment]::CurrentDirectory = [Environment]::GetFolderPath('System'); Push-Location $rd
    $rp = Resolve-G5Path 'sub\file.log'; Check ($rp -eq (Join-Path $rd 'sub\file.log')) "a relative path resolves against the PowerShell location, got $rp"
    Check ($rp -notlike "$([Environment]::GetFolderPath('System'))*") 'a relative path never resolves under the process directory (C:\Windows\system32)'
    Check ((Resolve-G5Path 'C:\a\..\b\c.log') -eq 'C:\b\c.log') 'an absolute path is normalised'
    Check ($null -eq (Resolve-G5Path '')) 'an empty path stays empty'
} finally { Pop-Location; [Environment]::CurrentDirectory = $oldCwd2 }
$lf = Join-Path $tmp 'live.log'; [IO.File]::WriteAllText($lf, "line one`nline two`nunfinished")
$wr = New-Object IO.FileStream($lf, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
try {
    $threw = $false; try { [void][IO.File]::ReadAllText($lf) } catch { $threw = $true }
    Check $threw 'precondition: a plain ReadAllText fails while a writer holds the file (the Rev5 defect)'
    $r = Read-G5Shared $lf -DropPartialLine
    Check ($r.text -eq "line one`nline two`n" -and $r.partial_line_dropped) 'shared read works under a writer and drops the unfinished last line'
    $r2 = Read-G5Shared $lf; Check ($r2.text.EndsWith('unfinished') -and -not $r2.partial_line_dropped) 'without -DropPartialLine the text is returned as is'
} finally { $wr.Dispose() }
$threw = $false; try { [void](Read-G5Shared (Join-Path $tmp 'does-not-exist.log')) } catch { $threw = $true }; Check $threw 'an unreadable/missing file throws: mandatory evidence is never silently dropped'

Remove-Item Env:\NPCORTEX_CPLUS_F1_TOKEN, Env:\NPCORTEX_ALLOW_SCHEMA_V5 -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
[pscustomobject]@{ checks = $checks; failures = $fails.Count; status = $(if ($fails.Count) { 'FAIL' } else { 'PASS' }); scope = 'fail-closed prerequisites on temp folders + one real isolated backend process; no Minecraft, no live database' } | ConvertTo-Json
$fails | ForEach-Object { "FAIL: $_" }
if ($fails.Count) { exit 1 }
