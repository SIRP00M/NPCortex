# NPCortex G5 preflight (TEST ONLY, read-only). Fails closed: exit 0 = PASS, exit 1 = FAIL. Writes <run folder>/preflight.json.
# It never writes to a database, a world or a journal, never resets anything, and never prints the local credential.
#   -Phase Start   a fresh disposable world + a fresh isolated backend: no worlds/actors/profiles in the database, no C1 identities and
#                  no M03 evidence in the world, the G5 data pack installed unchanged
#   -Phase Before  between scenario groups: every actor in the database is a G5 lane with a profile, and no lane has a non-terminal journal
#                  (such a lane is reported, never reset: resolve it by hand or use a fresh lane name)
# A lane without a database row is "not registered yet" - expected before its first C2 goal (default-mode NPCs are registered by C2 itself).
# It is only a FAILURE when C2 already tried and the registration was rejected (the collector classifies that from the logs).
param(
    [ValidateSet('Start', 'Before')][string]$Phase = 'Start',
    [string]$G5RunDir,
    [string]$WorldName,
    [string]$RunDir,
    [string]$OutDir,
    [int]$Lanes = 10,
    [switch]$NoLiveBackend,
    [switch]$Quiet
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'g5-common.ps1')
if (-not $OutDir) { $OutDir = Join-Path $root 'g5-results' }
if (-not $RunDir) { $RunDir = Join-Path $root 'forge-mod/run' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$checks = New-Object System.Collections.ArrayList
function Add-Check([string]$name, [bool]$ok, [string]$detail) { [void]$checks.Add([ordered]@{ name = $name; ok = $ok; detail = $detail }) }

$runFull = Resolve-G5RunDir $G5RunDir $OutDir
$iso = Test-G5Isolation -G5RunDir $runFull -RequireLiveBackend:(-not $NoLiveBackend) -RepoRoot $root
foreach ($c in $iso.checks) { Add-Check ("isolation." + $c.name) $c.ok $c.detail }

Add-Check 'local_credential' (Test-G5Secret) 'a well-formed local credential exists (the value is never shown); without it every C2 registration is rejected with 403'
Add-Check 'lane_count' ($Lanes -ge 1 -and $Lanes -le 10) "requested lanes: $Lanes (C2 MAX_ACTORS is 10 and is not changed)"

$laneInfo = @()
if ($iso.db -and (Test-Path -LiteralPath $iso.db -PathType Leaf)) {
    $st = Get-G5DbState $iso.db
    if ($st) {
        $byActor = @{}; foreach ($p in @($st.profiles)) { $byActor[[string]$p.actor_id] = $p }
        if ($Phase -eq 'Start') {
            Add-Check 'database_fresh' (@($st.actors).Count -eq 0 -and @($st.profiles).Count -eq 0 -and [int]$st.worlds -eq 0) "worlds=$($st.worlds) actors=$(@($st.actors).Count) profiles=$(@($st.profiles).Count) (a run starts on an empty database)"
        } else {
            $foreign = @(@($st.actors) | Where-Object { $p = $byActor[[string]$_.actor_id]; -not $p -or $laneNames -notcontains [string]$p.display_name })
            Add-Check 'only_g5_lanes_in_database' ($foreign.Count -eq 0) $(if ($foreign.Count) { "$($foreign.Count) actor(s) that are not G5 lanes: $((@($foreign | ForEach-Object { $_.actor_id }) -join ', '))" } else { 'every actor is a G5 lane with a profile' })
        }
        foreach ($nm in $laneNames[0..([math]::Min($Lanes, 10) - 1)]) {
            $p = @($st.profiles | Where-Object { [string]$_.display_name -eq $nm }) | Select-Object -First 1
            $laneInfo += [ordered]@{ lane = $nm; registration = $(if ($p) { "BOUND source=$($p.source) revision=$($p.revision)" } else { 'NOT_REGISTERED_YET (expected before the first C2 goal)' }) }
        }
    } else { Add-Check 'database_state' $false 'the isolated database could not be read' }
}

if ($WorldName) {
    $world = Join-Path (Join-Path $RunDir 'saves') $WorldName
    Add-Check 'world_exists' (Test-Path -LiteralPath $world -PathType Container) "world folder: $world"
    if (Test-Path -LiteralPath $world) {
        $c1 = Get-ChildItem -LiteralPath (Join-Path $world 'data') -File -Filter 'npcortex_c1_p0.dat' -ErrorAction SilentlyContinue
        $evDir = Join-Path $world 'data/npcortex/m03-evidence'
        $evFiles = @(Get-ChildItem -LiteralPath $evDir -File -Filter *.json -ErrorAction SilentlyContinue)
        if ($Phase -eq 'Start') {
            Add-Check 'world_fresh_no_npc_identities' (-not $c1) 'a fresh world has no saved C1 NPC identities (npcortex_c1_p0.dat)'
            Add-Check 'world_fresh_no_m03_evidence' ($evFiles.Count -eq 0) "m03-evidence files: $($evFiles.Count)"
        } else {
            $latest = @{}
            foreach ($f in ($evFiles | Sort-Object LastWriteTime)) { $o = Read-G5Json $f.FullName; if ($o -and $o.name -and $o.c2_journal) { $latest[[string]$o.name] = $o.c2_journal.state } }
            $active = @($laneNames | Where-Object { $latest.ContainsKey($_) -and (Test-G5Active $latest[$_]) })
            Add-Check 'no_lane_with_active_journal' ($active.Count -eq 0) $(if ($active.Count) { "lane(s) with a non-terminal journal: $(($active | ForEach-Object { "$_=$($latest[$_])" }) -join ', '). NOT reset by this tool: resolve by hand or use a new lane name." } else { 'no lane has a non-terminal journal in the newest snapshots' })
        }
        $dpWorld = Join-Path $world 'datapacks/NPCortex_G5'; $dpRepo = Join-Path $root 'integrations/datapacks/NPCortex_G5'
        if ((Test-Path $dpWorld) -and (Test-Path $dpRepo)) {
            $h = { param($p) (Get-ChildItem -Recurse -File -LiteralPath $p | Sort-Object FullName | ForEach-Object { (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash }) -join '' }
            Add-Check 'datapack_identical_to_repo' ((& $h $dpWorld) -eq (& $h $dpRepo)) 'the installed NPCortex_G5 pack equals integrations/datapacks/NPCortex_G5'
        } else { Add-Check 'datapack_installed' $false 'the NPCortex_G5 data pack is not installed in the world (scripts\install-g5-datapack.ps1)' }
    }
}

$pass = -not ($checks | Where-Object { -not $_.ok })
$runId = if ($iso.marker) { [string]$iso.marker.run_id } else { $null }
$result = [ordered]@{ status = $(if ($pass) { 'PASS' } else { 'FAIL' }); phase = $Phase; run_id = $runId; db_path = $iso.db; world = $WorldName; checked_utc = (Get-Date).ToUniversalTime().ToString('s') + 'Z'
                      note = 'PASS means the prerequisites hold; it is not a test verdict. A failed prerequisite stops the sequence: nothing is reset or repaired automatically.'; lanes = $laneInfo; checks = $checks }
if ($runFull -and (Test-Path -LiteralPath $runFull -PathType Container)) { [IO.File]::WriteAllText((Join-Path $runFull 'preflight.json'), ($result | ConvertTo-Json -Depth 6), $utf8) }
if (-not $Quiet) {
    foreach ($c in $checks) { Write-Host ("  [{0}] {1}: {2}" -f $(if ($c.ok) { 'ok  ' } else { 'FAIL' }), $c.name, $c.detail) -ForegroundColor $(if ($c.ok) { 'DarkGray' } else { 'Red' }) }
    foreach ($l in $laneInfo) { Write-Host ("  lane {0}: {1}" -f $l.lane, $l.registration) -ForegroundColor DarkGray }
    Write-Host ("G5 PREFLIGHT {0} ({1})" -f $result.status, $Phase) -ForegroundColor $(if ($pass) { 'Green' } else { 'Red' })
}
$result | ConvertTo-Json -Depth 6 -Compress | Write-Output
exit $(if ($pass) { 0 } else { 1 })
