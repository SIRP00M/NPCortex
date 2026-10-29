# Vanilla Player Parity campaign runner / evidence collector (PowerShell first). Offline stages run unattended; the Minecraft stage is the Owner's.
#   scripts\parity\run-parity.ps1 -Mode Preflight                         # environment + data pack lint (read-only)
#   scripts\parity\run-parity.ps1 -Mode Auto                              # build + GameTests (parity, bench, accepted native) + PAP real-backend; writes evidence
#   scripts\parity\run-parity.ps1 -Mode Install -WorldName NPCortex_Parity_Test   # copies the data pack into ONE throw-away world
#   scripts\parity\run-parity.ps1 -Mode Collect [-Log <latest.log>]       # merges in-game "PARITY|STATUS|Txx|..." lines + Auto evidence into the matrix
#   scripts\parity\run-parity.ps1 -Mode Remove -WorldName NPCortex_Parity_Test
# Statuses are only PASS / FAIL / PENDING / BLOCKED / NOT TESTED, and only from executed evidence. Never touches SQLite, outbox, journal or git.
param(
    [Parameter(Mandatory)][ValidateSet('Preflight', 'Auto', 'Install', 'Collect', 'Remove')][string]$Mode,
    [string]$WorldName,
    [string]$SavesDir,
    [string]$Log,
    [string]$EvidenceDir,
    [switch]$AllowAnyName
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$forge = Join-Path $repo 'forge-mod'
$pack = Join-Path $repo 'integrations\datapacks\NPCortex_Parity_Acceptance'
$ns = Join-Path $pack 'data\npcortex_parity\functions'
if (-not $SavesDir) { $SavesDir = Join-Path $forge 'run\saves' }
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $repo 'docs\parity\evidence' }
function Fail([string]$m) { Write-Host "run-parity: $m" -ForegroundColor Red; exit 1 }
function Latest-Evidence { $d = Get-ChildItem -LiteralPath $EvidenceDir -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1; return $d }

function Lint-Pack {
    $problems = @()
    try { $m = Get-Content -Raw (Join-Path $pack 'pack.mcmeta') | ConvertFrom-Json; if ($m.pack.pack_format -ne 15) { $problems += "pack_format is $($m.pack.pack_format), 1.20.1 needs 15" } } catch { $problems += "pack.mcmeta unreadable: $_" }
    foreach ($i in 1..25) { $t = 't{0:d2}' -f $i
        foreach ($k in 'setup', 'check') { if (-not (Test-Path (Join-Path $ns "$k\$t.mcfunction")) -and -not (Test-Path (Join-Path $ns "$k\${t}_3.mcfunction"))) { $problems += "missing $k/$t" } } }
    foreach ($f in 'reset', 'status', 'guide') { if (-not (Test-Path (Join-Path $ns "$f.mcfunction"))) { $problems += "missing $f" } }
    $files = Get-ChildItem -LiteralPath $ns -Recurse -Filter *.mcfunction
    foreach ($f in $files) {
        $n = 0
        foreach ($line in Get-Content -LiteralPath $f.FullName) {
            $n++
            if ($line -match '^\s*$' -or $line -match '^\s*#') { continue }
            if ($line -match '^\s*/') { $problems += "$($f.Name):$n starts with '/'" }
            foreach ($m in [regex]::Matches($line, 'function npcortex_parity:([a-z0-9_/]+)')) {
                if (-not (Test-Path (Join-Path $ns ($m.Groups[1].Value + '.mcfunction')))) { $problems += "$($f.Name):$n calls missing function $($m.Groups[1].Value)" } }
            if ($line -match '\bdata modify entity\b|\bsetblock\b.*oak_log' -and $f.Name -match '^check') { $problems += "$($f.Name):$n a check function must not manipulate the outcome" }
        }
    }
    return $problems
}

