# Static validation of the NPCortex G5 data pack (TEST ONLY). No Minecraft needed; this is NOT a runtime proof.
# Checks: pack metadata, function reachability, 1.20.1 command grammar subset, no macros / newer features,
# NPCortex command shapes against the registered Java literals, placement/safety limits, PowerShell parse.
param([string]$Pack, [switch]$Quiet)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $Pack) { $Pack = Join-Path $root 'integrations/datapacks/NPCortex_G5' }
$fnRoot = Join-Path $Pack 'data/npcortex_g5/functions'
$fail = New-Object System.Collections.ArrayList
$checks = 0
function Bad($m) { [void]$fail.Add($m) }
function Ok($c, $m) { $script:checks++; if (-not $c) { Bad $m } }

# 1 pack.mcmeta
$meta = Get-Content -Raw -LiteralPath (Join-Path $Pack 'pack.mcmeta') | ConvertFrom-Json
Ok ($meta.pack.pack_format -eq 15) 'pack_format must be 15 (Minecraft 1.20 - 1.20.1)'
$files = Get-ChildItem -Recurse -File -LiteralPath $fnRoot -Filter *.mcfunction
$rel = @{}; foreach ($f in $files) { $rel[($f.FullName.Substring($fnRoot.Length+1) -replace '\\','/' -replace '\.mcfunction$','')] = $f.FullName }
Ok ($files.Count -gt 20) 'unexpectedly few functions'
foreach ($e in 'setup','start','status','stop','rejoin','gate','cancel_current','strict_on','strict_off','tests/navigation','tests/obstacles','tests/cancellation','tests/regression','tests/multi_npc') { Ok $rel.ContainsKey($e) "missing entry point $e" }
Ok (-not (Test-Path (Join-Path $Pack 'data/minecraft/tags'))) 'pack must not use load/tick tags (nothing may start by itself)'

