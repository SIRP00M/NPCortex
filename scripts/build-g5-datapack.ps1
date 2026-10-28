# NPCortex Roadmap #2 G5 - TEST-ONLY data pack generator (Minecraft 1.20.1, pack_format 15), revision 6 (p10: per-tick trigger, <= 5 attempts; p18: typed finalize).
# Writes integrations/datapacks/NPCortex_G5 and NPCortex_G5.zip. Touches no world, save, source or backend.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pack = Join-Path $root 'integrations/datapacks/NPCortex_G5'
$ns   = 'npcortex_g5'
$fn   = Join-Path $pack "data/$ns/functions"
if (Test-Path -LiteralPath $pack) { Remove-Item -LiteralPath $pack -Recurse -Force }   # generated output only
New-Item -ItemType Directory -Force -Path $fn | Out-Null
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Put([string]$rel, [string[]]$lines) {
    $p = Join-Path $fn $rel
    New-Item -ItemType Directory -Force -Path (Split-Path $p) | Out-Null
    [IO.File]::WriteAllText($p, (($lines -join "`n") + "`n"), $utf8)
}
$OP = 'execute as @a[tag=g5_op,limit=1] run npcortex'     # every NPCortex command runs as the tagged operator (player context + permission 2)
$AD = 'if score #adaptive g5 matches 1'     # p00 verified a selector + tag + entity data + position: the pack may observe the NPC in-game
$FB = 'if score #adaptive g5 matches 0'     # OWNER_GATED mode: nothing is observable in-game; M03 snapshots + logs are the evidence and the owner gate decides progression

# Bounded search box for EVERY @e selector (test world only: x -250..250, z -150..150, y -64..-44; covers the 150-block lane, detours around
# the 161-wide wall and the 10-lane multi-NPC test with margin). It does not touch chunk loading, C2 radius, LongGoal limits or motion ownership.
# WHY a box: a positive type=player makes vanilla read the PlayerList (the C1 NPC is deliberately NOT in it, so p00 of revision 3 saw only the operator);
# any of x/y/z/dx/dy/dz makes vanilla read ServerLevel.players, which does contain it. x/y/z also move the origin of distance=, therefore distance
# checks are written as: as <npc selector> positioned <point> if entity @s[distance=..r].
$BOX = 'x=-250,y=-64,z=-150,dx=500,dy=20,dz=300'
function NPCSEL([int]$s, [string]$nm) { if ($s -eq 1) { "@e[type=player,tag=g5_npc_$nm,$BOX]" } else { "@e[tag=g5_npc_$nm,$BOX]" } }
function CANDSEL([int]$s, [string]$tail = '') { $t = if ($s -eq 1) { 'type=player' } else { 'type=!item' }; "@e[$t,tag=!g5_op,tag=!g5_npc,$BOX$tail]" }
function Ctr($v) { ([double]$v + 0.5).ToString('0.0', [Globalization.CultureInfo]::InvariantCulture) }   # block centre, invariant culture

[IO.File]::WriteAllText((Join-Path $pack 'pack.mcmeta'),
 '{"pack":{"pack_format":15,"description":"NPCortex Roadmap #2 G5 test harness | TEST ONLY | disposable superflat world"}}' + "`n", $utf8)

# ---------------------------------------------------------------- lib
Put 'lib/reset.mcfunction' @(
 '# Per-scenario counters. -999999 = "no previous observation".',
 'scoreboard players set #result g5 0','scoreboard players set #moved g5 0','scoreboard players set #moving g5 0',
 'scoreboard players set #still g5 0','scoreboard players set #settle g5 -1','scoreboard players set #cancelled g5 0',
 'scoreboard players set #lpx g5 -999999','scoreboard players set #lpz g5 -999999','scoreboard players set #pre g5 1')
$despawnAll = @('yui') + (2..10 | ForEach-Object { 'g5a{0:d2}' -f $_ }) | ForEach-Object { "$OP c1 despawn $_" }
Put 'lib/fresh_start.mcfunction' (@('# Deterministic start: NPC yui at block (2,2) on the flat test area. All G5 NPCs are despawned first (identities are kept).') + $despawnAll + @('tp @a[tag=g5_op] 0.5 -60 0.5', "$OP c1 spawn yui", 'function npcortex_g5:lib/reset'))
# NPC identification (tag based). The NPC's GameProfile name is "N"+character(<=8)+"_"+uuid6 (C1IdentityData), NOT the character name,
# so selectors never use name=. #sel (set by p00) = 1: world-limited player selector, 2: typeless entity selector, 0: nothing verified.
# A candidate is adopted ONLY when exactly one untagged non-operator candidate exists (#nu = 1); with 0 or >=2 nothing is tagged.
$names = @('yui') + (2..10 | ForEach-Object { 'g5a{0:d2}' -f $_ })
foreach ($nm in $names) {
    Put "lib/adopt_$nm.mcfunction" @(
     "# tag the single untagged non-operator candidate as $nm (no-op when already tagged)",
     'scoreboard players set #nu g5 0',
     "execute if score #sel g5 matches 1 store result score #nu g5 if entity $(CANDSEL 1)",
     "execute if score #sel g5 matches 2 store result score #nu g5 if entity $(CANDSEL 2)",
     "execute if score #sel g5 matches 1 unless entity $(NPCSEL 1 $nm) if score #nu g5 matches 1 run function npcortex_g5:lib/adopt_do_$nm",
     "execute if score #sel g5 matches 2 unless entity $(NPCSEL 2 $nm) if score #nu g5 matches 1 run function npcortex_g5:lib/adopt_do_$nm")
    Put "lib/adopt_do_$nm.mcfunction" @(
     "execute if score #sel g5 matches 1 run tag $(CANDSEL 1 ',limit=1') add g5_npc_$nm",
     "execute if score #sel g5 matches 2 run tag $(CANDSEL 2 ',limit=1') add g5_npc_$nm",
     "execute if score #sel g5 matches 1 run tag $(NPCSEL 1 $nm) add g5_npc",
     "execute if score #sel g5 matches 2 run tag $(NPCSEL 2 $nm) add g5_npc")
}
Put 'lib/obs_yui.mcfunction' @(
 '# Observation of the NPC entity (a ServerPlayer) through its adopted tag. #present=0 means it cannot be identified/seen.',
 'function npcortex_g5:lib/adopt_yui',
 'scoreboard players set #present g5 0',
 "execute if score #sel g5 matches 1 store success score #present g5 if entity $(NPCSEL 1 'yui')",
 "execute if score #sel g5 matches 2 store success score #present g5 if entity $(NPCSEL 2 'yui')",
 "execute if score #present g5 matches 1 if score #sel g5 matches 1 as $(NPCSEL 1 'yui') store result score #px g5 run data get entity @s Pos[0]",
 "execute if score #present g5 matches 1 if score #sel g5 matches 1 as $(NPCSEL 1 'yui') store result score #pz g5 run data get entity @s Pos[2]",
 "execute if score #present g5 matches 1 if score #sel g5 matches 2 as $(NPCSEL 2 'yui') store result score #px g5 run data get entity @s Pos[0]",
 "execute if score #present g5 matches 1 if score #sel g5 matches 2 as $(NPCSEL 2 'yui') store result score #pz g5 run data get entity @s Pos[2]")
Put 'lib/abort_obs.mcfunction' @(
 '# Adaptive mode lost sight of the NPC after p00 had confirmed observation: stop, never continue blind (no retries).',
 'execute if score #nu g5 matches 0 run say [G5] NOTE no untagged non-operator candidate found (NPC gone or no longer selectable)',
 'execute if score #nu g5 matches 2.. run say [G5] NOTE ambiguous: more than one untagged non-operator candidate (another NPC or human)',
 'say [G5] ABORT NPC observation lost in adaptive mode; dependent scenarios were not run',
 'tellraw @a[tag=g5_op] {"text":"[G5] ABORT: the NPC can no longer be observed (see log). Remaining scenarios were NOT run.","color":"red"}',
 'scoreboard players add #blocked g5 1','scoreboard players set #running g5 0')
Put 'lib/periodic_snap.mcfunction' @(
 '# M03 snapshot every #per polls (position + journal state = evidence independent of selectors).',
 'scoreboard players operation #m g5 = #tick g5','scoreboard players operation #m g5 %= #per g5',
 'execute if score #m g5 matches 0 run function npcortex_g5:lib/snap')
Put 'lib/await_snap.mcfunction' @(
 '# While a scenario waits for the owner gate: one M03 snapshot every 10 polls (5 s) so scripts/g5-gate.ps1 can judge quiescence.',
 'scoreboard players operation #m g5 = #tick g5','scoreboard players operation #m g5 %= #aw g5',
 'execute if score #m g5 matches 0 run function npcortex_g5:lib/snap')
Put 'lib/prime.mcfunction' @(
 'scoreboard players operation #lpx g5 = #px g5','scoreboard players operation #lpz g5 = #pz g5',
 'scoreboard players set #moved g5 0','scoreboard players set #moving g5 0','scoreboard players set #still g5 0')
