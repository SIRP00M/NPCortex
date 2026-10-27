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
foreach ($mode in 'ok', 'fb', 'nogo', 'timer', 'hang', 'nolog', 'real', 'realp00', 'inconsistent', 'dupterm', 'nosnap', 'stale') {
    $fx = Join-Path $tmp $mode
    & node (Join-Path $PSScriptRoot 'g5-collector-fixture.js') $fx $mode | Out-Null
    $null = & (Join-Path $PSScriptRoot 'collect-g5-evidence.ps1') -WorldName NPCortex_G5_fixture -RunDir (Join-Path $fx 'run') -Database (Join-Path $fx 'backend/npcortex.sqlite3') -BackendUrl 'http://127.0.0.1:1' -OutDir (Join-Path $fx 'out')
    $zip = Get-ChildItem (Join-Path $fx 'out') -Filter 'NPCortex-G5-TestResults-*.zip' | Select-Object -First 1
    Check ($null -ne $zip) "$mode zip created"
    $z = [IO.Compression.ZipFile]::OpenRead($zip.FullName)
    $names = $z.Entries | ForEach-Object { $_.FullName }
    Check (-not ($names | Where-Object { $_.IndexOf([char]92) -ge 0 })) "$mode zip entries use forward slashes"
    foreach ($req in 'summary.json', 'manifest.json', 'REPORT.md', 'meta/environment.json', 'logs/g5-log-extract.txt', 'backend/db-summary.json') { Check ($names -contains $req) "$mode has $req" }
    Check (-not ($names | Where-Object { $_ -match 'secret|token' })) "$mode no secret-looking file names"
    $logText = ReadEntry $z 'logs/latest.log'
    if ($mode -notin 'nolog', 'real', 'realp00') { Check ($logText -notmatch 'SECRETTOKENVALUE|hunter2|abcDEF123456') "$mode secrets redacted from the copied log"; Check ($logText -match 'REDACTED') "$mode redaction marker present" }
    $extract = ReadEntry $z 'logs/g5-log-extract.txt'
    if ($mode -ne 'nolog') { Check ($extract -match '(?m)^\d\d:\d\d:\d\d (BEGIN|OBS|END|START) ') "$mode Forge-format timestamps were parsed into G5 markers" }
    $sum = (ReadEntry $z 'summary.json') | ConvertFrom-Json
    $rep = ReadEntry $z 'REPORT.md'
    $z.Dispose()
    $st = @{}; $why = @{}; $sum.scenarios | ForEach-Object { $st[$_.id] = $_.status; $why[$_.id] = $_.reason }
    $pass = @($st.Values | Where-Object { $_ -eq 'PASS' }).Count
    switch ($mode) {
        { $_ -in 'ok', 'fb', 'nogo' } {
            $mustPass = 'p00_capability_probe', 'p02_navlong_20', 'p03_navlong_40', 'p04_navlong_80', 'p05_navlong_150', 'p06_navlong_diag60', 'p07_small_wall_detour', 'p09_cancel_leg_active', 'p11_savequit_checkpoint', 'p12_rejoin_no_replay', 'p13_new_navlong_after_recovery', 'p14_regress_c2_navigate', 'p15_regress_c2_move', 'p16_regress_follow_start', 'p17_regress_follow_stop', 'p19_multi_1_npc', 'p20_multi_3_npc', 'p21_multi_5_npc', 'p22_multi_10_npc'
            foreach ($id in $mustPass) { Check ($st[$id] -eq 'PASS') "${mode}: $id should PASS, got $($st[$id]) ($($why[$id]))" }
            Check ($st['p08_large_wall_no_progress'] -eq 'UNVERIFIED') "${mode}: p08 is UNVERIFIED (failure reason not logged), got $($st['p08_large_wall_no_progress'])"
            Check ($st['p18_regress_e3_taskmove'] -eq 'PENDING') "${mode}: E3 stays PENDING without finalize"
            if ($mode -eq 'ok') { Check ($st['p10_cancel_quiescing'] -eq 'PASS') "ok: p10 PASS, got $($st['p10_cancel_quiescing']) ($($why['p10_cancel_quiescing']))"; Check ($sum.mode -eq 'ADAPTIVE') "ok: mode ADAPTIVE, got $($sum.mode)" }
            else {
                Check ($st['p10_cancel_quiescing'] -in 'PASS', 'PENDING') "${mode}: p10 PASS or PENDING (fixed-time cancel may miss the quiescing state)"
                Check ($sum.mode -eq 'OWNER_GATED') "${mode}: mode OWNER_GATED, got $($sum.mode)"
                Check ($why['p02_navlong_20'] -match 'owner-gated mode') "${mode}: verdict states it came from snapshots + owner gate only"
                Check ($sum.owner_gate.awaited -eq 21 -and $sum.owner_gate.released -eq 21 -and $sum.owner_gate.awaiting_never_released -eq 0) "${mode}: all 21 scenarios awaited and were released ($($sum.owner_gate | ConvertTo-Json -Compress))"
                if ($mode -eq 'fb') { Check ($sum.owner_gate.released_with_recorded_go -eq 21) "fb: every release has a recorded GO of g5-gate.ps1 ($($sum.owner_gate.released_with_recorded_go))" }
                else { Check ($sum.owner_gate.released_without_recorded_go -eq 21 -and (($sum.notes -join ' ') -match 'without a GO decision')) 'nogo: releases without a recorded GO are reported (verdicts unchanged, progression unverified)' }
            }
            $c = $sum.correlation | Where-Object { $_.scenario -eq 'p02_navlong_20' }
            Check ($c.goal -match '^[0-9a-f-]{36}$' -and $c.action -match '^[0-9a-f-]{36}$' -and $c.terminal -eq 'COMPLETED') "${mode}: p02 correlates one goal id, one action id and one COMPLETED terminal ($($c | ConvertTo-Json -Compress))"
            Check ($c.states -match 'MOVING') "${mode}: journal states recorded for p02 ($($c.states))"
            Check ($rep -match 'Correlation \(scenario') "${mode}: REPORT carries the correlation table"
            $c9 = $sum.scenarios | Where-Object { $_.id -eq 'p09_cancel_leg_active' }; Check ($c9.evidence.journal_state_at_cancel -eq 'MOVING') "${mode}: p09 journal state at cancel is MOVING"
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
        'dupterm' { Check ($st['p04_navlong_80'] -eq 'FAIL' -and $why['p04_navlong_80'] -match 'exactly-once') "dupterm: two terminal lines for one goal fail p04, got $($st['p04_navlong_80']) ($($why['p04_navlong_80']))"; Check ($st['p03_navlong_40'] -eq 'PASS') 'dupterm: p03 unaffected' }
        'nosnap' { Check ($pass -eq 0) "nosnap: without M03 snapshots nothing may PASS (got $pass)"; Check ($st['p02_navlong_20'] -eq 'PENDING' -and $why['p02_navlong_20'] -match 'snapshot') 'nosnap: p02 PENDING for lack of snapshots' }
        'stale' { Check ($pass -eq 0) "stale: an earlier run's evidence is never credited to the new run (got $pass PASS)"; Check ($st['p02_navlong_20'] -eq 'PENDING') 'stale: p02 PENDING (no BEGIN in the analysed run)'; Check (($sum.notes -join ' ') -match 'earlier G5 run') 'stale: earlier run reported as ignored' }
    }
}
Remove-Item -LiteralPath $tmp -Recurse -Force
[pscustomobject]@{ checks = $checks; failures = $fails.Count; status = $(if ($fails.Count) { 'FAIL' } else { 'PASS' }); scope = 'collector logic on synthetic fixtures + one real log excerpt only' } | ConvertTo-Json
$fails | ForEach-Object { "FAIL: $_" }
if ($fails.Count) { exit 1 }