# 2 registered NPCortex literals (production source is read-only here)
$lits = @{}
$srcDir = Join-Path $root 'forge-mod/src/main/java'
$haveSrc = Test-Path -LiteralPath $srcDir   # absent when the harness package is unpacked outside the repository: literal cross-check is then SKIPPED (reported)
if ($haveSrc) { Get-ChildItem -Recurse -File -LiteralPath $srcDir -Filter *.java | ForEach-Object {
    foreach ($m in [regex]::Matches((Get-Content -Raw -LiteralPath $_.FullName), 'literal\("([A-Za-z0-9_-]+)"\)')) { $lits[$m.Groups[1].Value] = $true }
} }
# M03 registers its leaf literals through a loop over string constants.
if ($haveSrc) { foreach ($m in [regex]::Matches((Get-Content -Raw -LiteralPath (Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusM03Runtime.java')), '"([a-z0-9-]+)"')) { $lits[$m.Groups[1].Value] = $true } }
$npc = '(?:yui|g5a(?:0[2-9]|10))'
$npcortexShapes = @(
  ('^c1 spawn ' + $npc + '$'), ('^c1 despawn ' + $npc + '$'),
  '^c2 navlong ' + $npc + ' -?\d+ -?\d+ -?\d+$', '^c2 navigate ' + $npc + ' -?\d+ -?\d+ -?\d+$',
  '^c2 move ' + $npc + ' -?\d+(\.\d+)? -?\d+ -?\d+(\.\d+)?$', '^c2 cancel ' + $npc + '$',
  '^debug cplus m03 enable$', ('^debug cplus m03 snapshot ' + $npc + '$'), ('^debug cplus m03 move ' + $npc + '$'),
  ('^debug cplus m03 follow-self ' + $npc + '$'), ('^debug cplus m03 unfollow ' + $npc + '$'),
  '^debug cplus m03 measure-auto (1|3|5|10) \d+$')

$box = 'x=-250,y=-64,z=-150,dx=500,dy=20,dz=300'   # must equal $BOX in build-g5-datapack.ps1
$callable = @{}; $exec = @('as','at','positioned','store','if','unless','run')
$phaseNames = @{}
function CheckCommand([string]$cmd, [string]$where) {
    $script:checks++
    if ($cmd -match '^\s*$') { return }
    if ($cmd.StartsWith('$')) { Bad "$where macro line (1.20.2+): $cmd"; return }
    if ($cmd.StartsWith('/')) { Bad "$where leading slash: $cmd"; return }
    $head = ($cmd -split ' ')[0]
    switch ($head) {
        'execute' {
            if ($cmd -match ' run ') { $pre = $cmd.Substring(0, $cmd.IndexOf(' run ')); $post = $cmd.Substring($cmd.IndexOf(' run ') + 5) }
            elseif ($cmd -match ' (if|unless) ') { $pre = $cmd; $post = '' }   # terminal condition (used with store)
            else { Bad "$where execute without run or terminal condition: $cmd"; return }
            $toks = $pre -split ' '
            for ($i = 1; $i -lt $toks.Count; $i++) {
                switch ($toks[$i]) {
                    'as' { $i++ } 'at' { $i++ }
                    'positioned' { $i += 3 }
                    'store' { $i += 4 }  # store success|result score <holder> <obj>
                    { $_ -in 'if','unless' } {
                        $k = $toks[$i+1]
                        if ($k -eq 'score') { $op = $toks[$i+4]; if ($op -eq 'matches') { $i += 5 } elseif ($op -in '=','>','<','>=','<=') { $i += 6 } else { Bad "$where bad score test: $cmd"; return } }
                        elseif ($k -eq 'entity') { $i += 2 } elseif ($k -eq 'block') { $i += 5 } else { Bad "$where unsupported condition: $cmd"; return }
                    }
                    default { Bad "$where unsupported execute token '$($toks[$i])': $cmd"; return }
                }
            }
            if ($post) { CheckCommand $post $where }
        }
        'scoreboard' {
            $ok = $cmd -match '^scoreboard objectives add g5 dummy$' -or
                  $cmd -match '^scoreboard players (set|add|remove) #\w+ g5 -?\d+$' -or
                  $cmd -match '^scoreboard players operation #\w+ g5 (=|\+=|%=) #\w+ g5$'
            if (-not $ok) { Bad "$where scoreboard shape: $cmd" }
        }
        'data' { if ($cmd -notmatch '^data get entity @s Pos\[(0|2)\]( 1000)?$') { Bad "$where data command: $cmd" } }
        'say' {
            if ($cmd -notmatch '^say \[G5\] ') { Bad "$where say must start with [G5]: $cmd" }
            if ($cmd.Contains('@')) { Bad "$where say text contains '@' (vanilla expands selectors in say; the p00 log printed 'Dev' for an @e[...] in the text): $cmd" }
        }
        'tellraw' {
            $json = $cmd -replace '^tellraw @\S+ ', ''
            try { $null = $json | ConvertFrom-Json } catch { Bad "$where invalid tellraw JSON: $cmd" }
            if ($cmd -notmatch '^tellraw @(s|a\[tag=g5_op\]) ') { Bad "$where tellraw target: $cmd" }
        }
        'tp' { if ($cmd -notmatch '^tp @a\[tag=g5_op\] -?\d+(\.\d+)? -60 -?\d+(\.\d+)?$') { Bad "$where tp shape/limits: $cmd" }
               else { $m = [regex]::Match($cmd, 'tp @a\[tag=g5_op\] (-?[\d.]+) -60 (-?[\d.]+)'); if ([math]::Abs([double]$m.Groups[1].Value) -gt 200 -or [math]::Abs([double]$m.Groups[2].Value) -gt 80) { Bad "$where tp outside test area: $cmd" } } }
        'fill' {
            $m = [regex]::Match($cmd, '^fill (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) (-?\d+) minecraft:(stone|air)$')
            if (-not $m.Success) { Bad "$where fill shape/block: $cmd"; return }
            $x1,$y1,$z1,$x2,$y2,$z2 = 1..6 | ForEach-Object { [int]$m.Groups[$_].Value }
            if (($x1,$x2 | Where-Object { $_ -lt -200 -or $_ -gt 200 }) -or ($z1,$z2 | Where-Object { $_ -lt -80 -or $_ -gt 80 }) -or ($y1,$y2 | Where-Object { $_ -lt -61 -or $_ -gt -57 })) { Bad "$where fill outside test area (x +-200, z +-80, y -61..-57): $cmd" }
            if ([math]::Abs($x2-$x1+1) * [math]::Abs($y2-$y1+1) * [math]::Abs($z2-$z1+1) -gt 2000) { Bad "$where fill too large: $cmd" }
        }
        'gamerule' { if ($cmd -notmatch '^gamerule (doMobSpawning|doDaylightCycle|doWeatherCycle) false$') { Bad "$where gamerule: $cmd" } }
        'difficulty' { if ($cmd -ne 'difficulty peaceful') { Bad "$where difficulty: $cmd" } }
        'gamemode' { if ($cmd -ne 'gamemode creative @s') { Bad "$where gamemode: $cmd" } }
        'tag' {
            # operator tag; or a candidate selector that can never match the operator (tag=!g5_op, tag=!g5_npc, limit=1) adding an NPC/probe tag;
            # or a selector on an already adopted NPC / probe tag. Always inside the bounded search box. Never @a, never the operator.
            $bx = [regex]::Escape($box)
            $okOp = ($cmd -eq 'tag @s add g5_op')
            $okCand = ($cmd -match ('^tag @e\[(type=player|type=!item),tag=!g5_op,tag=!g5_npc,' + $bx + ',limit=1\] add (g5_npc_(yui|g5a\d\d)|g5_pv1|g5_pv2)$'))
            $okTagged = ($cmd -match ('^tag @e\[(type=player,)?tag=(g5_npc_(yui|g5a\d\d)|g5_pv1|g5_pv2),' + $bx + '\] (add g5_npc|remove g5_pv[12])$'))
            if (-not ($okOp -or $okCand -or $okTagged)) { Bad "$where tag: $cmd" }
        }
        'schedule' { if ($cmd -notmatch '^schedule (function npcortex_g5:loop 10t replace|clear npcortex_g5:loop|function npcortex_g5:lib/p10_watch 1t replace|clear npcortex_g5:lib/p10_watch)$') { Bad "$where schedule: $cmd" } }
        'function' {
            if ($cmd -notmatch '^function npcortex_g5:([\w/]+)$') { Bad "$where function ref: $cmd" }
            elseif (-not $rel.ContainsKey($Matches[1])) { Bad "$where missing function $($Matches[1])" } else { $callable[$Matches[1]] = $true }
        }
        'npcortex' {
            $tail = $cmd.Substring(9)
            if (-not ($npcortexShapes | Where-Object { $tail -match $_ })) { Bad "$where npcortex command not in the allowed shape list: $cmd" }
            foreach ($t in $tail -split ' ') { if ($t -match '^[a-z][a-z0-9-]*$' -and $t -notmatch "^$npc$" -and $haveSrc -and -not $lits.ContainsKey($t)) { Bad "$where literal '$t' is not registered in production source: $cmd" } }
        }
        default { Bad "$where unsupported command '$head': $cmd" }
    }
    # selector sanity (brackets balanced; limited keys)
    foreach ($sel in [regex]::Matches($cmd, '@([eaps])\[([^\]]*)\]')) {
        $kind = $sel.Groups[1].Value; $sa = $sel.Groups[2].Value
        foreach ($kv in $sa -split ',') { if ($kv -notmatch '^(type|tag|limit|distance|sort|x|y|z|dx|dy|dz)=') { Bad "$where selector key: $kv in $cmd" } }
        # regression: the GameProfile name is "N<char>_<uuid6>", never the character name, so name= selectors are forbidden
        if ($sa -match '(^|,)name=') { Bad "$where name= selector (GameProfile name != character name): $cmd" }
        # bounded search: every @e carries the test-world box (no unbounded entity scans); @a only ever addresses the tagged operator
        if ($kind -eq 'e' -and $sa -notmatch ('(^|,)' + [regex]::Escape($box) + '(,|$)')) { Bad "$where @e selector without the bounded search box: $cmd" }
        if ($kind -eq 'a' -and $sa -notmatch '(^|,)tag=g5_op(,|$)') { Bad "$where @a selector that is not the tagged operator: $cmd" }
        if ($kind -in 'p', 'r') { Bad "$where @$kind selector: $cmd" }
        if ($kind -eq 's' -and $sa -notmatch '^distance=\.\.[\d.]+$') { Bad "$where @s selector arguments: $cmd" }
        if ($kind -eq 'e' -and $sa -match 'distance=') { Bad "$where distance= together with the search box (x/y/z move the origin of distance): $cmd" }
    }
    if (-not $cmd.StartsWith('tellraw') -and $cmd -match '@e(?!\[)') { Bad "$where bare @e selector (unbounded): $cmd" }
    # the only bare @a is the read-only PlayerList count of the p00 negative control
    if ($cmd -match '@a(?!\[)' -and $cmd -notmatch '^execute if score #tick g5 matches 3 store result score #n_a g5 if entity @a$') { Bad "$where bare @a selector (only the p00 PlayerList count may use it): $cmd" }
    Ok (([regex]::Matches($cmd, '\[').Count) -eq ([regex]::Matches($cmd, '\]').Count) -or $cmd.StartsWith('tellraw') -or $cmd.StartsWith('say')) "$where unbalanced brackets: $cmd"
}
foreach ($k in $rel.Keys) {
    $n = 0
    foreach ($line in [IO.File]::ReadAllLines($rel[$k])) {
        $n++
        if ($line.TrimStart().StartsWith('#') -or $line.Trim() -eq '') { continue }
        CheckCommand $line "$k`:$n"
    }
}
# 3 reachability: every function except entry points must be called from somewhere
$entries = @('setup','start','status','stop','rejoin','menu','loop','probe','gate','cancel_current','strict_on','strict_off') + ($rel.Keys | Where-Object { $_ -like 'tests/*' -or $_ -like 'only/*' })
$dispatchCalls = (Get-Content -Raw -LiteralPath $rel['dispatch'])
foreach ($k in $rel.Keys) {
    if ($entries -contains $k) { continue }
    Ok ($callable.ContainsKey($k)) "unreachable function $k"
}
Ok ($dispatchCalls -match 'ph/p22_multi_10_npc') 'dispatch lists all phases'
# 4 invariants
$all = ($rel.Values | ForEach-Object { Get-Content -Raw -LiteralPath $_ }) -join "`n"
Ok ($all -notmatch 'forceload') 'forceload must never appear'
Ok ($all -notmatch 'matches -?\d+\.\.\d+\.\.') 'range typo'
Ok (([regex]::Matches($all, 'c2 navlong')).Count -ge 8) 'navlong scenarios present'
# 4b evidence-first invariants (revision 3)
Ok ($rel.ContainsKey('ph/p00_capability_probe')) 'p00 capability probe exists'
Ok (-not ($rel.Keys | Where-Object { $_ -like 'ph/p01*' })) 'retired p01 must not exist'
$firstPhase = ([IO.File]::ReadAllLines($rel['dispatch']) | Where-Object { $_ -match 'run function npcortex_g5:ph/' } | Select-Object -First 1)
Ok ($firstPhase -match 'matches 1 run function npcortex_g5:ph/p00_capability_probe') 'p00 is phase 1'
$p00 = if ($rel.ContainsKey('ph/p00_capability_probe')) { Get-Content -Raw -LiteralPath $rel['ph/p00_capability_probe'] } else { '' }
foreach ($c in 'playerlist_control','world_limited_player','typeless_entity','tag_v1','tag_v2','entity_data','position') { foreach ($v in 'PASS','FAIL') { Ok ($p00.Contains("PROBE $c=$v")) "p00 reports PROBE $c=$v" } }
foreach ($c in 'tag_v1','tag_v2','entity_data','position') { Ok ($p00.Contains("PROBE $c=NOT_ATTEMPTED")) "p00 reports PROBE $c=NOT_ATTEMPTED (a skipped test is never reported as FAIL)" }
foreach ($m in 'PROBE selector_chosen=world_limited_player','PROBE selector_chosen=typeless_entity','PROBE selector_chosen=NONE','PROBE mode=ADAPTIVE','PROBE mode=OWNER_GATED') { Ok ($p00.Contains($m)) "p00 emits $m" }
# the two selector kinds are tested independently: each tag test runs only when its own selector test passed
Ok ($p00 -match 'if score #s_v1 g5 matches 1 run tag' -and $p00 -match 'if score #s_v2 g5 matches 1 run tag') 'p00 tag tests are conditional on their own selector test'
# owner gate: every phase has a release function that is refused unless this phase is the current one AND awaiting (#settle = -2)
foreach ($pk in @($rel.Keys | Where-Object { $_ -like 'ph/*' })) {
    $gid = $pk -replace '^ph/', 'gate/'
    if (-not $rel.ContainsKey($gid)) { Bad "no owner-gate function for $pk"; continue }
    $gt = Get-Content -Raw -LiteralPath $rel[$gid]
    Ok ($gt -match '#running g5 matches 1' -and $gt -match '#phase g5 matches \d+' -and $gt -match '#settle g5 matches -2') "$gid must check run active, current phase and awaiting state"
    foreach ($gl in ($gt -split "`n" | Where-Object { $_ -match 'run (scoreboard players set #settle g5 1|function npcortex_g5:lib/checkpoint_savequit)$' })) { Ok ($gl -match '#running g5 matches 1' -and $gl -match '#phase g5 matches \d+' -and $gl -match 'if score #settle g5 matches -2') "$gid release line lacks a guard: $gl" }
}
Ok ((Get-Content -Raw -LiteralPath $rel['cancel_current']) -match 'execute if score #settle g5 matches -2 run execute as @a\[tag=g5_op,limit=1\] run npcortex c2 cancel yui') 'cancel_current cancels only while a scenario is awaiting'
foreach ($k in $rel.Keys) {
    if ($k -notlike 'ph/*' -or $k -like 'ph/p00*') { continue }
    $n = 0
    foreach ($line in [IO.File]::ReadAllLines($rel[$k])) {
        $n++
        if ($line.StartsWith('#')) { continue }
        # selector use (observation, tag adoption, polling) is allowed only in adaptive mode
        if ($line -match 'tag=g5_npc_|lib/obs_yui|lib/poll|lib/prime|lib/adopt_' -and $line -notmatch '#adaptive g5 matches 1') { Bad "${k}:$n selector use outside adaptive mode: $line" }
        # owner-gated progression: a countdown (non-negative #settle) may only be armed when no gate is needed (#gn = 0); gate-needing outcomes set -2 (AWAIT)
        if ($line -match 'scoreboard players set #settle g5 \d+$' -and $line -notmatch '#gn g5 matches 0') { Bad "${k}:$n timer-armed progression without the owner-gate condition (#gn = 0): $line" }
        # a phase may only advance from a finished settle countdown (never directly from a timeout or window)
        if ($line -match 'run function npcortex_g5:lib/advance' -and $line -notmatch '#settle g5 matches 0') { Bad "${k}:$n phase advance not conditioned on a finished settle countdown: $line" }
        # no blind dispatch: every goal/movement command requires a clean #result
        if ($line -match 'run execute as @a\[tag=g5_op,limit=1\] run npcortex (c2 (navlong|navigate|move)|debug cplus m03 (move|follow-self|unfollow|measure-auto))' -and $line -notmatch '#result g5 matches 0') { Bad "${k}:$n command dispatch not gated by a clean #result: $line" }
    }
}
Ok ($all -notmatch 'name=') 'no name= selector anywhere'
# 5 PowerShell parse of all G5 scripts
foreach ($ps in 'build-g5-datapack.ps1','install-g5-datapack.ps1','collect-g5-evidence.ps1','g5-common.ps1','g5-gate.ps1','test-g5-gate.ps1','validate-g5-datapack.ps1','test-g5-collector.ps1','test-g5-installer.ps1','test-g5-validator-mutations.ps1','run-g5-validation.ps1') {
    $p = Join-Path $PSScriptRoot $ps
    if (Test-Path $p) { $errs = $null; $null = [System.Management.Automation.Language.Parser]::ParseFile($p, [ref]$null, [ref]$errs); Ok ($errs.Count -eq 0) "PowerShell parse errors in ${ps}: $($errs | ForEach-Object { $_.Message })" }
}
$result = [pscustomobject]@{ checks = $checks; functions = $rel.Count; failures = $fail.Count; status = $(if ($fail.Count -eq 0) { 'PASS' } else { 'FAIL' }); literal_cross_check = $(if ($haveSrc) { 'DONE against forge-mod sources' } else { 'SKIPPED (production source not present)' }); scope = 'static syntax/structure only; not runtime evidence' }
if (-not $Quiet) { $fail | ForEach-Object { "FAIL: $_" }; $result | ConvertTo-Json }
if ($fail.Count -gt 0) { exit 1 }
exit 0