Put 'lib/track.mcfunction' @(
 '# Coarse (1 block) movement tracking per 10-tick poll.',
 'scoreboard players set #moving g5 0',
 'execute unless score #px g5 = #lpx g5 run scoreboard players set #moving g5 1',
 'execute unless score #pz g5 = #lpz g5 run scoreboard players set #moving g5 1',
 'execute if score #moving g5 matches 1 run scoreboard players set #moved g5 1',
 'execute if score #moving g5 matches 1 run scoreboard players set #still g5 0',
 'execute if score #moving g5 matches 0 run scoreboard players add #still g5 1',
 'scoreboard players operation #lpx g5 = #px g5','scoreboard players operation #lpz g5 = #pz g5')
Put 'lib/poll.mcfunction' @('function npcortex_g5:lib/obs_yui','execute if score #present g5 matches 1 run function npcortex_g5:lib/track')
Put 'lib/snap.mcfunction' @("$OP debug cplus m03 snapshot yui")
Put 'lib/cancel_now.mcfunction' @(
 '# Snapshot (journal state at this instant) and cancel in the same tick; the snapshot proves which state was cancelled.',
 'scoreboard players set #cancelled g5 1','scoreboard players set #still g5 0',
 "$OP debug cplus m03 snapshot yui",
 '# Revision 6: no `c2 status` here - command feedback of a function is suppressed by vanilla, so it never reached the log. The longState at the cancel is recorded by production (D1-B).',
 "$OP c2 cancel yui",'say [G5] NOTE cancel issued')
Put 'lib/advance.mcfunction' @(
 'scoreboard players add #phase g5 1','scoreboard players set #tick g5 -1','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0',
 'execute if score #phase g5 > #last g5 run function npcortex_g5:lib/finished')
Put 'lib/finished.mcfunction' @(
 'execute if score #blocked g5 matches 0 run say [G5] SEQUENCE_DONE outcome=ALL_SCENARIOS_RAN (not a verdict)',
 'execute if score #blocked g5 matches 1.. run say [G5] SEQUENCE_DONE outcome=HAD_BLOCKED_SCENARIOS (not a verdict)',
 'scoreboard players set #running g5 0'
 'tellraw @a[tag=g5_op] {"text":"[G5] sequence finished. Run scripts/collect-g5-evidence.ps1 to package evidence.","color":"green"}')

