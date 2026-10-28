# G5 run isolation helpers (revision 5, TEST ONLY). Dot-sourced by g5-common.ps1.
# A G5 run owns a folder g5-results/run-<id> with a marker, the backend record and ONE fresh SQLite file. Preflight and the collector
# FAIL CLOSED unless this proof holds; nothing here ever writes to a database, resets a journal or prints the local credential.
$G5_MARKER_KIND = 'npcortex-g5-isolation-marker-v1'
function Get-G5RepoRoot { return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
function Read-G5Json([string]$path) { try { return ([IO.File]::ReadAllText($path) | ConvertFrom-Json) } catch { return $null } }
# Rev6: .NET file APIs resolve a relative path against the PROCESS directory (C:\Windows\system32 when started from an elevated shell or the Explorer),
# not against the PowerShell location. Every user-supplied path goes through here: relative paths are resolved against $PWD, never against the process directory.
function Resolve-G5Path([string]$path, [string]$base) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $null }
    if (-not $base) { $base = (Get-Location).ProviderPath }
    if ([IO.Path]::IsPathRooted($path)) { return [IO.Path]::GetFullPath($path) }
    return [IO.Path]::GetFullPath((Join-Path $base $path))
}
# Rev6: read a file another process keeps open for writing (Minecraft latest.log, the backend console log): FileShare.ReadWrite|Delete. A trailing line that
# the writer has not finished (no line break at the end) is dropped when -DropPartialLine is set; the caller records the fact. Never returns $null silently:
# an unreadable file throws, so mandatory evidence is not lost without notice.
function Read-G5Shared([string]$path, [switch]$DropPartialLine) {
    $fs = New-Object IO.FileStream($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $sr = New-Object IO.StreamReader($fs, (New-Object System.Text.UTF8Encoding($false)), $true)
        try { $text = $sr.ReadToEnd() } finally { $sr.Dispose() }
    } finally { $fs.Dispose() }
    $partial = $false
    if ($DropPartialLine -and $text.Length -gt 0 -and $text[$text.Length - 1] -ne "`n") {
        $i = $text.LastIndexOf("`n"); $partial = $true
        $text = $(if ($i -ge 0) { $text.Substring(0, $i + 1) } else { '' })
    }
    return [pscustomobject]@{ text = $text; partial_line_dropped = $partial }
}
function Resolve-G5RunDir([string]$G5RunDir, [string]$OutDir) {
    if ($G5RunDir) { return (Resolve-G5Path $G5RunDir) }
    if (-not $OutDir) { $OutDir = Join-Path (Get-G5RepoRoot) 'g5-results' }
    $cur = Read-G5Json (Join-Path $OutDir 'current-run.json')
    if ($cur -and $cur.run_dir) { return (Resolve-G5Path ([string]$cur.run_dir)) }
    return $null
}
function Get-G5DbState([string]$db) {
    # Read-only (query_only) look at the isolated database: version, integrity, actors and CURRENT profile revisions (no prompts).
    $py = @'
import sqlite3, sys, json
c = sqlite3.connect(sys.argv[1]); c.execute("PRAGMA query_only=ON"); c.row_factory = sqlite3.Row
t = {r[0] for r in c.execute("select name from sqlite_master where type='table'")}
o = {"user_version": c.execute("PRAGMA user_version").fetchone()[0], "integrity": c.execute("PRAGMA integrity_check").fetchone()[0], "actors": [], "profiles": [], "worlds": 0}
if "worlds" in t: o["worlds"] = c.execute("select count(*) from worlds").fetchone()[0]
if "actors" in t: o["actors"] = [dict(r) for r in c.execute("select actor_id, character_id, dimension_id from actors order by actor_id limit 500")]
if "character_profiles" in t:
    o["profiles"] = [dict(r) for r in c.execute("select p.actor_id, p.revision, p.source, p.display_name, p.composition, p.content_sha256 from character_profiles p where p.revision = (select max(revision) from character_profiles q where q.actor_id = p.actor_id) order by p.actor_id limit 500")]
print(json.dumps(o))
'@
    $f = Join-Path ([IO.Path]::GetTempPath()) ("g5-dbstate-" + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.py')
    [IO.File]::WriteAllText($f, $py, (New-Object System.Text.UTF8Encoding($false)))
    try {
        $out = & python -I $f $db 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return (($out | Out-String) | ConvertFrom-Json)
    } catch { return $null } finally { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
}
function Test-G5Secret {   # true when a well-formed local credential exists. The value is never returned or printed.
    $manual = [Environment]::GetEnvironmentVariable('NPCORTEX_CPLUS_F1_TOKEN')
    if ($manual) { return ($manual.Length -ge 24 -and $manual.Length -le 512 -and $manual -notmatch "[\r\n]") }
    $path = [Environment]::GetEnvironmentVariable('NPCORTEX_CPLUS_F1_SECRET_FILE')
    if (-not $path) { $b = $env:LOCALAPPDATA; if (-not $b) { $b = Join-Path $HOME 'AppData\Local' }; $path = Join-Path $b 'NPCortex\bridge\cplus-f1.secret' }
    if (-not [IO.Path]::IsPathRooted($path) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $path).Length -gt 128) { return $false }
    return (([IO.File]::ReadAllText($path, [Text.Encoding]::ASCII)).Trim() -match '^[0-9a-f]{64}$')
}
function Test-G5Isolation {
    # Returns [pscustomobject]@{ ok; checks = list of @{name; ok; detail}; marker; db; run_dir }. ok only when EVERY check passes.
    param([string]$G5RunDir, [string]$Database = '', [switch]$RequireLiveBackend, [string]$RepoRoot)
    if (-not $RepoRoot) { $RepoRoot = Get-G5RepoRoot }
    $checks = New-Object System.Collections.ArrayList
    $add = { param($n, $ok, $d) [void]$checks.Add([ordered]@{ name = $n; ok = [bool]$ok; detail = $d }) }
    $res = [ordered]@{ ok = $false; checks = $checks; marker = $null; db = $null; run_dir = $G5RunDir }
    if (-not $G5RunDir -or -not (Test-Path -LiteralPath $G5RunDir -PathType Container)) {
        & $add 'run_dir' $false 'no isolated G5 run folder (start one with scripts/start-g5-backend.ps1)'; return [pscustomobject]$res
    }
    $runFull = (Resolve-G5Path $G5RunDir).TrimEnd('\')
    $res.run_dir = $runFull
    $marker = Read-G5Json (Join-Path $runFull 'G5-ISOLATED.marker.json')
    if (-not $marker -or $marker.kind -ne $G5_MARKER_KIND -or -not $marker.run_id -or -not $marker.db_path) {
        & $add 'marker' $false 'G5-ISOLATED.marker.json is missing or invalid: the database cannot be verified as isolated'; return [pscustomobject]$res
    }
    $res.marker = $marker
    & $add 'marker' $true "run $($marker.run_id)"
    $dbFull = [IO.Path]::GetFullPath([string]$marker.db_path)
    $res.db = $dbFull
    & $add 'db_absolute_in_run_dir' ([IO.Path]::IsPathRooted([string]$marker.db_path) -and $dbFull.StartsWith($runFull + '\', [StringComparison]::OrdinalIgnoreCase)) "the database must live inside the run folder: $dbFull"
    $live = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'backend/data/npcortex.sqlite3'))
    $cand = @($live); if ($marker.repo_default_db) { $cand += [IO.Path]::GetFullPath([string]$marker.repo_default_db) }
    $isDefault = [bool]($cand | Where-Object { $_ -ieq $dbFull }) -or ((Split-Path $dbFull -Parent) -ieq (Split-Path $live -Parent))
    & $add 'not_default_database' (-not $isDefault) 'the repository default database (backend/data) is never an isolated test database'
    if ($Database) { & $add 'database_argument_matches_marker' ((Resolve-G5Path $Database) -ieq $dbFull) "-Database must equal the marker database ($dbFull)" }
    # a junction/symlink ANYWHERE on the path (up to the drive root) could make a "run folder" an alias of backend/data
    $reparse = $null; $p = $dbFull
    while ($p -and (Test-Path -LiteralPath $p)) {
        if ((Get-Item -LiteralPath $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { $reparse = $p; break }
        $p = Split-Path $p -Parent
    }
    & $add 'no_links' (-not $reparse) $(if ($reparse) { "junction or symbolic link on the path: $reparse" } else { 'no junction or symbolic link anywhere on the path of the database' })
    & $add 'plain_path_spelling' (([string]$marker.db_path) -notmatch '^\\\\[?.]\\' -and ([string]$marker.db_path) -notmatch '~\d' -and ([string]$marker.db_path) -notmatch '[\*?<>|"]') 'no \\?\ prefix, no 8.3 short names, no wildcard characters in the database path'
    $backendDir = [IO.Path]::GetFullPath((Join-Path $RepoRoot 'backend')).TrimEnd('\')
    & $add 'not_under_backend_dir' (-not ($dbFull + '\').StartsWith($backendDir + '\', [StringComparison]::OrdinalIgnoreCase)) 'an isolated database never lives under the repository backend folder'
    if (Test-Path -LiteralPath $dbFull -PathType Leaf) {
        $st = Get-G5DbState $dbFull
        & $add 'database_readable_v5' ($st -and $st.user_version -eq 5 -and $st.integrity -eq 'ok') $(if ($st) { "user_version=$($st.user_version) integrity=$($st.integrity)" } else { 'cannot read the database with python/sqlite3' })
    } else { & $add 'database_exists' $false "database file not found: $dbFull" }
    if ($RequireLiveBackend) {
        $rec = Read-G5Json (Join-Path $runFull 'backend-run.json')
        if (-not $rec -or -not $rec.pid -or -not $rec.port) { & $add 'backend_record' $false 'backend-run.json is missing: the running backend was not started by scripts/start-g5-backend.ps1' }
        else {
            & $add 'backend_record_db' (([IO.Path]::GetFullPath([string]$rec.db_path)) -ieq $dbFull) 'the backend record names the marker database'
            $listener = @(Get-NetTCPConnection -State Listen -LocalPort ([int]$rec.port) -ErrorAction SilentlyContinue)
            $owner = 0; if ($listener.Count) { $owner = [int]$listener[0].OwningProcess }
            $parent = 0; if ($owner) { try { $parent = [int](Get-CimInstance Win32_Process -Filter "ProcessId=$owner").ParentProcessId } catch { } }
            & $add 'port_owner_is_recorded_backend' ($owner -ne 0 -and ($owner -eq [int]$rec.pid -or $parent -eq [int]$rec.pid)) "port $($rec.port) is served by pid $owner (recorded backend pid $($rec.pid))"
            & $add 'backend_process_alive' ($null -ne (Get-Process -Id ([int]$rec.pid) -ErrorAction SilentlyContinue)) 'the recorded backend process is running'
            $health = $null; try { $health = (Invoke-WebRequest -UseBasicParsing -Uri ("http://127.0.0.1:$($rec.port)/health") -TimeoutSec 4).Content | ConvertFrom-Json } catch { }
            & $add 'backend_health' ($health -and $health.status -eq 'ok') 'GET /health answers ok'
        }
    }
    $res.ok = -not ($checks | Where-Object { -not $_.ok })
    return [pscustomobject]$res
}
