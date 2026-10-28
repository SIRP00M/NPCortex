# TEST-ONLY: exercises collect-g5-evidence.ps1 on SYNTHETIC fixtures (scripts/g5-collector-fixture.js) and one REAL Forge log excerpt.
# Proves parsing/verdict rules, the ZIP layout and the "no independent evidence => no PASS" property.
# It says nothing about NPCortex or Minecraft behaviour.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("g5-collector-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$fails = New-Object System.Collections.ArrayList; $checks = 0
function Check($c, $m) { $script:checks++; if (-not $c) { [void]$script:fails.Add($m) } }
Add-Type -AssemblyName System.IO.Compression.FileSystem
function ReadEntry($z, $name) { $e = $z.Entries | Where-Object { $_.FullName -eq $name }; if (-not $e) { return $null }; $r = New-Object IO.StreamReader($e.Open()); $t = $r.ReadToEnd(); $r.Dispose(); return $t }
foreach ($mode in 'ok', 'fb', 'nogo', 'timer', 'hang', 'nolog', 'real', 'realp00', 'inconsistent', 'dupterm', 'nosnap', 'stale', 'staleperf', 'regfail', 'regfailnojournal', 'noprofiles', 'dupactor', 'nodiag', 'diagactive', 'diagmismatch', 'p08other', 'nofinalize', 'wrongtask', 'nog3', 'taint', 'nobacklog', 'oldgate', 'realp11') {
    $fx = Join-Path $tmp $mode
    & node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $fx $mode | Out-Null
    $null = & (Join-Path $PSScriptRoot 'collect-g5-evidence.ps1') -WorldName NPCortex_G5_fixture -RunDir (Join-Path $fx 'run') -G5RunDir (Join-Path $fx 'g5/run-fixture') -BackendUrl 'http://127.0.0.1:1' -OutDir (Join-Path $fx 'out')
    $zip = Get-ChildItem (Join-Path $fx 'out') -Filter 'NPCortex-G5-TestResults-*.zip' | Select-Object -First 1
    Check ($null -ne $zip) "$mode zip created"
    $z = [IO.Compression.ZipFile]::OpenRead($zip.FullName)
    $names = $z.Entries | ForEach-Object { $_.FullName }
    Check (-not ($names | Where-Object { $_.IndexOf([char]92) -ge 0 })) "$mode zip entries use forward slashes"
    foreach ($req in 'summary.json', 'manifest.json', 'REPORT.md', 'meta/environment.json', 'logs/g5-log-extract.txt', 'backend/db-summary.json') { Check ($names -contains $req) "$mode has $req" }
    Check (($names -contains 'backend/backend.err.log') -eq ($mode -ne 'nobacklog')) "$mode backend console log discovered from backend-run.json (present unless the log is missing)"
    Check (-not ($names | Where-Object { $_ -match 'secret|token' })) "$mode no secret-looking file names"
    $logText = ReadEntry $z 'logs/latest.log'
    if ($mode -notin 'nolog', 'real', 'realp00', 'realp11') { Check ($logText -notmatch 'SECRETTOKENVALUE|hunter2|abcDEF123456') "$mode secrets redacted from the copied log"; Check ($logText -match 'REDACTED') "$mode redaction marker present" }
    $extract = ReadEntry $z 'logs/g5-log-extract.txt'
    if ($mode -ne 'nolog') { Check ($extract -match '(?m)^\d\d:\d\d:\d\d (BEGIN|OBS|END|START) ') "$mode Forge-format timestamps were parsed into G5 markers" }
    $sum = (ReadEntry $z 'summary.json') | ConvertFrom-Json
    $rep = ReadEntry $z 'REPORT.md'
    $z.Dispose()
    $st = @{}; $why = @{}; $sum.scenarios | ForEach-Object { $st[$_.id] = $_.status; $why[$_.id] = $_.reason }
    $pass = @($st.Values | Where-Object { $_ -eq 'PASS' }).Count
    switch ($mode) {
        { $_ -in 'ok', 'fb', 'nogo', 'nodiag', 'diagactive', 'diagmismatch', 'p08other', 'nofinalize', 'wrongtask', 'nog3', 'taint', 'nobacklog', 'oldgate' } {
            $mustPass = 'p00_capability_probe', 'p02_navlong_20', 'p03_navlong_40', 'p04_navlong_80', 'p05_navlong_150', 'p06_navlong_diag60', 'p07_small_wall_detour', 'p09_cancel_leg_active', 'p11_savequit_checkpoint', 'p12_rejoin_no_replay', 'p13_new_navlong_after_recovery', 'p14_regress_c2_navigate', 'p15_regress_c2_move', 'p16_regress_follow_start', 'p17_regress_follow_stop', 'p19_multi_1_npc', 'p20_multi_3_npc', 'p21_multi_5_npc', 'p22_multi_10_npc'
            foreach ($id in $mustPass) { Check ($st[$id] -eq 'PASS') "${mode}: $id should PASS, got $($st[$id]) ($($why[$id]))" }
            $e10 = ($sum.scenarios | Where-Object { $_.id -eq 'p10_cancel_quiescing' }).evidence
            $e08 = ($sum.scenarios | Where-Object { $_.id -eq 'p08_large_wall_no_progress' }).evidence
            $e18 = ($sum.scenarios | Where-Object { $_.id -eq 'p18_regress_e3_taskmove' }).evidence
            $gated = ($mode -in 'fb', 'nogo')
            Check ($sum.collector_version -eq 'rev6.0' -and $rep -match 'collector rev6.0') "${mode}: the report and summary carry the collector version"
            Check ($sum.status_dimensions.performance -eq 'MEASURED_NOT_ACCEPTED' -and $sum.status_dimensions.owner_gate -eq 'SAFE_PROGRESSION_ONLY_NOT_AN_ACCEPTANCE_VERDICT' -and $sum.status_dimensions.milestone -like 'NOT_ACCEPTED_BY_THE_COLLECTOR*') "${mode}: status dimensions are separate and never claim milestone acceptance"
            # ---- p08: the reason comes from the production diagnostic only
            switch ($mode) {
                'nodiag' { Check ($st['p08_large_wall_no_progress'] -eq 'UNVERIFIED' -and $why['p08_large_wall_no_progress'] -match 'no D1 diagnostic') "nodiag: without the D1-A line p08 stays UNVERIFIED, got $($st['p08_large_wall_no_progress']) ($($why['p08_large_wall_no_progress']))" }
                'p08other' { Check ($st['p08_large_wall_no_progress'] -eq 'FAIL' -and $why['p08_large_wall_no_progress'] -match 'LONG_LEG_LIMIT' -and $why['p08_large_wall_no_progress'] -match 'scenario expectation') "p08other: a reason other than NO_PROGRESS/OSCILLATION is reported, never forced to PASS, got $($st['p08_large_wall_no_progress']) ($($why['p08_large_wall_no_progress']))" }
                default { Check ($st['p08_large_wall_no_progress'] -eq 'PASS' -and $e08.failure_reason -like 'LONG_NO_PROGRESS*') "${mode}: p08 PASS only because D1-A reports LONG_NO_PROGRESS for the same actor/goal, got $($st['p08_large_wall_no_progress']) ($($why['p08_large_wall_no_progress']))" }
            }
            # ---- p18: movement alone is never acceptance
            switch ($mode) {
                'nofinalize' { Check ($st['p18_regress_e3_taskmove'] -eq 'PENDING' -and $why['p18_regress_e3_taskmove'] -match 'taskmovefinalize' -and $why['p18_regress_e3_taskmove'] -match 'taskmovestatus' -or ($st['p18_regress_e3_taskmove'] -eq 'PENDING' -and $why['p18_regress_e3_taskmove'] -match 'taskmovefinalize')) "nofinalize: without the typed answers p18 stays PENDING and names what is missing, got $($st['p18_regress_e3_taskmove']) ($($why['p18_regress_e3_taskmove']))" }
                'wrongtask' { Check ($st['p18_regress_e3_taskmove'] -eq 'FAIL' -and $why['p18_regress_e3_taskmove'] -match 'another task') "wrongtask: a finalize answer for another task is INCONSISTENT_EVIDENCE, got $($st['p18_regress_e3_taskmove']) ($($why['p18_regress_e3_taskmove']))" }
                'nog3' { Check ($st['p18_regress_e3_taskmove'] -eq 'PENDING' -and $why['p18_regress_e3_taskmove'] -match 'cplus_e3_g3_terminals') "nog3: typed answers without the durable database terminal stay PENDING, got $($st['p18_regress_e3_taskmove']) ($($why['p18_regress_e3_taskmove']))" }
                default { Check ($st['p18_regress_e3_taskmove'] -eq 'PASS' -and $e18.e3.task_id -eq 'SIM-TASK' -and $e18.e3.g3_terminal_outcome -eq 'COMPLETED') "${mode}: p18 PASS needs the typed finalize + status answers AND the durable G3 terminal, got $($st['p18_regress_e3_taskmove']) ($($why['p18_regress_e3_taskmove']))" }
            }
            # ---- p10: up to five attempts, coverage only from the production diagnostic
            if ($gated) {
                Check ($st['p10_cancel_quiescing'] -eq 'PENDING' -and $why['p10_cancel_quiescing'] -match 'no cancel was issued') "fb: p10 cannot cancel without in-game observation => PENDING (never a PASS), got $($st['p10_cancel_quiescing']) ($($why['p10_cancel_quiescing']))"
                Check ($sum.mode -eq 'OWNER_GATED') "${mode}: mode OWNER_GATED, got $($sum.mode)"
                Check ($why['p02_navlong_20'] -match 'owner-gated mode') "${mode}: verdict states it came from snapshots + owner gate only"
                Check ($sum.owner_gate.awaited -eq 21 -and $sum.owner_gate.released -eq 21 -and $sum.owner_gate.awaiting_never_released -eq 0) "${mode}: all 21 scenarios awaited and were released ($($sum.owner_gate | ConvertTo-Json -Compress))"
                if ($mode -eq 'fb') { Check ($sum.owner_gate.released_with_recorded_go -eq 21) "fb: every release has a recorded GO of g5-gate.ps1 for THIS run ($($sum.owner_gate.released_with_recorded_go))" }
                else { Check ($sum.owner_gate.released_without_recorded_go -eq 21 -and (($sum.notes -join ' ') -match 'without a GO decision')) 'nogo: releases without a recorded GO are reported (verdicts unchanged, progression unverified)' }
            } else {
                Check ($sum.mode -eq 'ADAPTIVE') "${mode}: mode ADAPTIVE, got $($sum.mode)"
                Check ($e10.attempts_run -eq 5 -and $e10.attempts.Count -eq 5) "${mode}: p10 ran exactly 5 attempts ($($e10.attempts_run))"
                Check ((@($e10.attempts | ForEach-Object { $_.goal } | Select-Object -Unique).Count -eq 5) -and (@($e10.attempts | Where-Object { $_.safety -eq 'PASS' }).Count -eq 5)) "${mode}: every attempt has its own goal and a PASS cancellation safety ($(@($e10.attempts | ForEach-Object { $_.safety }) -join ','))"
                switch ($mode) {
                    'nodiag' { Check ($st['p10_cancel_quiescing'] -eq 'UNVERIFIED' -and $e10.leg_quiescing_coverage -like 'UNVERIFIED*' -and $why['p10_cancel_quiescing'] -match 'safety PASS') "nodiag: without D1-B the coverage is UNVERIFIED although cancellation was safe, got $($st['p10_cancel_quiescing']) ($($why['p10_cancel_quiescing']))" }
                    'diagactive' { Check ($st['p10_cancel_quiescing'] -eq 'UNVERIFIED' -and (@($e10.attempts | Where-Object { $_.coverage -eq 'NOT_COVERED' -and $_.diag_long -eq 'LEG_ACTIVE' }).Count -eq 5)) "diagactive: D1-B says the cancels hit LEG_ACTIVE => UNVERIFIED (not a fabricated PASS, not a FAIL), got $($st['p10_cancel_quiescing']) ($($why['p10_cancel_quiescing']))" }
                    'diagmismatch' { Check ($st['p10_cancel_quiescing'] -eq 'UNVERIFIED' -and (@($e10.attempts | Where-Object { $_.coverage -eq 'UNVERIFIED' -and $_.coverage_why -match 'another actor/goal' }).Count -eq 5)) "diagmismatch: a diagnostic naming another goal never counts as coverage, got $($st['p10_cancel_quiescing']) ($($why['p10_cancel_quiescing']))" }
                    default {
                        Check ($st['p10_cancel_quiescing'] -eq 'PASS' -and $e10.leg_quiescing_coverage -like 'COVERED*' -and $e10.attempts[0].diag_long -eq 'LEG_QUIESCING' -and $e10.attempts[0].coverage -eq 'COVERED') "${mode}: p10 PASS because D1-B recorded long=LEG_QUIESCING for the same actor and goal, got $($st['p10_cancel_quiescing']) ($($why['p10_cancel_quiescing']))"
                        Check ($e10.cancellation_safety -like 'PASS*') "${mode}: cancellation safety is reported separately ($($e10.cancellation_safety))"
                    }
                }
                if ($mode -eq 'oldgate') {
                    Check ($sum.owner_gate.released_with_recorded_go -eq 1 -and $sum.owner_gate.released_without_recorded_go -eq 1) "oldgate: only the gate row of THIS run counts (older-run rows and rows without a run id are ignored), got $($sum.owner_gate | ConvertTo-Json -Compress)"
                    Check (($sum.notes -join ' ') -match 'not attributed to this run') 'oldgate: ignored rows are reported'
                } else {
                    Check ($sum.owner_gate.released_with_recorded_go -ge 1 -and $sum.owner_gate.released_without_recorded_go -eq 0) "${mode}: gate rows of this run are credited ($($sum.owner_gate | ConvertTo-Json -Compress))"
                }
            }
            # ---- backend logs, health, performance taint, evidence gaps
            if ($mode -eq 'nobacklog') { Check (@($sum.evidence_gaps | Where-Object { $_ -match 'backend log .* not found' }).Count -ge 1) "nobacklog: a missing mandatory backend log is reported as an evidence gap ($($sum.evidence_gaps -join ' | '))" }
            else { Check ($sum.evidence_gaps.Count -eq 0) "${mode}: no evidence gaps ($($sum.evidence_gaps -join ' | '))"; Check ($sum.backend.logs.'backend.err.log'.lines -eq 3 -and $sum.backend.logs.'backend.err.log'.http_4xx_5xx -eq 0) "${mode}: backend console log read through a shared read, the unfinished last line dropped ($($sum.backend.logs | ConvertTo-Json -Compress))" }
            Check ($sum.backend.health_state -match 'HEALTHY_BEFORE_STOP_UNREACHABLE_AFTER_SHUTDOWN') "${mode}: post-shutdown UNREACHABLE is told apart from a failure during gameplay ($($sum.backend.health_state))"
            $pf22 = ($sum.scenarios | Where-Object { $_.id -eq 'p22_multi_10_npc' }).evidence.performance; $pf20 = ($sum.scenarios | Where-Object { $_.id -eq 'p20_multi_3_npc' }).evidence.performance
            Check ($pf20.first_sample_contamination -eq 'not detected' -and $pf20.acceptance -eq 'MEASURED_NOT_ACCEPTED') "${mode}: a clean performance record is not flagged and is still not accepted"
            if ($mode -eq 'taint') { Check ($pf22.first_sample_contamination -like 'SUSPECTED*' -and $pf22.usable_metrics -eq 'tick_event_p95_ms, server_average_tick_ms' -and $pf22.tick_event_max_ms_raw -eq 207341813.263 -and -not ($pf22.PSObject.Properties.Name -contains 'tick_event_mean_ms')) 'taint: a first-sample-contaminated record is flagged, shown raw only, and its mean/max are not offered as usable'; Check (($sum.notes -join ' ') -match 'first-sample contamination') 'taint: noted in the report notes' }
            $c = $sum.correlation | Where-Object { $_.scenario -eq 'p02_navlong_20' }
            Check ($c.goal -match '^[0-9a-f-]{36}$' -and $c.action -match '^[0-9a-f-]{36}$' -and $c.terminal -eq 'COMPLETED') "${mode}: p02 correlates one goal id, one action id and one COMPLETED terminal ($($c | ConvertTo-Json -Compress))"
            Check ($c.states -match 'MOVING') "${mode}: journal states recorded for p02 ($($c.states))"
            Check ($rep -match 'Correlation \(scenario') "${mode}: REPORT carries the correlation table"
            $c9 = $sum.scenarios | Where-Object { $_.id -eq 'p09_cancel_leg_active' }; Check ($c9.evidence.journal_state_at_cancel -eq 'MOVING') "${mode}: p09 journal state at cancel is MOVING"
            $m22 = $sum.scenarios | Where-Object { $_.id -eq 'p22_multi_10_npc' }
            Check ($m22.evidence.lanes.Count -eq 10 -and @($m22.evidence.lanes | Where-Object { $_.status -eq 'PASS' -and $_.registration -eq 'BOUND' }).Count -eq 10) "${mode}: p22 lists 10 lanes, each PASS with its profile BOUND ($(@($m22.evidence.lanes | ForEach-Object { $_.status }) -join ','))"
            Check ((@($m22.evidence.lanes | ForEach-Object { $_.goal } | Select-Object -Unique).Count -eq 10) -and (@($m22.evidence.lanes | ForEach-Object { $_.actor_id } | Select-Object -Unique).Count -eq 10) -and (@($m22.evidence.lanes | ForEach-Object { $_.game_profile_id } | Select-Object -Unique).Count -eq 10)) "${mode}: p22 lanes have 10 distinct goal ids, 10 distinct actor ids and 10 distinct GameProfile ids"
            Check ($m22.evidence.lanes[0].lane -eq 'yui' -and $m22.evidence.lanes[1].lane -eq 'g5a02' -and $m22.evidence.lanes[9].lane -eq 'g5a10') "${mode}: fresh g5a lane identities"
            Check ($sum.isolation.run_id -eq 'fixture' -and $sum.isolation.preflight -eq 'PASS') "${mode}: the summary records the isolation proof"
        }
        'realp11' {
            # REAL Rev5 evidence: an earlier HTTP_PENDING snapshot (1.0 s before the checkpoint) and the MOVING snapshot taken in the checkpoint tick. Rev5 picked the first one and reported PENDING.
            $e11 = ($sum.scenarios | Where-Object { $_.id -eq 'p11_savequit_checkpoint' }).evidence
            Check ($st['p11_savequit_checkpoint'] -eq 'PASS') "realp11: the checkpoint-correlated MOVING snapshot qualifies, got $($st['p11_savequit_checkpoint']) ($($why['p11_savequit_checkpoint']))"
            Check ($e11.checkpoint_snapshot.state -eq 'MOVING' -and $e11.checkpoint_snapshot.candidates -eq 2 -and $e11.checkpoint_snapshot.goal -eq '511414dd-0935-4f0d-a38e-76f6729b8a1a' -and $e11.checkpoint_snapshot.action -eq 'ecf45fc8-6bad-4e9d-8240-cd1fd08f9cc0') "realp11: both candidates seen, the MOVING one chosen, goal and action ids preserved ($($e11.checkpoint_snapshot | ConvertTo-Json -Compress))"
            Check ([double]$e11.checkpoint_snapshot.offset_s -eq 0) 'realp11: the chosen snapshot is the one taken in the checkpoint tick (offset 0.00 s)'
            Check ($sum.collector_version -eq 'rev6.0' -and $rep -match 'collector rev6.0') 'realp11: the corrected verdict is a NEW versioned report'
            Check ($pass -eq 1) "realp11: only p11 passes, got $pass"
        }
        'timer' {
            Check ($st['p03_navlong_40'] -eq 'FAIL' -and $why['p03_navlong_40'] -match 'timer-only') "timer: a scenario that ended without a recorded gate release FAILs, got $($st['p03_navlong_40']) ($($why['p03_navlong_40']))"
            Check ($st['p02_navlong_20'] -eq 'PASS' -and $st['p04_navlong_80'] -eq 'PASS') 'timer: neighbouring scenarios unaffected'
        }
        'hang' {
            Check ($st['p03_navlong_40'] -eq 'PENDING' -and $why['p03_navlong_40'] -match 'AWAITING_OWNER_GATE') "hang: a never-released scenario is PENDING, got $($st['p03_navlong_40']) ($($why['p03_navlong_40']))"
            Check ($pass -le 1 -and $sum.owner_gate.awaiting_never_released -eq 1) "hang: nothing passes beyond the probe ($pass), one scenario awaiting"
        }
        'realp00' {
            Check ($sum.mode -eq 'OWNER_GATED') "realp00: the real revision-3 log (mode=FALLBACK) is read as OWNER_GATED, got $($sum.mode)"
            Check ($st['p00_capability_probe'] -eq 'PASS' -and $why['p00_capability_probe'] -match 'LEGACY rev3 probe' -and $why['p00_capability_probe'] -match 'selector=NONE') "realp00: p00 PASS from the real log + real M03 snapshots, flagged as legacy vocabulary ($($why['p00_capability_probe']))"
            Check ($sum.probe_capabilities.selector -like 'NONE*' -and $sum.probe_capabilities.mode -like 'FALLBACK*') 'realp00: real probe lines parsed (selector=NONE, mode=FALLBACK)'
            Check (([regex]::Matches($extract, 'BEGIN p00_capability_probe')).Count -eq 1) 'realp00: the client-chat echo is not double counted'
            Check ($pass -eq 1) "realp00: only the probe can pass, got $pass"
            Check ($st['p02_navlong_20'] -eq 'PENDING') 'realp00: scenarios that never began stay PENDING'
        }
        'nolog' { Check ($pass -eq 0) 'nolog: no scenario may PASS without log evidence'; Check (@($st.Values | Where-Object { $_ -eq 'PENDING' }).Count -ge 20) 'nolog: scenarios are PENDING' }
        'real' {
            # real excerpt of forge-mod/run/logs/latest.log (first run, selector used the character name): p01/p02 BLOCKED_NPC_NOT_SELECTABLE
            Check ($extract -match '06:35:28 START ALL') 'real: [27Oct2026 06:35:28.258] [Dev] [G5] START ALL parsed as 06:35:28'
            Check (([regex]::Matches($extract, 'BEGIN p02_navlong_20')).Count -eq 1) 'real: the client-chat echo of each marker is not double counted'
            Check ($st['p02_navlong_20'] -eq 'BLOCKED') "real: p02 BLOCKED, got $($st['p02_navlong_20'])"
            Check ($pass -eq 0) 'real: nothing passes when the NPC was never observed'
            Check ($st['p05_navlong_150'] -eq 'PENDING' -and $st['p00_capability_probe'] -eq 'PENDING') 'real: scenarios that never began stay PENDING'
        }
        'inconsistent' {
            Check ($st['p03_navlong_40'] -eq 'FAIL' -and $why['p03_navlong_40'] -match 'INCONSISTENT_EVIDENCE') "inconsistent: selector/snapshot disagreement fails p03, got $($st['p03_navlong_40']) ($($why['p03_navlong_40']))"
            Check ($st['p02_navlong_20'] -eq 'PASS' -and $st['p04_navlong_80'] -eq 'PASS') 'inconsistent: neighbouring scenarios unaffected'
        }
        { $_ -in 'regfail', 'regfailnojournal' } {
            $m20 = $sum.scenarios | Where-Object { $_.id -eq 'p20_multi_3_npc' }; $m19 = $sum.scenarios | Where-Object { $_.id -eq 'p19_multi_1_npc' }
            Check ($st['p20_multi_3_npc'] -eq 'FAIL' -and $why['p20_multi_3_npc'] -match 'Character Profile was NOT registered') "regfail: a lane whose profile registration was rejected FAILs the scenario as a prerequisite failure, got $($st['p20_multi_3_npc']) ($($why['p20_multi_3_npc']))"
            $l3 = $m20.evidence.lanes | Where-Object { $_.lane -eq 'g5a03' }; $l2 = $m20.evidence.lanes | Where-Object { $_.lane -eq 'g5a02' }
            Check ($l3.registration -eq 'REGISTRATION_FAILED' -and $l3.status -eq 'FAIL') "${mode}: lane g5a03 is classified REGISTRATION_FAILED ($($l3 | ConvertTo-Json -Compress))"
            Check ($l2.registration -eq 'BOUND' -and $l2.status -eq 'PASS') 'regfail: the other lanes are judged independently and stay PASS'
            Check ($st['p19_multi_1_npc'] -eq 'PASS') "regfail: the single-lane scenario is unaffected, got $($st['p19_multi_1_npc'])"
            Check ($rep -match 'Lane-by-lane') 'regfail: REPORT carries the lane table'
        }
        'staleperf' {
            foreach ($pid2 in 'p19_multi_1_npc', 'p20_multi_3_npc', 'p21_multi_5_npc', 'p22_multi_10_npc') { Check ($st[$pid2] -eq 'PENDING' -and $why[$pid2] -match 'performance record') "staleperf: an older run's performance record is never credited to $pid2, got $($st[$pid2]) ($($why[$pid2]))" }
        }
        'noprofiles' {
            Check ($st['p20_multi_3_npc'] -eq 'PENDING' -and $why['p20_multi_3_npc'] -match 'not evidenced') "noprofiles: without profile rows the profile binding is not evidenced => PENDING (never PASS), got $($st['p20_multi_3_npc']) ($($why['p20_multi_3_npc']))"
            Check ($st['p22_multi_10_npc'] -ne 'PASS') 'noprofiles: p22 cannot PASS either'
        }
        'dupactor' {
            Check ($st['p20_multi_3_npc'] -eq 'FAIL' -and $why['p20_multi_3_npc'] -match 'share a') "dupactor: lanes sharing one actor id FAIL, got $($st['p20_multi_3_npc']) ($($why['p20_multi_3_npc']))"
        }
        'dupterm' { Check ($st['p04_navlong_80'] -eq 'FAIL' -and $why['p04_navlong_80'] -match 'exactly-once') "dupterm: two terminal lines for one goal fail p04, got $($st['p04_navlong_80']) ($($why['p04_navlong_80']))"; Check ($st['p03_navlong_40'] -eq 'PASS') 'dupterm: p03 unaffected' }
        'nosnap' { Check ($pass -eq 0) "nosnap: without M03 snapshots nothing may PASS (got $pass)"; Check ($st['p02_navlong_20'] -eq 'PENDING' -and $why['p02_navlong_20'] -match 'snapshot') 'nosnap: p02 PENDING for lack of snapshots' }
        'stale' { Check ($pass -eq 0) "stale: an earlier run's evidence is never credited to the new run (got $pass PASS)"; Check ($st['p02_navlong_20'] -eq 'PENDING') 'stale: p02 PENDING (no BEGIN in the analysed run)'; Check (($sum.notes -join ' ') -match 'earlier G5 run') 'stale: earlier run reported as ignored' }
    }
}
# ---- Rev6 collector hardening: a RELATIVE -BackendLog resolved against the PowerShell location (not the process directory), logs a running process keeps open for writing
$lk = Join-Path $tmp 'lock'
& node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $lk 'ok' | Out-Null
$logPath = Join-Path $lk 'run/logs/latest.log'; $errPath = Join-Path $lk 'g5/run-fixture/backend.err.log'
$relDir = Join-Path $lk 'relative'; New-Item -ItemType Directory -Force -Path $relDir | Out-Null
[IO.File]::WriteAllText((Join-Path $relDir 'my-backend.log'), "INFO: 127.0.0.1 - `"GET /health HTTP/1.1`" 200 -`n")
$oldCwd = [Environment]::CurrentDirectory
$w1 = New-Object IO.FileStream($logPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)      # a writer, like Minecraft / the cmd.exe redirect of the backend
$w2 = New-Object IO.FileStream($errPath, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::Read)
$raised = $null
try {
    [Environment]::CurrentDirectory = [Environment]::GetFolderPath('System')          # the Rev5 failure: the process directory is C:\Windows\system32
    Push-Location $lk
    $null = & (Join-Path $PSScriptRoot 'collect-g5-evidence.ps1') -WorldName NPCortex_G5_fixture -RunDir 'run' -G5RunDir 'g5/run-fixture' -BackendUrl 'http://127.0.0.1:1' -OutDir 'out' -BackendLog 'relative/my-backend.log'
} catch { $raised = $_.Exception.Message } finally { Pop-Location; [Environment]::CurrentDirectory = $oldCwd; $w1.Dispose(); $w2.Dispose() }
Check ($null -eq $raised) "locked live logs + relative paths: the collector must not fail ($raised)"
$zl = Get-ChildItem (Join-Path $lk 'out') -Filter 'NPCortex-G5-TestResults-*.zip' -ErrorAction SilentlyContinue | Select-Object -First 1
Check ($null -ne $zl) 'locked/relative: the evidence ZIP was created'
if ($zl) {
    $z = [IO.Compression.ZipFile]::OpenRead($zl.FullName); $nm = $z.Entries | ForEach-Object { $_.FullName }
    Check (($nm -contains 'backend/backend.log') -and ((ReadEntry $z 'backend/backend.log') -match 'GET /health')) 'relative -BackendLog was resolved against the PowerShell location and read'
    Check ((ReadEntry $z 'logs/latest.log') -match 'BEGIN p02_navlong_20') 'latest.log was read while a writer held it open'
    $z.Dispose()
}

Remove-Item -LiteralPath $tmp -Recurse -Force
[pscustomobject]@{ checks = $checks; failures = $fails.Count; status = $(if ($fails.Count) { 'FAIL' } else { 'PASS' }); scope = 'collector logic on synthetic fixtures + one real log excerpt only' } | ConvertTo-Json
$fails | ForEach-Object { "FAIL: $_" }
if ($fails.Count) { exit 1 }