# ---------------------------------------------------------------- scenario templates
# Phase files are generated; each runs once per 10-tick poll with #tick = polls since entry (0 on entry).
# Progression rule (revision 4): a phase advances by itself ONLY on in-game evidence that the NPC reached its target / stopped (adaptive REACHED,
# STOPPED_AFTER_CANCEL, NO_MOVEMENT_AFTER_REJOIN) or when nothing was dispatched (BLOCKED). Every other outcome - timeout, stall, never moved,
# cancel not triggered, and EVERY outcome of the owner-gated mode - enters AWAIT: the pack stops, keeps taking snapshots every 5 s and waits for
# /function npcortex_g5:gate (refused unless the scenario is awaiting) after scripts/g5-gate.ps1 printed GO from M03 snapshots + logged terminal lines.
$script:phases = New-Object System.Collections.ArrayList
function NewPhase($id, $title) { return [pscustomobject]@{ id=$id; title=$title; lines=(New-Object System.Collections.ArrayList) } }
function L($ph, [string]$s) { [void]$ph.lines.Add($s) }
function LB($ph, [scriptblock]$f) { foreach ($s in 1, 2) { L $ph (& $f $s) } }   # one line per selector kind (#sel 1 / 2)
function Latch($ph, $codes) {   # say OBS once, then arm settle countdown
    foreach ($e in $codes.GetEnumerator()) { L $ph "execute if score #result g5 matches $($e.Key) if score #settle g5 matches -1 run say [G5] OBS $($ph.id) $($e.Value)" }
}
function AwaitLines($ph, [string]$cond) {   # $cond selects the poll(s) in which the scenario enters AWAIT (settle is -1 exactly once)
    L $ph "execute $cond if score #settle g5 matches -1 run say [G5] AWAIT $($ph.id) owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1"
    L $ph "execute $cond if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {`"text`":`"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.`",`"color`":`"gold`"}"
    L $ph "execute $cond if score #settle g5 matches -1 run scoreboard players set #settle g5 -2"
}
function Finish($ph, [int]$reachSettle, [int]$otherSettle, [string[]]$cleanup, [int]$minTick = 0, [bool]$noGate = $false, [string]$awaitFn = 'npcortex_g5:lib/await_snap', [string[]]$mid = @()) {
    # gate-needing outcomes: 2 TIMEOUT, 3 STALLED, 6 NEVER_MOVED, 8 CANCEL_NOT_TRIGGERED, 10 AWAITING_OWNER_GATE (+ 1 and 7 when #strict = 1)
    L $ph 'scoreboard players set #gn g5 0'
    foreach ($c in '2..3', '6', '8', '10') { L $ph "execute if score #result g5 matches $c run scoreboard players set #gn g5 1" }
    if (-not $noGate) { foreach ($c in '1', '7') { L $ph "execute if score #strict g5 matches 1 if score #result g5 matches $c run scoreboard players set #gn g5 1" } }
    AwaitLines $ph 'if score #gn g5 matches 1'
    L $ph "execute if score #gn g5 matches 0 if score #result g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 $reachSettle"
    foreach ($c in '4..5', '7', '11') { L $ph "execute if score #gn g5 matches 0 if score #result g5 matches $c if score #settle g5 matches -1 run scoreboard players set #settle g5 $otherSettle" }
    L $ph "execute if score #settle g5 matches -2 run function $awaitFn"
    L $ph 'execute if score #settle g5 matches 1.. run scoreboard players remove #settle g5 1'
    foreach ($m in $mid) { L $ph $m }
    $gate = if ($minTick -gt 0) { " if score #tick g5 matches $minTick.." } else { '' }
    L $ph "execute if score #settle g5 matches 0$gate run function npcortex_g5:lib/snap"
    L $ph "execute if score #settle g5 matches 0$gate if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1"
    L $ph "execute if score #settle g5 matches 0$gate run say [G5] END $($ph.id)"
    foreach ($c in $cleanup) { L $ph "execute if score #settle g5 matches 0$gate run $c" }
    L $ph "execute if score #settle g5 matches 0$gate run function npcortex_g5:lib/advance"
}
$CODES = [ordered]@{ 1='REACHED'; 2='TIMEOUT'; 3='STALLED_TERMINAL_INFERRED'; 4='BLOCKED_NPC_NOT_SELECTABLE'; 5='BLOCKED_TARGET_CHUNK_NOT_LOADED'; 6='NEVER_MOVED'; 7='STOPPED_AFTER_CANCEL'; 8='CANCEL_NOT_TRIGGERED'; 10='AWAITING_OWNER_GATE' }

# Generic single-NPC "start a goal and watch the NPC" phase.
# Adaptive (p00 verified selector/tag/data/position): position is polled with the tag selector and drives early termination / cancel triggers.
# Owner-gated: the same commands run at fixed ticks; after the observation window ($fbEnd polls) the scenario AWAITS the owner gate.
# $fbEnd = poll at which the owner-gated observation window ends (default max(60, timeout/4)); $fbCancel = poll at which the gated mode cancels.
function NavPhase($id, $title, $startCmds, $preBlocks, $rx, $rz, $reachDist, $timeoutPolls, $pre, $post, $mode='reach', $trigger=$null, $measure=0, $minTick=0, $fbCancel=0, $fbEnd=0, $farFrom=0) {
    if ($preBlocks.Count -gt 0 -and $preBlocks[0] -isnot [array]) { $preBlocks = ,@($preBlocks) }
    if ($fbEnd -le 0) { $fbEnd = [math]::Max(60, [int]($timeoutPolls / 4)) }
    $cx = Ctr $rx; $cz = Ctr $rz
    $p = NewPhase $id $title
    L $p "# $id $title  (generated by scripts/build-g5-datapack.ps1; mode=$mode)"
    L $p "execute if score #tick g5 matches 0 run say [G5] BEGIN $id $title"
    L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start'
    # tick 3: adaptive mode needs the NPC observable; target chunks must already be loaded in both modes (no forceload anywhere)
    L $p "execute if score #tick g5 matches 3 $AD run function npcortex_g5:lib/obs_yui"
    L $p "execute if score #tick g5 matches 3 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
    foreach ($b in $preBlocks) { L $p "execute if score #tick g5 matches 3 if score #result g5 matches 0 unless block $($b[0]) -64 $($b[1]) minecraft:bedrock run scoreboard players set #result g5 5" }
    L $p "execute if score #tick g5 matches 3 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
    foreach ($c in $pre) { L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $c" }
    L $p 'execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
    if ($measure -gt 0) { L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $OP debug cplus m03 measure-auto 1 $measure" }
    foreach ($c in $startCmds) { L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $c" }
    # polls (adaptive tracking continues after a result so post-cancel movement is still observed)
    L $p "execute if score #tick g5 matches 5.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
    if ($mode -eq 'cancel') {   # keep snapshotting after the cancel: 'stopped and stayed stopped' needs several post-cancel positions
        L $p 'execute if score #tick g5 matches 5.. if score #result g5 matches 0 if score #cancelled g5 matches 0 run function npcortex_g5:lib/periodic_snap'
        L $p 'execute if score #tick g5 matches 5.. if score #cancelled g5 matches 1 unless score #settle g5 matches -2 run function npcortex_g5:lib/periodic_snap'
    } else { L $p 'execute if score #tick g5 matches 5.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap' }
    if ($mode -eq 'reach' -or $mode -eq 'noreach') {
        LB $p { param($s) "execute if score #tick g5 matches 5.. $AD if score #sel g5 matches $s if score #result g5 matches 0 if score #still g5 matches 3.. as $(NPCSEL $s 'yui') positioned $cx -60 $cz if entity @s[distance=..$reachDist] run scoreboard players set #result g5 1" }
        L $p "execute if score #tick g5 matches 5.. $AD if score #result g5 matches 0 if score #moved g5 matches 1 if score #still g5 matches 60.. run scoreboard players set #result g5 3"
    }
    if ($mode -eq 'cancel') {
        if ($farFrom -gt 0) {   # cancel only while the NPC is still far from the target: a stop at the destination is a completion, not a leg stop
            LB $p { param($s) "execute if score #tick g5 matches 5.. $AD if score #sel g5 matches $s if score #result g5 matches 0 if score #cancelled g5 matches 0 if score #moved g5 matches 1 $trigger as $(NPCSEL $s 'yui') positioned $cx -60 $cz unless entity @s[distance=..$farFrom] run function npcortex_g5:lib/cancel_now" }
        } else {
            L $p "execute if score #tick g5 matches 5.. $AD if score #result g5 matches 0 if score #cancelled g5 matches 0 if score #moved g5 matches 1 $trigger run function npcortex_g5:lib/cancel_now"
        }
        L $p "execute if score #tick g5 matches 5.. $AD if score #result g5 matches 0 if score #cancelled g5 matches 1 if score #still g5 matches 8.. run scoreboard players set #result g5 7"
        L $p ('execute if score #result g5 matches 7 if score #moving g5 matches 1 run say [G5] OBS ' + $id + ' MOVED_AFTER_CANCEL')
        L $p "execute if score #tick g5 matches $fbCancel $FB if score #result g5 matches 0 run function npcortex_g5:lib/cancel_now"
    }
    L $p "execute if score #tick g5 matches $timeoutPolls.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
    L $p "execute if score #tick g5 matches $fbEnd.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
    L $p "execute if score #result g5 matches 2 $AD if score #moved g5 matches 0 run scoreboard players set #result g5 6"
    if ($mode -eq 'cancel') { L $p "execute if score #result g5 matches 2 $AD if score #cancelled g5 matches 0 if score #moved g5 matches 1 run scoreboard players set #result g5 8" }
    Latch $p $CODES
    $reachSettle = if ($mode -eq 'cancel') { 60 } else { 30 }
    $otherSettle = if ($mode -eq 'cancel') { 40 } else { 5 }   # cancel: 20 s of post-cancel snapshots (>= 2 of them >= 6 s after the cancel)
    Finish $p $reachSettle $otherSettle $post $minTick
    L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
    [void]$script:phases.Add($p); return $p
}

# ---- p00 capability probe v2: seven INDEPENDENT capabilities, each PASS / FAIL / NOT_ATTEMPTED (never inferred from another) ----------
#   playerlist_control   @a (PlayerList) sees only the operator                           [negative control, informational]
#   world_limited_player @e[type=player, search box] sees operator + NPC
#   typeless_entity      @e[type=!item, search box, not operator] sees exactly the NPC
#   tag_v1 / tag_v2      tag add + verify + remove through the selector kind above (skipped = NOT_ATTEMPTED when that kind failed)
#   entity_data          data get entity Pos through the chosen selector
#   position             Pos agrees with the spawn block (2,2); M03 snapshots are the independent cross-check in the collector
# Score holders: -1 = NOT_ATTEMPTED, 0 = FAIL, 1 = PASS.
$p = NewPhase 'p00_capability_probe' 'C1 spawn + selector probe v2 (decides ADAPTIVE vs OWNER_GATED mode)'
L $p '# p00 (generated). A missing capability selects OWNER_GATED mode (M03 snapshot evidence + owner gate), never a blind run. say text must not contain @ (vanilla expands selectors in say).'
L $p 'execute if score #tick g5 matches 0 run say [G5] BEGIN p00_capability_probe capability probe v2'
L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start'
foreach ($cv in 'adaptive','probed','sel','nu') { L $p "execute if score #tick g5 matches 0 run scoreboard players set #$cv g5 0" }
foreach ($cv in 's_ctl','s_v1','s_v2','s_t1','s_t2','s_dat','s_pos') { L $p "execute if score #tick g5 matches 0 run scoreboard players set #$cv g5 -1" }
foreach ($cv in 'n_a','n_v1','n_v2') { L $p "execute if score #tick g5 matches 0 run scoreboard players set #$cv g5 0" }
L $p 'execute if score #tick g5 matches 0 run scoreboard players set #px g5 -999999'
L $p 'execute if score #tick g5 matches 0 run scoreboard players set #pz g5 -999999'
function Cap([string]$name, [string]$var, [int]$tick, [string]$pass, [string]$fail, [string]$na) {
    L $p "execute if score #tick g5 matches $tick if score #$var g5 matches 1 run say [G5] PROBE $name=PASS $pass"
    L $p "execute if score #tick g5 matches $tick if score #$var g5 matches 0 run say [G5] PROBE $name=FAIL $fail"
    if ($na) { L $p "execute if score #tick g5 matches $tick if score #$var g5 matches -1 run say [G5] PROBE $name=NOT_ATTEMPTED $na" }
}
# tick 3: three independent counts
L $p 'execute if score #tick g5 matches 3 store result score #n_a g5 if entity @a'
L $p "execute if score #tick g5 matches 3 store result score #n_v1 g5 if entity @e[type=player,$BOX]"
L $p "execute if score #tick g5 matches 3 store result score #n_v2 g5 if entity @e[type=!item,tag=!g5_op,$BOX]"
L $p 'execute if score #tick g5 matches 3 if score #n_a g5 matches 1 run scoreboard players set #s_ctl g5 1'
L $p 'execute if score #tick g5 matches 3 unless score #n_a g5 matches 1 run scoreboard players set #s_ctl g5 0'
L $p 'execute if score #tick g5 matches 3 if score #n_v1 g5 matches 2 run scoreboard players set #s_v1 g5 1'
L $p 'execute if score #tick g5 matches 3 unless score #n_v1 g5 matches 2 run scoreboard players set #s_v1 g5 0'
L $p 'execute if score #tick g5 matches 3 if score #n_v2 g5 matches 1 run scoreboard players set #s_v2 g5 1'
L $p 'execute if score #tick g5 matches 3 unless score #n_v2 g5 matches 1 run scoreboard players set #s_v2 g5 0'
Cap 'playerlist_control' 's_ctl' 3 '(PlayerList sees only the operator: the NPC is not a PlayerList member, as designed)' '(PlayerList sees a different number of players than 1: informational, the NPC may be a PlayerList member)' ''
Cap 'world_limited_player' 's_v1' 3 '(operator and NPC found by the player selector with the search box)' '(NPC not found, or more than one other player: see count)' ''
L $p 'execute if score #tick g5 matches 3 if score #n_v1 g5 matches ..1 run say [G5] PROBE world_limited_player_count=0_or_1 (only the operator or nobody)'
L $p 'execute if score #tick g5 matches 3 if score #n_v1 g5 matches 3.. run say [G5] PROBE world_limited_player_count=3_or_more (ambiguous: another player or NPC exists)'
Cap 'typeless_entity' 's_v2' 3 '(exactly one non-operator entity found by the typeless selector with the search box)' '(none, or more than one non-operator entity: ambiguous)' ''
L $p 'execute if score #tick g5 matches 3 if score #n_v2 g5 matches 0 run say [G5] PROBE typeless_entity_count=0 (no non-operator entity found)'
L $p 'execute if score #tick g5 matches 3 if score #n_v2 g5 matches 2.. run say [G5] PROBE typeless_entity_count=2_or_more (ambiguous: another entity exists)'
# tick 4: tag add / verify / remove, each kind only when its own selector passed
L $p "execute if score #tick g5 matches 4 if score #s_v1 g5 matches 1 run tag $(CANDSEL 1 ',limit=1') add g5_pv1"
L $p "execute if score #tick g5 matches 4 if score #s_v1 g5 matches 1 store success score #s_t1 g5 if entity @e[type=player,tag=g5_pv1,$BOX]"
L $p "execute if score #tick g5 matches 4 if score #s_v1 g5 matches 1 run tag @e[type=player,tag=g5_pv1,$BOX] remove g5_pv1"
L $p "execute if score #tick g5 matches 4 if score #s_v2 g5 matches 1 run tag $(CANDSEL 2 ',limit=1') add g5_pv2"
L $p "execute if score #tick g5 matches 4 if score #s_v2 g5 matches 1 store success score #s_t2 g5 if entity @e[tag=g5_pv2,$BOX]"
L $p "execute if score #tick g5 matches 4 if score #s_v2 g5 matches 1 run tag @e[tag=g5_pv2,$BOX] remove g5_pv2"
L $p 'execute if score #tick g5 matches 4 run function npcortex_g5:lib/snap'
Cap 'tag_v1' 's_t1' 4 '(tag add and verify through the player selector)' '(tag was not found after adding)' '(world_limited_player did not pass)'
Cap 'tag_v2' 's_t2' 4 '(tag add and verify through the typeless selector)' '(tag was not found after adding)' '(typeless_entity did not pass)'
# tick 5: choose the selector kind (player selector preferred: it scans only ServerLevel.players), adopt yui
L $p 'execute if score #tick g5 matches 5 if score #s_t1 g5 matches 1 run scoreboard players set #sel g5 1'
L $p 'execute if score #tick g5 matches 5 unless score #sel g5 matches 1 if score #s_t2 g5 matches 1 run scoreboard players set #sel g5 2'
L $p 'execute if score #tick g5 matches 5 unless score #sel g5 matches 0 run function npcortex_g5:lib/adopt_yui'
L $p 'execute if score #tick g5 matches 5 if score #sel g5 matches 1 run say [G5] PROBE selector_chosen=world_limited_player'
L $p 'execute if score #tick g5 matches 5 if score #sel g5 matches 2 run say [G5] PROBE selector_chosen=typeless_entity'
L $p 'execute if score #tick g5 matches 5 if score #sel g5 matches 0 run say [G5] PROBE selector_chosen=NONE (no selector kind passed selector and tag together)'
# tick 6: entity data + position through the chosen selector
LB $p { param($s) "execute if score #tick g5 matches 6 if score #sel g5 matches $s as $(NPCSEL $s 'yui') store success score #s_dat g5 run data get entity @s Pos[0]" }
LB $p { param($s) "execute if score #tick g5 matches 6 if score #sel g5 matches $s as $(NPCSEL $s 'yui') store result score #px g5 run data get entity @s Pos[0]" }
LB $p { param($s) "execute if score #tick g5 matches 6 if score #sel g5 matches $s as $(NPCSEL $s 'yui') store result score #pz g5 run data get entity @s Pos[2]" }
L $p 'execute if score #tick g5 matches 6 if score #s_dat g5 matches 1 run scoreboard players set #s_pos g5 0'
L $p 'execute if score #tick g5 matches 6 if score #s_dat g5 matches 1 if score #px g5 matches 1..3 if score #pz g5 matches 1..3 run scoreboard players set #s_pos g5 1'
Cap 'entity_data' 's_dat' 6 '(data get entity Pos works on the NPC)' '(data get entity Pos failed on the NPC)' '(no selector kind passed, or the adopted NPC was not selected)'
Cap 'position' 's_pos' 6 '(Pos agrees with the spawn block 2,2)' '(Pos does not agree with the spawn block 2,2)' '(entity data was not readable)'
# tick 7: decision
L $p 'execute if score #tick g5 matches 7 unless score #sel g5 matches 0 if score #s_dat g5 matches 1 if score #s_pos g5 matches 1 run scoreboard players set #adaptive g5 1'
L $p 'execute if score #tick g5 matches 7 if score #adaptive g5 matches 1 run scoreboard players set #per g5 10'
L $p 'execute if score #tick g5 matches 7 if score #adaptive g5 matches 0 run scoreboard players set #per g5 5'
L $p 'execute if score #tick g5 matches 7 run scoreboard players set #probed g5 1'
L $p 'execute if score #tick g5 matches 7 if score #adaptive g5 matches 1 run say [G5] PROBE mode=ADAPTIVE (in-game observation verified; M03 snapshots stay the independent evidence)'
L $p 'execute if score #tick g5 matches 7 if score #adaptive g5 matches 0 run say [G5] PROBE mode=OWNER_GATED (M03 snapshots + logs are the evidence; progression needs scripts/g5-gate.ps1 and the owner gate)'
L $p 'execute if score #tick g5 matches 7 if score #adaptive g5 matches 1 run scoreboard players set #result g5 1'
L $p 'execute if score #tick g5 matches 7 if score #adaptive g5 matches 0 run scoreboard players set #result g5 11'
Latch $p ([ordered]@{ 1='ADAPTIVE'; 11='OWNER_GATED' })
Finish $p 3 3 @() 0 $true
[void]$script:phases.Add($p)

# ---- navigation ------------------------------------------------------------------------------
foreach ($d in @(@('p02_navlong_20','navlong 20 blocks',22,2,350),
                 @('p03_navlong_40','navlong 40 blocks (west)',-38,2,430),
                 @('p04_navlong_80','navlong 80 blocks, multi-chunk',82,2,590),
                 @('p05_navlong_150','navlong 150 blocks, multi-chunk',152,2,870),
                 @('p06_navlong_diag60','navlong diagonal 60/60, multi-chunk',62,62,510))) {
    $null = NavPhase $d[0] $d[1] @("$OP c2 navlong yui $($d[2]) -60 $($d[3])") @(@($d[2],$d[3])) $d[2] $d[3] 1.6 $d[4] @() @()
}
# ---- obstacles ---------------------------------------------------------------------------------
$null = NavPhase 'p07_small_wall_detour' 'small wall (11 wide) between start and target: detour expected' @("$OP c2 navlong yui 32 -60 2") @(@(32,2),@(12,-3),@(12,7)) 32 2 1.6 430 @('fill 12 -60 -3 12 -58 7 minecraft:stone') @('fill 12 -60 -3 12 -58 7 minecraft:air')
$null = NavPhase 'p08_large_wall_no_progress' 'wall z -80..80 (3 high): bounded LONG_NO_PROGRESS/OSCILLATION expected' @("$OP c2 navlong yui 62 -60 2") @(@(62,2),@(12,-80),@(12,80)) 62 2 1.6 800 @('fill 12 -60 -80 12 -58 80 minecraft:stone') @('fill 12 -60 -80 12 -58 80 minecraft:air') 'noreach' $null 240 0
# ---- cancellation (the gated mode cancels at a FIXED poll; the snapshot taken in the same tick proves which journal state was cancelled) ----
$null = NavPhase 'p09_cancel_leg_active' 'cancel while the NPC is walking (state MOVING in snapshot)' @("$OP c2 navlong yui 82 -60 2") @(@(82,2)) 82 2 1.6 500 @() @() 'cancel' 'if score #moving g5 matches 1' 0 480 14 90
# p10 (revision 5): 150 blocks (several legs) and the cancel only counts at a stop that is still >= 25 blocks from the target, so it cannot land after the
# goal completed. Two separate verdicts in the collector: cancellation safety vs LEG_QUIESCING race coverage (the latter only with a chat status sample).
# ---- p10 (revision 6): up to FIVE attempts. The Rev5 trigger (a 10-tick stationary poll) could not see the 2-3 tick leg quiesce of a 150 block navlong
# (a leg is about 6 blocks, the quiesce adds 0.10-0.15 s), and `c2 status` cannot be echoed from a function. Now a per-tick watch (own 1t schedule, bounded: it runs only while
# #w = 1, which every attempt end, cancel and result clears) compares the NPC position in 1/1000 block and cancels in the first tick the NPC stands still after >= 10 moving
# ticks while it is still >= 25 blocks from the target. The production diagnostic D1-B records the longState at the cancel() invocation; nothing here decides coverage.
function P10Phase {
    $id = 'p10_cancel_quiescing'
    $p = NewPhase $id 'cancel at the first stand-still tick after moving while far from the target (<= 5 attempts, per-tick trigger; production diagnostic D1-B records longState)'
    L $p "# $id (generated; revision 6). Attempt counter #att (1..5), per-tick watch lib/p10_watch, cancel lib/p10_cancel."
    L $p 'execute if score #tick g5 matches 0 unless score #p10on g5 matches 1 run function npcortex_g5:lib/p10_init'
    L $p "execute if score #tick g5 matches 0 if score #att g5 matches 1 run say [G5] BEGIN $id cancel at the first stand-still tick after moving while far from the target (up to 5 attempts)"
    foreach ($k in 1..5) { L $p "execute if score #tick g5 matches 0 if score #att g5 matches $k run say [G5] NOTE attempt $k" }
    L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start'
    L $p "execute if score #tick g5 matches 3 $AD run function npcortex_g5:lib/obs_yui"
    L $p "execute if score #tick g5 matches 3 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
    L $p 'execute if score #tick g5 matches 3 if score #result g5 matches 0 unless block 152 -64 2 minecraft:bedrock run scoreboard players set #result g5 5'
    L $p "execute if score #tick g5 matches 3 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
    L $p 'execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
    L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $OP c2 navlong yui 152 -60 2"
    L $p "execute if score #tick g5 matches 4 $AD if score #result g5 matches 0 run function npcortex_g5:lib/p10_arm"
    L $p "execute if score #tick g5 matches 5.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
    L $p 'execute if score #tick g5 matches 5.. if score #result g5 matches 0 if score #cancelled g5 matches 0 run function npcortex_g5:lib/periodic_snap'
    L $p 'execute if score #tick g5 matches 5.. if score #cancelled g5 matches 1 unless score #settle g5 matches -2 run function npcortex_g5:lib/periodic_snap'
    # explicit exit: the goal reached its target without a cancel (the trigger never fired)
    LB $p { param($s) "execute if score #tick g5 matches 5.. $AD if score #sel g5 matches $s if score #result g5 matches 0 if score #cancelled g5 matches 0 if score #still g5 matches 3.. as $(NPCSEL $s 'yui') positioned 152.5 -60 2.5 if entity @s[distance=..1.6] run scoreboard players set #result g5 1" }
    L $p "execute if score #tick g5 matches 5.. $AD if score #result g5 matches 0 if score #cancelled g5 matches 1 if score #still g5 matches 8.. run scoreboard players set #result g5 7"
    L $p ('execute if score #result g5 matches 7 if score #moving g5 matches 1 run say [G5] OBS ' + $id + ' MOVED_AFTER_CANCEL')
    # bounded timeout (75 s per attempt; the walk takes about 46 s): a goal that moved but was never cancelled is cancelled as CLEANUP (noted, not counted as an attempt cancel)
    L $p "execute if score #tick g5 matches 150.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
    L $p "execute if score #result g5 matches 2 $AD if score #moved g5 matches 0 run scoreboard players set #result g5 6"
    L $p 'execute if score #result g5 matches 2 run function npcortex_g5:lib/p10_timeout'
    L $p "execute if score #tick g5 matches 24.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
    L $p 'execute if score #result g5 matches 1.. run scoreboard players set #w g5 0'
    Latch $p ([ordered]@{ 1='COMPLETED_WITHOUT_CANCEL'; 4='BLOCKED_NPC_NOT_SELECTABLE'; 5='BLOCKED_TARGET_CHUNK_NOT_LOADED'; 6='NEVER_MOVED'; 7='STOPPED_AFTER_CANCEL'; 10='AWAITING_OWNER_GATE'; 11='ATTEMPT_TIMEOUT_CLEANED_UP' })
    # the next attempt starts only after the settle of THIS one (20 s of post-cancel snapshots after a cancel) and only while attempts remain (#att <= 4)
    $mid = @(); foreach ($c in '1', '7', '11') { $mid += "execute if score #settle g5 matches 0 if score #att g5 matches ..4 if score #result g5 matches $c run function npcortex_g5:lib/p10_next" }
    Finish $p 10 40 @('scoreboard players set #w g5 0', 'scoreboard players set #p10on g5 0', 'scoreboard players set #att g5 0') 0 $false 'npcortex_g5:lib/await_snap' $mid
    L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
    [void]$script:phases.Add($p)
}
Put 'lib/p10_init.mcfunction' @('# first attempt of p10','scoreboard players set #p10on g5 1','scoreboard players set #att g5 1','scoreboard players set #w g5 0')
Put 'lib/p10_next.mcfunction' @('# next attempt (bounded by the #att <= 4 condition of the caller)','say [G5] NOTE attempt_end','scoreboard players add #att g5 1','scoreboard players set #tick g5 -1','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0','scoreboard players set #w g5 0')
Put 'lib/p10_arm.mcfunction' @('# arm the per-tick watch for this attempt','scoreboard players set #lqx g5 -999999','scoreboard players set #lqz g5 -999999','scoreboard players set #qrun g5 0','scoreboard players set #q g5 0','scoreboard players set #w g5 1','function npcortex_g5:lib/p10_watch')
$wl = @('# p10 per-tick watch (generated). Bounded: re-schedules itself ONLY while #w = 1; attempt end, cancel and every result clear #w.',
        'scoreboard players set #qx g5 -999998','scoreboard players set #qz g5 -999998')
foreach ($s in 1, 2) { $wl += "execute if score #w g5 matches 1 if score #sel g5 matches $s as $(NPCSEL $s 'yui') store result score #qx g5 run data get entity @s Pos[0] 1000"; $wl += "execute if score #w g5 matches 1 if score #sel g5 matches $s as $(NPCSEL $s 'yui') store result score #qz g5 run data get entity @s Pos[2] 1000" }
$wl += @('scoreboard players set #q g5 0',
         'execute unless score #qx g5 matches -999998 if score #qx g5 = #lqx g5 if score #qz g5 = #lqz g5 run scoreboard players set #q g5 1',
         '# cancel once: the first tick the NPC stands still after >= 10 moving ticks, still >= 25 blocks from the target, never twice (#cancelled, #w)')
foreach ($s in 1, 2) { $wl += "execute if score #w g5 matches 1 if score #cancelled g5 matches 0 if score #q g5 matches 1 if score #qrun g5 matches 10.. if score #sel g5 matches $s as $(NPCSEL $s 'yui') positioned 152.5 -60 2.5 unless entity @s[distance=..25] run function npcortex_g5:lib/p10_cancel" }
$wl += @('execute unless score #qx g5 matches -999998 if score #q g5 matches 0 run scoreboard players add #qrun g5 1',
         'execute if score #q g5 matches 1 run scoreboard players set #qrun g5 0',
         'scoreboard players operation #lqx g5 = #qx g5','scoreboard players operation #lqz g5 = #qz g5',
         'execute if score #w g5 matches 1 run schedule function npcortex_g5:lib/p10_watch 1t replace')
Put 'lib/p10_watch.mcfunction' $wl
$cl = @('# p10 cancel (generated): the journal snapshot of the cancel tick, then the ONE cancel of this attempt. D1-B logs longState at the cancel() invocation.',
        'scoreboard players set #cancelled g5 1','scoreboard players set #w g5 0','scoreboard players set #still g5 0')
foreach ($k in 1..5) { $cl += "execute if score #att g5 matches $k run say [G5] NOTE cancel attempt $k" }
$cl += @("$OP debug cplus m03 snapshot yui","$OP c2 cancel yui")
Put 'lib/p10_cancel.mcfunction' $cl
Put 'lib/p10_timeout.mcfunction' @('# attempt timed out after the NPC had moved: clean up (this is NOT an attempt cancel)','scoreboard players set #w g5 0',
        'execute if score #moved g5 matches 1 run say [G5] NOTE cleanup_cancel',"execute if score #moved g5 matches 1 run $OP c2 cancel yui",'execute if score #moved g5 matches 1 run scoreboard players set #result g5 11')
P10Phase

# ---- recovery: Save&Quit checkpoint (MANUAL) then no-replay + new movement --------------------
# p11 walks 150 blocks (about 40 s) so the NPC is still mid-goal while the owner gate is used in owner-gated mode.
$p = NewPhase 'p11_savequit_checkpoint' 'start navlong 150, then pause for Save & Quit (MANUAL checkpoint)'
L $p '# p11 checkpoint (generated). Adaptive: the checkpoint starts when the NPC is seen walking. Owner-gated: the gate (g5-gate.ps1 saw a mid-goal M03 snapshot) starts it.'
L $p 'execute if score #tick g5 matches 0 run say [G5] BEGIN p11_savequit_checkpoint'
L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start'
L $p "execute if score #tick g5 matches 3 $AD run function npcortex_g5:lib/obs_yui"
L $p "execute if score #tick g5 matches 3 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
L $p 'execute if score #tick g5 matches 3 if score #result g5 matches 0 unless block 152 -64 2 minecraft:bedrock run scoreboard players set #result g5 5'
L $p "execute if score #tick g5 matches 3 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
L $p 'execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $OP c2 navlong yui 152 -60 2"
L $p "execute if score #tick g5 matches 5.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
L $p 'execute if score #tick g5 matches 5.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap'
L $p "execute if score #tick g5 matches 5.. $AD if score #result g5 matches 0 if score #moved g5 matches 1 if score #moving g5 matches 1 run scoreboard players set #result g5 9"
L $p "execute if score #tick g5 matches 24.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
L $p "execute if score #tick g5 matches 700.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
L $p 'execute if score #result g5 matches 9 run function npcortex_g5:lib/checkpoint_savequit'
L $p "execute if score #result g5 matches 2 $AD if score #moved g5 matches 0 run scoreboard players set #result g5 6"
L $p 'execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p11_savequit_checkpoint AWAITING_OWNER_GATE'
AwaitLines $p 'if score #result g5 matches 10'
L $p 'execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap'
L $p 'execute if score #result g5 matches 2..8 run say [G5] OBS p11_savequit_checkpoint ABORTED_BEFORE_CHECKPOINT'
L $p 'execute if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1'
L $p 'execute if score #result g5 matches 2..8 run function npcortex_g5:lib/finished'
[void]$script:phases.Add($p)
Put 'lib/checkpoint_savequit.mcfunction' @(
 'function npcortex_g5:lib/snap',
 'say [G5] CHECKPOINT save_quit NPC is walking a navlong goal; world will be saved by Save and Quit',
 'tellraw @a[tag=g5_op] [{"text":"[G5] CHECKPOINT: now press Esc > Save and Quit to Title (while yui is walking). Re-open THIS world, then run ","color":"gold"},{"text":"/function npcortex_g5:rejoin","color":"aqua","clickEvent":{"action":"suggest_command","value":"/function npcortex_g5:rejoin"}}]',
 'scoreboard players set #running g5 0','scoreboard players set #cp g5 1',
 'scoreboard players set #phase g5 12','scoreboard players set #tick g5 -1','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0',
 '# recovery group runs p12 and p13 after the rejoin; a full start keeps its own #last',
 'execute if score #last g5 matches ..12 run scoreboard players set #last g5 13')
$p = NewPhase 'p12_rejoin_no_replay' 'after rejoin: NPC must not resume the old goal (no movement for 20 s)'
L $p '# p12 after rejoin (generated); no fresh_start: the restored NPC is what is being observed. Evidence: M03 snapshots; adaptive mode adds tag observation (re-adopted after the reload).'
L $p 'execute if score #tick g5 matches 0 run say [G5] BEGIN p12_rejoin_no_replay'
L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/reset'
L $p "execute if score #tick g5 matches 5 $AD run function npcortex_g5:lib/obs_yui"
L $p "execute if score #tick g5 matches 5 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
L $p "execute if score #tick g5 matches 5 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
L $p 'execute if score #tick g5 matches 5 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
L $p "execute if score #tick g5 matches 6.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
L $p 'execute if score #tick g5 matches 6.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap'
L $p "execute if score #tick g5 matches 6.. $AD if score #result g5 matches 0 if score #moved g5 matches 1 run scoreboard players set #result g5 3"
L $p "execute if score #tick g5 matches 6.. $AD if score #result g5 matches 0 if score #moved g5 matches 0 if score #still g5 matches 40.. run scoreboard players set #result g5 1"
L $p "execute if score #tick g5 matches 100.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
L $p "execute if score #tick g5 matches 50.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
L $p 'execute if score #result g5 matches 1 if score #settle g5 matches -1 run say [G5] OBS p12_rejoin_no_replay NO_MOVEMENT_AFTER_REJOIN'
L $p 'execute if score #result g5 matches 3 if score #settle g5 matches -1 run say [G5] OBS p12_rejoin_no_replay MOVED_AFTER_REJOIN'
L $p 'execute if score #result g5 matches 2 if score #settle g5 matches -1 run say [G5] OBS p12_rejoin_no_replay TIMEOUT'
L $p 'execute if score #result g5 matches 4 if score #settle g5 matches -1 run say [G5] OBS p12_rejoin_no_replay BLOCKED_NPC_NOT_SELECTABLE'
L $p 'execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p12_rejoin_no_replay AWAITING_OWNER_GATE'
Finish $p 5 5 @()
L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
[void]$script:phases.Add($p)
$null = NavPhase 'p13_new_navlong_after_recovery' 'new navlong 20 works after recovery' @("$OP c2 navlong yui 22 -60 2") @(@(22,2)) 22 2 1.6 700 @() @()

# ---- regression (E3 acceptance stays MANUAL) -------------------------------------------------
$null = NavPhase 'p14_regress_c2_navigate' 'existing c2 navigate (4 blocks)' @("$OP c2 navigate yui 6 -60 2") @(@(6,2)) 6 2 1.6 400 @() @()
$null = NavPhase 'p15_regress_c2_move' 'existing c2 move (3.5 blocks)' @("$OP c2 move yui 5.5 -60 2.5") @(@(5,2)) 5 2 1.6 400 @() @()
$p = NewPhase 'p16_regress_follow_start' 'follow: NPC must come to the operator 28 blocks away'
L $p '# p16 follow start (generated)'
L $p 'execute if score #tick g5 matches 0 run say [G5] BEGIN p16_regress_follow_start'
L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start'
L $p "execute if score #tick g5 matches 3 $AD run function npcortex_g5:lib/obs_yui"
L $p "execute if score #tick g5 matches 3 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
L $p "execute if score #tick g5 matches 3 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
L $p 'execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $OP debug cplus m03 follow-self yui"
L $p 'execute if score #tick g5 matches 5 if score #result g5 matches 0 run tp @a[tag=g5_op] 30.5 -60 2.5'
L $p "execute if score #tick g5 matches 5.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
L $p 'execute if score #tick g5 matches 5.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap'
LB $p { param($s) "execute if score #tick g5 matches 8.. $AD if score #sel g5 matches $s if score #result g5 matches 0 as $(NPCSEL $s 'yui') at @s if entity @a[tag=g5_op,distance=..5] run scoreboard players set #result g5 1" }
L $p "execute if score #tick g5 matches 400.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
L $p "execute if score #tick g5 matches 60.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
L $p "execute if score #result g5 matches 2 $AD if score #moved g5 matches 0 run scoreboard players set #result g5 6"
Latch $p $CODES
Finish $p 10 5 @()      # follow stays active; p17 unfollows
L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
[void]$script:phases.Add($p)
$p = NewPhase 'p17_regress_follow_stop' 'unfollow: NPC must stop although the operator moves away'
L $p '# p17 unfollow (generated); keeps the NPC of p16'
L $p 'execute if score #tick g5 matches 0 run say [G5] BEGIN p17_regress_follow_stop'
L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/reset'
L $p "execute if score #tick g5 matches 1 $AD run function npcortex_g5:lib/obs_yui"
L $p "execute if score #tick g5 matches 1 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
L $p "execute if score #tick g5 matches 1 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
L $p 'execute if score #tick g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
L $p "execute if score #tick g5 matches 2 if score #result g5 matches 0 run $OP debug cplus m03 unfollow yui"
L $p 'execute if score #tick g5 matches 3 if score #result g5 matches 0 run tp @a[tag=g5_op] -60.5 -60 2.5'
L $p "execute if score #tick g5 matches 4.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
L $p 'execute if score #tick g5 matches 4.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap'
L $p "execute if score #tick g5 matches 8.. $AD if score #result g5 matches 0 if score #still g5 matches 20.. run scoreboard players set #result g5 1"
L $p "execute if score #tick g5 matches 200.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
L $p "execute if score #tick g5 matches 40.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
Latch $p $CODES
Finish $p 5 5 @()
L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
[void]$script:phases.Add($p)
$p = NewPhase 'p18_regress_e3_taskmove' 'C+ E3 guarded move via M03 bridge (observe displacement; acceptance needs G3 finalize = MANUAL)'
L $p '# p18 E3 (generated): only observes that the NPC moved and stopped; E3 terminal/ACK acceptance is NOT inferred'
L $p 'execute if score #tick g5 matches 0 run say [G5] BEGIN p18_regress_e3_taskmove'
L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start'
L $p "execute if score #tick g5 matches 3 $AD run function npcortex_g5:lib/obs_yui"
L $p "execute if score #tick g5 matches 3 $AD unless score #present g5 matches 1 run scoreboard players set #result g5 4"
L $p "execute if score #tick g5 matches 3 $AD if score #result g5 matches 0 run function npcortex_g5:lib/prime"
L $p 'execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap'
L $p "execute if score #tick g5 matches 4 if score #result g5 matches 0 run $OP debug cplus m03 move yui"
L $p "execute if score #tick g5 matches 5.. $AD if score #present g5 matches 1 run function npcortex_g5:lib/poll"
L $p 'execute if score #tick g5 matches 5.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap'
L $p "execute if score #tick g5 matches 5.. $AD if score #result g5 matches 0 if score #moved g5 matches 1 if score #still g5 matches 30.. run scoreboard players set #result g5 10"
L $p "execute if score #tick g5 matches 600.. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
L $p "execute if score #tick g5 matches 100.. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
L $p "execute if score #result g5 matches 2 $AD if score #moved g5 matches 0 run scoreboard players set #result g5 6"
# Rev6: movement is not G3 acceptance. Immediately after the move the OWNER types the finalize (the answer, with the durable ACK, appears only for typed commands) and the status.
L $p 'execute if score #result g5 matches 10 if score #settle g5 matches -1 if score #moved g5 matches 1 run say [G5] OBS p18_regress_e3_taskmove MOVED_AWAITING_TYPED_FINALIZE'
L $p 'execute if score #result g5 matches 10 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] [{"text":"[G5] p18: type NOW, as the operator and in this order: ","color":"gold"},{"text":"/npcortex debug cplus taskmovefinalize yui","color":"aqua"},{"text":"  then  ","color":"gold"},{"text":"/npcortex debug cplus taskmovestatus yui","color":"aqua"},{"text":"  - wait for each answer in chat (G3 COMPLETED ... durable ACK). Only then run scripts/g5-gate.ps1 and the gate command.","color":"gold"}]'
Latch $p $CODES
Finish $p 20 5 @()
L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
[void]$script:phases.Add($p)

# ---- multi NPC 1/3/5/10 (adaptive: tag positions; owner-gated: final per-NPC M03 snapshots) -------
foreach ($n in @(1,3,5,10)) {
    $id = "p{0}_multi_{1}_npc" -f (@{1=19;3=20;5=21;10=22}[$n]), $n
    $p = NewPhase $id "$n NPC concurrent navlong 40 (one lane each, FIFO) + M03 tick measurement"
    L $p "# $id (generated). lane k: NPC block z=8k+2, goal (42,-60,8k+2). Needs exactly $n live NPCs for measure-auto."
    L $p "execute if score #tick g5 matches 0 run say [G5] BEGIN $id"
    L $p 'execute if score #tick g5 matches 0 run function npcortex_g5:lib/reset'
    foreach ($nm in $names) { L $p "execute if score #tick g5 matches 0 run $OP c1 despawn $nm" }
    for ($k=0; $k -lt 10; $k++) { L $p "execute if score #tick g5 matches 0 run scoreboard players set #r$k g5 0" }
    # NPCs are spawned ONE per poll and adopted (tagged) on the next poll, so exactly one untagged candidate exists at each adoption.
    for ($k=0; $k -lt $n; $k++) {
        L $p "execute if score #tick g5 matches $(1+2*$k) run tp @a[tag=g5_op] 0.5 -60 $(8*$k).5"
        L $p "execute if score #tick g5 matches $(1+2*$k) run $OP c1 spawn $($names[$k])"
        L $p "execute if score #tick g5 matches $(2+2*$k) $AD run function npcortex_g5:lib/adopt_$($names[$k])"
    }
    $t1 = 2*$n + 2                       # all NPCs adopted: preconditions
    L $p "execute if score #tick g5 matches $(2*$n+1) run tp @a[tag=g5_op] 0.5 -60 0.5"
    for ($k=0; $k -lt $n; $k++) {
        $z=8*$k+2; $nm = $names[$k]
        LB $p { param($s) "execute if score #tick g5 matches $t1 $AD if score #sel g5 matches $s unless entity $(NPCSEL $s $nm) run scoreboard players set #result g5 4" }
        L $p "execute if score #tick g5 matches $t1 if score #result g5 matches 0 unless block 42 -64 $z minecraft:bedrock run scoreboard players set #result g5 5"
    }
    for ($k=0; $k -lt $n; $k++) { L $p "execute if score #tick g5 matches $($t1+1) if score #result g5 matches 0 run $OP debug cplus m03 snapshot $($names[$k])" }
    L $p "execute if score #tick g5 matches $($t1+1) if score #result g5 matches 0 run $OP debug cplus m03 measure-auto $n 120"
    for ($k=0; $k -lt $n; $k++) { L $p "execute if score #tick g5 matches $($t1+2) if score #result g5 matches 0 run $OP c2 navlong $($names[$k]) 42 -60 $(8*$k+2)" }
    $t3 = $t1 + 3
    for ($k=0; $k -lt $n; $k++) {
        $nm = $names[$k]; $zc = Ctr (8*$k+2)
        LB $p { param($s) "execute if score #tick g5 matches $t3.. $AD if score #sel g5 matches $s if score #result g5 matches 0 as $(NPCSEL $s $nm) positioned 42.5 -60 $zc if entity @s[distance=..1.6] run scoreboard players set #r$k g5 1" }
    }
    L $p "execute if score #tick g5 matches $t3.. $AD run scoreboard players set #rsum g5 0"
    for ($k=0; $k -lt $n; $k++) { L $p "execute if score #tick g5 matches $t3.. $AD run scoreboard players operation #rsum g5 += #r$k g5" }
    L $p "execute if score #tick g5 matches $t3.. $AD if score #result g5 matches 0 if score #rsum g5 matches $n run scoreboard players set #result g5 1"
    L $p "execute if score #tick g5 matches $($t3+592).. $AD if score #result g5 matches 0 run scoreboard players set #result g5 2"
    L $p "execute if score #tick g5 matches $($t1 + 255).. $FB if score #result g5 matches 0 run scoreboard players set #result g5 10"
    $codes = [ordered]@{ 1='ALL_REACHED'; 2='TIMEOUT'; 4='BLOCKED_NPC_NOT_SELECTABLE'; 5='BLOCKED_TARGET_CHUNK_NOT_LOADED'; 10='AWAITING_OWNER_GATE' }
    Latch $p $codes
    # hold >= 255 polls (2550 ticks) so the 120 s M03 measurement (2400 ticks) is finished before the next one
    $per = @(); for ($k=0; $k -lt $n; $k++) { $per += "execute if score #r$k g5 matches 1 run say [G5] OBS $id NPC_$($names[$k]) REACHED"; $per += "execute $AD if score #r$k g5 matches 0 run say [G5] OBS $id NPC_$($names[$k]) NOT_REACHED"; $per += "$OP debug cplus m03 snapshot $($names[$k])" }
    # while awaiting, every lane is snapshotted (the gate needs a terminal + quiescent state of EACH NPC)
    $aw = @('scoreboard players operation #m g5 = #tick g5', 'scoreboard players operation #m g5 %= #aw g5'); for ($k=0; $k -lt $n; $k++) { $aw += "execute if score #m g5 matches 0 run $OP debug cplus m03 snapshot $($names[$k])" }
    Put "lib/await_snap_$n.mcfunction" $aw
    Finish $p 20 5 $per ($t1 + 255) $false "npcortex_g5:lib/await_snap_$n"
    L $p 'execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs'
    [void]$script:phases.Add($p)
}

# ---------------------------------------------------------------- emit phase files + dispatcher + owner-gate functions
$i = 0
foreach ($ph in $script:phases) { $i++; Put ("ph/{0}.mcfunction" -f $ph.id) $ph.lines.ToArray(); $ph | Add-Member -NotePropertyName num -NotePropertyValue $i }
$disp = @('# Runs the current phase once per poll. Phase files may advance #phase; later lines then see tick -1 and do nothing.')
foreach ($ph in $script:phases) { $disp += "execute if score #phase g5 matches $($ph.num) run function npcortex_g5:ph/$($ph.id)" }
$disp += 'scoreboard players add #tick g5 1'
# watchdog: operator must stay online
$disp += 'execute unless entity @a[tag=g5_op] run function npcortex_g5:lib/abort_no_operator'
Put 'dispatch.mcfunction' $disp
Put 'lib/abort_no_operator.mcfunction' @('say [G5] ABORT operator offline','scoreboard players set #running g5 0')
Put 'loop.mcfunction' @(
 'execute if score #running g5 matches 1 run function npcortex_g5:dispatch',
 'execute if score #running g5 matches 1 run schedule function npcortex_g5:loop 10t replace')

# Owner gate: releases ONE awaiting scenario. Refused unless the run is active, this is the current phase and it is AWAITING (#settle = -2).
# The decision whether it is safe is made by scripts/g5-gate.ps1 from M03 snapshots + the logged terminal lines; this function is only the owner's release.
foreach ($ph in $script:phases) {
    $num = $ph.num; $id = $ph.id
    $lines = @(
     "# owner gate for $id (generated)",
     "execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress",
     "execute if score #running g5 matches 1 unless score #phase g5 matches $num run say [G5] NOTE gate refused: $id is not the current phase",
     "execute if score #running g5 matches 1 if score #phase g5 matches $num unless score #settle g5 matches -2 run say [G5] NOTE gate refused: $id is not awaiting the gate",
     "execute if score #running g5 matches 1 if score #phase g5 matches $num if score #settle g5 matches -2 run say [G5] GATE $id RELEASED")
    if ($id -like 'p11_*') { $lines += "execute if score #running g5 matches 1 if score #phase g5 matches $num if score #settle g5 matches -2 run function npcortex_g5:lib/checkpoint_savequit" }
    else { $lines += "execute if score #running g5 matches 1 if score #phase g5 matches $num if score #settle g5 matches -2 run scoreboard players set #settle g5 1" }
    Put "gate/$id.mcfunction" $lines
}
$gen = @('# Releases the CURRENT awaiting scenario (scripts/g5-gate.ps1 prints the exact command and the scenario id). Refused unless a scenario is awaiting.',
         'execute unless score #running g5 matches 1 run tellraw @s {"text":"[G5] gate refused: no run in progress.","color":"red"}')
foreach ($ph in $script:phases) { $gen += "execute if score #running g5 matches 1 if score #phase g5 matches $($ph.num) run function npcortex_g5:gate/$($ph.id)" }
Put 'gate.mcfunction' $gen
# Explicit safe checkpoint for a scenario that never reaches terminal evidence: cancels the actors (c2 cancel), then g5-gate.ps1 judges again.
$cc = @('# Only while a scenario is AWAITING the gate. Cancels the C2 goals of all G5 NPCs; g5-gate.ps1 then waits for CANCELLED/terminal + quiescence before it prints GO.',
        'execute unless score #settle g5 matches -2 run tellraw @s {"text":"[G5] cancel_current refused: no scenario is awaiting the gate.","color":"red"}',
        'execute unless score #settle g5 matches -2 run say [G5] NOTE cancel_current refused: not awaiting',
        'execute if score #settle g5 matches -2 run say [G5] NOTE cancel_current issued',
        'execute if score #settle g5 matches -2 run function npcortex_g5:lib/snap')
foreach ($nm in $names) { $cc += "execute if score #settle g5 matches -2 run $OP c2 cancel $nm" }
Put 'cancel_current.mcfunction' $cc
Put 'strict_on.mcfunction' @('scoreboard players set #strict g5 1','say [G5] NOTE strict gate ON: every scenario needs the owner gate, also after an in-game REACHED')
Put 'strict_off.mcfunction' @('scoreboard players set #strict g5 0','say [G5] NOTE strict gate OFF: adaptive REACHED / STOPPED / NO_MOVEMENT advance on in-game evidence')

# entry points -----------------------------------------------------------------------------
Put 'setup.mcfunction' @(
 '# Run once as the operator, in a disposable SUPERFLAT world (cheats on). Verifies the test area; changes only test-world rules.',
 '# Also purges a stale scheduled loop and every state of an earlier run (stale-run protection).',
 'schedule clear npcortex_g5:loop',
 'schedule clear npcortex_g5:lib/p10_watch',
 'scoreboard objectives add g5 dummy',
 'tag @s add g5_op',
 'scoreboard players set #ready g5 0',
 'execute if block 0 -61 0 minecraft:grass_block if block 0 -64 0 minecraft:bedrock if block 0 -60 0 minecraft:air run scoreboard players set #ready g5 1',
 'execute if score #ready g5 matches 0 run tellraw @s {"text":"[G5] ABORT: this is not the default superflat layout (grass y=-61, bedrock y=-64 at 0,0). Create a new SUPERFLAT world.","color":"red"}',
 'execute if score #ready g5 matches 1 run gamerule doMobSpawning false',
 'execute if score #ready g5 matches 1 run gamerule doDaylightCycle false',
 'execute if score #ready g5 matches 1 run gamerule doWeatherCycle false',
 'execute if score #ready g5 matches 1 run difficulty peaceful',
 'execute if score #ready g5 matches 1 run gamemode creative @s',
 'execute if score #ready g5 matches 1 run npcortex debug cplus m03 enable',
 'execute if score #ready g5 matches 1 run say [G5] SETUP ok',
 'execute if score #ready g5 matches 1 run tellraw @s {"text":"[G5] ready. Next: /function npcortex_g5:probe (5 s), then /function npcortex_g5:start. Status: npcortex_g5:status","color":"green"}',
 'scoreboard players set #running g5 0','scoreboard players set #single g5 0','scoreboard players set #last g5 99','scoreboard players set #phase g5 0',
 'scoreboard players set #adaptive g5 0','scoreboard players set #probed g5 0','scoreboard players set #sel g5 0','scoreboard players set #per g5 5',
 'scoreboard players set #blocked g5 0','scoreboard players set #cp g5 0','scoreboard players set #strict g5 0','scoreboard players set #aw g5 10',
 'scoreboard players set #gn g5 0','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0',
 'scoreboard players set #w g5 0','scoreboard players set #att g5 0','scoreboard players set #p10on g5 0')
function Starter($rel, $first, $last, $label, $key) {
    $note = if ($first -ne 1) { "execute if score #ready g5 matches 1 unless score #running g5 matches 1 unless score #probed g5 matches 1 run say [G5] NOTE mode=OWNER_GATED_UNPROBED (p00 not run in this setup: no in-game observation, every scenario needs the owner gate; run npcortex_g5:probe first for adaptive control)" } else { "# p00 decides the mode" }
    Put $rel @(
     "# start $label",
     'execute unless score #ready g5 matches 1 run tellraw @s {"text":"[G5] run npcortex_g5:setup first","color":"red"}',
     "execute if score #ready g5 matches 1 unless score #running g5 matches 1 run say [G5] START $label",
     $note,
     "execute if score #ready g5 matches 1 unless score #running g5 matches 1 run function npcortex_g5:lib/begin_$key")
    Put "lib/begin_$key.mcfunction" @(
     "scoreboard players set #phase g5 $first",'scoreboard players set #blocked g5 0','scoreboard players set #tick g5 0','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0',
     "scoreboard players set #last g5 $last",'scoreboard players set #running g5 1','schedule function npcortex_g5:loop 10t replace')
}
$byId = @{}; foreach ($ph in $script:phases) { $byId[$ph.id.Substring(0,3)] = $ph.num }
Starter 'start.mcfunction' 1 $script:phases.Count 'ALL' 'all'
Starter 'tests/navigation.mcfunction' $byId['p00'] $byId['p06'] 'navigation' 'navigation'
Starter 'tests/obstacles.mcfunction' $byId['p07'] $byId['p08'] 'obstacles' 'obstacles'
Starter 'tests/cancellation.mcfunction' $byId['p09'] $byId['p10'] 'cancellation' 'cancellation'
Starter 'tests/recovery.mcfunction' $byId['p11'] $byId['p11'] 'recovery (stops at the Save&Quit checkpoint)' 'recovery'
Starter 'tests/regression.mcfunction' $byId['p14'] $byId['p18'] 'regression' 'regression'
Starter 'tests/multi_npc.mcfunction' $byId['p19'] $byId['p22'] 'multi_npc' 'multi_npc'
foreach ($ph in $script:phases) {
    $num = $ph.num
    Put "lib/begin_$num.mcfunction" @("scoreboard players set #blocked g5 0","scoreboard players set #phase g5 $num",'scoreboard players set #tick g5 0','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0',"scoreboard players set #last g5 $num",'scoreboard players set #running g5 1','schedule function npcortex_g5:loop 10t replace')
    Put ("only/{0}.mcfunction" -f $ph.id) @(
     "execute unless score #ready g5 matches 1 run tellraw @s {`"text`":`"[G5] run npcortex_g5:setup first`",`"color`":`"red`"}",
     "execute if score #ready g5 matches 1 unless score #running g5 matches 1 run say [G5] START only:$($ph.id)",
     "execute if score #ready g5 matches 1 unless score #running g5 matches 1 run function npcortex_g5:lib/begin_$num",
     $(if ($num -ne 1) { "execute if score #ready g5 matches 1 unless score #probed g5 matches 1 run say [G5] NOTE mode=OWNER_GATED_UNPROBED (p00 not run in this setup: every scenario needs the owner gate)" } else { "# p00 decides the mode" }))
}
Put 'rejoin.mcfunction' @(
 '# Run after Save&Quit + re-opening the world (the M03 session does not survive a restart). Refused unless the p11 checkpoint set #cp (stale-run guard).',
 'tag @s add g5_op',
 'execute unless score #cp g5 matches 1 run tellraw @s {"text":"[G5] rejoin refused: no pending Save&Quit checkpoint (nothing to continue).","color":"red"}',
 'execute unless score #cp g5 matches 1 run say [G5] NOTE rejoin refused: no pending checkpoint',
 'execute if score #cp g5 matches 1 run function npcortex_g5:lib/rejoin_go')