switch ($Mode) {
    'Preflight' {
        $rows = @()
        $rows += [pscustomobject]@{ Check = 'gradle wrapper'; Result = (Test-Path (Join-Path $forge 'gradlew.bat')) }
        $rows += [pscustomobject]@{ Check = 'java on PATH'; Result = [bool](Get-Command java -ErrorAction SilentlyContinue) }
        $rows += [pscustomobject]@{ Check = 'python on PATH'; Result = [bool](Get-Command python -ErrorAction SilentlyContinue) }
        foreach ($p in 18765, 18766) { $busy = @(Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue).Count -gt 0
            $rows += [pscustomobject]@{ Check = "port $p free (a busy port BLOCKS the real-backend suites)"; Result = (-not $busy) } }
        $problems = Lint-Pack
        $rows += [pscustomobject]@{ Check = "data pack lint ($($problems.Count) problem(s))"; Result = ($problems.Count -eq 0) }
        $rows | Format-Table -AutoSize
        $problems | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
        if ($rows | Where-Object { -not $_.Result -and $_.Check -notmatch '^port' }) { exit 1 }
    }
    'Auto' {
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $out = Join-Path $EvidenceDir $stamp
        New-Item -ItemType Directory -Force -Path $out | Out-Null
        $res = [ordered]@{}
        function Run-Gradle([string]$name, [string]$tasks) {
            $log = Join-Path $out "$name.log"
            & cmd.exe /c "cd /d `"$forge`" && .\gradlew.bat --console=plain --offline $tasks > `"$log`" 2>&1"
            return @{ exit = $LASTEXITCODE; log = $log }
        }
        function Run-GameTest([string]$name, [string]$namespace) {
            $world = Join-Path $forge 'run-gametest\world'
            if (Test-Path -LiteralPath $world) { if ((Split-Path -Leaf $world) -ne 'world' -or $world -notlike '*run-gametest*') { Fail "refusing to delete $world" }; Remove-Item -LiteralPath $world -Recurse -Force }
            $r = Run-Gradle $name "runGameTestServer -PgametestNamespace=$namespace"
            $text = Get-Content $r.log
            $failed = @($text | Where-Object { $_ -match 'LogTestReporter\]: (\w+) failed!' } | ForEach-Object { ($_ -replace '^.*LogTestReporter\]: ', '') })
            $passed = ($text | Where-Object { $_ -match 'All (\d+) required tests passed' } | Select-Object -First 1)
            $status = if ($r.exit -eq 0 -and $passed -and $failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
            $res[$name] = [ordered]@{ status = $status; detail = $(if ($passed) { ($passed -replace '^.*(All \d+ required tests passed).*$', '$1') } else { "exit $($r.exit); failed: $($failed -join '; ')" }) }
            Select-String -Path $r.log -Pattern 'PARITY\|' | ForEach-Object { ($_.Line -replace '^\[[^\]]*\] \[[^\]]*\] \[[^\]]*\]: ', '') } | Set-Content (Join-Path $out "$name.evidence.txt") -Encoding utf8
        }
        # The Data Pack is loaded by REAL Minecraft: it is copied into the throw-away GameTest world before boot (Forge's GameTestServer picks up
        # world/datapacks), then ParityPackGameTests registers-checks every function and runs setup -> check for each scenario.
        function Run-PackGameTest([string]$name) {
            $world = Join-Path $forge 'run-gametest\world'
            if (Test-Path -LiteralPath $world) { if ((Split-Path -Leaf $world) -ne 'world' -or $world -notlike '*run-gametest*') { Fail "refusing to delete $world" }; Remove-Item -LiteralPath $world -Recurse -Force }
            New-Item -ItemType Directory -Force -Path (Join-Path $world 'datapacks') | Out-Null
            Copy-Item -LiteralPath $pack -Destination (Join-Path $world 'datapacks') -Recurse
            $r = Run-Gradle $name 'runGameTestServer -PgametestNamespace=npcortexparitypack'
            $text = Get-Content $r.log
            # The pack's scenarios share one persistent NPC identity and real-time mob behaviour, so the harness is known to flake occasionally
            # (a dead NPC cascading into the next scenario). ONE retry is allowed and is reported, never hidden: attempt 1's log is kept.
            $firstFailure = $null
            if ($r.exit -ne 0) {
                $firstFailure = (($text | Where-Object { $_ -match 'reported FAIL for' } | Select-Object -First 1) -replace '^.*reported FAIL for', 'FAIL for')
                Copy-Item -LiteralPath $r.log -Destination (Join-Path $out "$name.attempt1.log")
                $world = Join-Path $forge 'run-gametest\world'
                if ((Split-Path -Leaf $world) -eq 'world' -and $world -like '*run-gametest*' -and (Test-Path -LiteralPath $world)) { Remove-Item -LiteralPath $world -Recurse -Force }
                New-Item -ItemType Directory -Force -Path (Join-Path $world 'datapacks') | Out-Null
                Copy-Item -LiteralPath $pack -Destination (Join-Path $world 'datapacks') -Recurse
                $r = Run-Gradle $name 'runGameTestServer -PgametestNamespace=npcortexparitypack'
                $text = Get-Content $r.log
            }
            $passed = ($text | Where-Object { $_ -match 'All (\d+) required tests passed' } | Select-Object -First 1)
            $reg = ($text | Where-Object { $_ -match 'PARITY\|PACK\|registered=(\d+)' } | Select-Object -First 1)
            $verdicts = @($text | Where-Object { $_ -match 'PARITY\|PACK\|T\d\d\|' } | ForEach-Object { ($_ -replace '^.*PARITY\|PACK\|', '') })
            $status = if ($r.exit -eq 0 -and $passed -and $reg -and $verdicts.Count -ge 20) { 'PASS' } else { 'FAIL' }
            $res[$name] = [ordered]@{ status = $status; detail = "$(if ($firstFailure) { "RETRIED (attempt 1: $firstFailure, log kept as $name.attempt1.log); " })$($reg -replace '^.*PARITY\|PACK\|', ''); scenarios executed: $($verdicts.Count); pack verdicts: $($verdicts -join ', ')" }
            Select-String -Path $r.log -Pattern 'PARITY\|' | ForEach-Object { ($_.Line -replace '^\[[^\]]*\] \[[^\]]*\] \[[^\]]*\]: ', '') } | Set-Content (Join-Path $out "$name.evidence.txt") -Encoding utf8
        }
        $b = Run-Gradle 'build' 'build'
        $res['gradle build (Java self-tests)'] = [ordered]@{ status = $(if ($b.exit -eq 0) { 'PASS' } else { 'FAIL' }); detail = "exit $($b.exit); PASS lines: $((Select-String -Path $b.log -Pattern '^PASS ').Count)" }
        Run-GameTest 'gametest-parity' 'npcortexparity'
        Run-GameTest 'gametest-parity-bench' 'npcortexparitybench'
        Run-GameTest 'gametest-accepted-native' 'npcortex'
        Run-PackGameTest 'datapack-execution'
        foreach ($s in @(@('pap-real-backend', 'scripts\pap\run-pap-gametests.ps1', 18766), @('native-real-backend', 'scripts\test-native-real-backend.ps1', 18765))) {
            if (@(Get-NetTCPConnection -State Listen -LocalPort $s[2] -ErrorAction SilentlyContinue).Count) { $res[$s[0]] = [ordered]@{ status = 'BLOCKED'; detail = "port $($s[2]) is busy (a live backend?); this runner never reuses or kills it" }; continue }
            $log = Join-Path $out "$($s[0]).log"
            & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo $s[1]) *> $log
            $code = $LASTEXITCODE
            # Not a loose "PASS" match: the suite's own verdict line AND its ledger audit line must be present, no FAIL line, and every claim RELEASED.
            $txt = Get-Content -LiteralPath $log -Raw
            $verdict = $txt -match '(?m)^(NATIVE|PAP) REAL BACKEND: PASS'
            $ledger = $txt -match '(?m)^(PAP )?LEDGER OK'
            $failLines = @(Select-String -Path $log -Pattern '(?i)\bFAIL(ED)?\b' | Where-Object { $_.Line -notmatch 'FAILED_NO_EFFECT|INVENTORY_COMMIT_FAILED' }).Count
            $claims = [regex]::Match($txt, "(?m)^(?:pap )?claims\s*:\s*(\{[^}]*\})").Groups[1].Value
            $allReleased = $claims -match "^\{'RELEASED': \d+\}$"
            $ok = $code -eq 0 -and $verdict -and $ledger -and $failLines -eq 0 -and $allReleased
            $res[$s[0]] = [ordered]@{ status = $(if ($ok) { 'PASS' } else { 'FAIL' }); detail = "exit $code; verdict=$verdict ledger=$ledger failLines=$failLines claims=$claims" }
        }
        $res | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $out 'auto-summary.json') -Encoding utf8
        $res.GetEnumerator() | ForEach-Object { '{0,-34} {1,-8} {2}' -f $_.Key, $_.Value.status, $_.Value.detail }
        Write-Host "Evidence: $out"
        if ($res.Values | Where-Object { $_.status -eq 'FAIL' }) { exit 1 }
    }
    'Install' {
        if (-not $WorldName -or $WorldName -notmatch '^[^\\/:*?"<>|]{1,64}$') { Fail 'give -WorldName (a throw-away world)' }
        if (-not $AllowAnyName -and $WorldName -notmatch 'Parity') { Fail 'the world name must contain "Parity" (the pack builds and clears blocks). -AllowAnyName only for a throw-away world.' }
        $world = Join-Path $SavesDir $WorldName
        if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { Fail "world not found (create it in-game first): $world" }
        $problems = Lint-Pack; if ($problems.Count) { $problems | ForEach-Object { Write-Host $_ }; Fail 'data pack lint failed' }
        $dest = Join-Path $world 'datapacks\NPCortex_Parity_Acceptance'
        if (Test-Path -LiteralPath $dest) { Fail "already installed (existing files are preserved): $dest" }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
        Copy-Item -LiteralPath $pack -Destination $dest -Recurse
        Write-Host "Installed at $dest. In game: /reload ; /function npcortex_parity:guide" -ForegroundColor Green
    }
    'Remove' {
        if (-not $WorldName) { Fail 'give -WorldName' }
        $dest = Join-Path (Join-Path (Join-Path $SavesDir $WorldName) 'datapacks') 'NPCortex_Parity_Acceptance'
        if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force; Write-Host "Removed $dest" } else { Write-Host 'Not installed.' }
    }
    'Collect' {
        if (-not $Log) { $Log = Join-Path $forge 'run\logs\latest.log' }
        if (-not (Test-Path -LiteralPath $Log)) { Fail "log not found: $Log" }
        $inGame = @{}
        foreach ($l in Get-Content -LiteralPath $Log) { if ($l -match 'PARITY\|STATUS\|(T\d\d)\|(PASS|FAIL|NOT TESTED|PENDING)') { $inGame[$Matches[1]] = $Matches[2] } }
        $ev = Latest-Evidence
        $auto = $null; if ($ev -and (Test-Path (Join-Path $ev.FullName 'auto-summary.json'))) { $auto = Get-Content -Raw (Join-Path $ev.FullName 'auto-summary.json') | ConvertFrom-Json }
        $gt = @{}
        if ($ev) { foreach ($f in Get-ChildItem $ev.FullName -Filter *.evidence.txt) { foreach ($l in Get-Content $f.FullName) { if ($l -match 'PARITY\|(T\d\d)') { $gt[$Matches[1]] = $true } } } }
        $out = Join-Path $EvidenceDir ('matrix-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.md')
        $lines = @('# Vanilla Player Parity matrix (generated)', '', "In-game log: $Log", "Automated evidence folder: $($ev.FullName)", '',
                   '| Test | In-game (Owner campaign) | Automated GameTest evidence |', '|---|---|---|')
        foreach ($i in 1..25) { $t = 'T{0:d2}' -f $i
            $g = if ($inGame.ContainsKey($t)) { $inGame[$t] } else { 'PENDING (not run in game)' }
            $a = if ($gt.ContainsKey($t)) { 'executed (see *.evidence.txt)' } else { 'none' }
            $lines += "| $t | $g | $a |" }
        $lines += ''; $lines += '## Automated suites'
        if ($auto) { foreach ($p in $auto.PSObject.Properties) { $lines += "- $($p.Name): **$($p.Value.status)** - $($p.Value.detail)" } } else { $lines += '- no auto-summary.json found: run -Mode Auto first (NOT TESTED)' }
        $lines | Set-Content $out -Encoding utf8
        Write-Host "Wrote $out"
        $lines | Select-Object -First 35
        # contamination check: the pack must only be in Parity worlds
        Get-ChildItem -LiteralPath $SavesDir -Directory -ErrorAction SilentlyContinue | Where-Object { (Test-Path (Join-Path $_.FullName 'datapacks\NPCortex_Parity_Acceptance')) -and $_.Name -notmatch 'Parity' } |
            ForEach-Object { Write-Host "CONTAMINATION: pack installed in non-Parity world $($_.Name)" -ForegroundColor Red }
    }
}