Put 'lib/rejoin_go.mcfunction' @(
 'scoreboard players set #cp g5 0',
 'npcortex debug cplus m03 enable',
 'say [G5] REJOIN operator back; observing p12 (no replay) then p13 (new movement)',
 'scoreboard players set #phase g5 12','scoreboard players set #tick g5 0','scoreboard players set #settle g5 -1','scoreboard players set #result g5 0',
 'scoreboard players set #running g5 1','schedule function npcortex_g5:loop 10t replace')
Put 'probe.mcfunction' @('# capability probe only (p00): decides ADAPTIVE vs OWNER_GATED for later runs in this setup','function npcortex_g5:only/p00_capability_probe')
Put 'status.mcfunction' @(
 'tellraw @s [{"text":"[G5] running="},{"score":{"name":"#running","objective":"g5"}},{"text":" phase="},{"score":{"name":"#phase","objective":"g5"}},{"text":" tick="},{"score":{"name":"#tick","objective":"g5"}},{"text":" result="},{"score":{"name":"#result","objective":"g5"}},{"text":" settle="},{"score":{"name":"#settle","objective":"g5"}},{"text":" (-2 = awaiting owner gate) moved="},{"score":{"name":"#moved","objective":"g5"}},{"text":" still="},{"score":{"name":"#still","objective":"g5"}},{"text":" cancelled="},{"score":{"name":"#cancelled","objective":"g5"}},{"text":" adaptive="},{"score":{"name":"#adaptive","objective":"g5"}},{"text":" sel="},{"score":{"name":"#sel","objective":"g5"}},{"text":" probed="},{"score":{"name":"#probed","objective":"g5"}},{"text":" strict="},{"score":{"name":"#strict","objective":"g5"}}]',
 'tellraw @s {"text":"result codes: 1 reached, 2 timeout, 3 stalled(terminal inferred), 4 NPC not selectable, 5 target chunk not loaded, 6 never moved, 7 stopped after cancel, 8 cancel not triggered, 10 awaiting owner gate, 11 p10 attempt timed out and was cleaned up (one noted cleanup cancel). None of these is a PASS; the collector decides.","color":"gray"}')
Put 'stop.mcfunction' @(
 '# Stops the loop and removes ONLY the blocks this pack placed (stone walls inside the test area).',
 'schedule clear npcortex_g5:loop',
 'schedule clear npcortex_g5:lib/p10_watch',
 'scoreboard players set #w g5 0','scoreboard players set #p10on g5 0','scoreboard players set #att g5 0',
 'scoreboard players set #running g5 0',
 'fill 12 -60 -3 12 -58 7 minecraft:air','fill 12 -60 -80 12 -58 80 minecraft:air',
 'say [G5] STOP',
 'tellraw @s {"text":"[G5] stopped; test walls removed. NPCs are left in place (identities untouched).","color":"yellow"}')
Put 'menu.mcfunction' @(
 'tellraw @s {"text":"NPCortex G5 harness (TEST ONLY)","color":"aqua","bold":true}',
 'tellraw @s {"text":"setup > probe > start | tests/navigation | tests/obstacles | tests/cancellation | tests/recovery (checkpoint) > rejoin | tests/regression | tests/multi_npc | gate | cancel_current | strict_on | strict_off | status | stop","color":"gray"}')

# load tag so reload does not run anything (no tick/load functions: nothing starts by itself)
$zip = Join-Path $root 'integrations/datapacks/NPCortex_G5.zip'
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Push-Location $pack; try { & (Join-Path $env:SystemRoot 'System32\tar.exe') -a -cf $zip pack.mcmeta data; if ($LASTEXITCODE -ne 0) { throw 'tar failed' } } finally { Pop-Location }   # forward-slash entries (Compress-Archive on PS 5.1 writes backslashes)
"Generated $($script:phases.Count) phases -> $pack ; zip -> $zip"
