# Shared helpers of the G5 test harness (TEST ONLY). Dot-sourced by collect-g5-evidence.ps1 and g5-gate.ps1:  . (Join-Path $PSScriptRoot 'g5-common.ps1')

# journal states written by production code into the M03 snapshot (c2_journal.state). Anything not listed as terminal counts as ACTIVE (safe default).
$G5_TERMINAL = @('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
$G5_ACTIVE_NAMED = @('DECIDED', 'MOVING', 'REPLANNING', 'CANCEL_PENDING')
function Test-G5Terminal($state) { return ($state -in $G5_TERMINAL) }
function Test-G5Active($state) { return ($state -and ($state -notin $G5_TERMINAL) -and ($state -ne 'NONE')) }

function Dist($s, $tx, $tz) { if ($null -eq $s) { return [double]::PositiveInfinity }; return [math]::Sqrt(([double]$s.x - $tx) * ([double]$s.x - $tx) + ([double]$s.z - $tz) * ([double]$s.z - $tz)) }
function Spread($ss) {
    $ss = @($ss); if ($ss.Count -lt 2) { return [double]::PositiveInfinity }
    $mx = $ss | ForEach-Object { [double]$_.x } | Measure-Object -Maximum -Minimum
    $mz = $ss | ForEach-Object { [double]$_.z } | Measure-Object -Maximum -Minimum
    return [math]::Max($mx.Maximum - $mx.Minimum, $mz.Maximum - $mz.Minimum)
}
function States($ss, $goal) { $out = @(); foreach ($s in @($ss)) { if ($s.goal -eq $goal -and $s.state -and ($out.Count -eq 0 -or $out[$out.Count - 1] -ne $s.state)) { $out += $s.state } }; return $out }
function F1($v) { if ([double]::IsInfinity([double]$v)) { return 'n/a' }; return ('{0:N2}' -f [double]$v) }

# scenario table: id, class, type, target x, target z, tolerance
$S = @(
 @('p00_capability_probe','AUTO','probe',2,2,1.0), @('p02_navlong_20','AUTO','nav',22.5,2.5,1.6), @('p03_navlong_40','AUTO','nav',-37.5,2.5,1.6), @('p04_navlong_80','AUTO','nav',82.5,2.5,1.6),
 @('p05_navlong_150','AUTO','nav',152.5,2.5,1.6), @('p06_navlong_diag60','AUTO','nav',62.5,62.5,1.6), @('p07_small_wall_detour','AUTO','nav',32.5,2.5,1.6),
 @('p08_large_wall_no_progress','AUTO','noreach',62.5,2.5,1.6), @('p09_cancel_leg_active','AUTO','cancel9',82.5,2.5,1.6), @('p10_cancel_quiescing','SEMI-AUTO','cancel10',152.5,2.5,1.6),
 @('p11_savequit_checkpoint','SEMI-AUTO','checkpoint',152.5,2.5,1.6), @('p12_rejoin_no_replay','SEMI-AUTO','rejoin',0,0,0), @('p13_new_navlong_after_recovery','SEMI-AUTO','nav',22.5,2.5,1.6),
 @('p14_regress_c2_navigate','AUTO','nav',6.5,2.5,1.6), @('p15_regress_c2_move','AUTO','nav',5.5,2.5,1.6), @('p16_regress_follow_start','AUTO','follow1',30.5,2.5,6.0), @('p17_regress_follow_stop','AUTO','follow2',30.5,2.5,0),
 @('p18_regress_e3_taskmove','SEMI-AUTO','e3',0,0,0), @('p19_multi_1_npc','AUTO','multi',0,0,1.6), @('p20_multi_3_npc','AUTO','multi',0,0,1.6), @('p21_multi_5_npc','AUTO','multi',0,0,1.6), @('p22_multi_10_npc','AUTO','multi',0,0,1.6))
# Fresh lane identities (revision 5): 'g5a' lanes never collide with identities of earlier G5 runs (g5n..). Lane 1 stays 'yui' for the single-NPC scenarios;
# in a fresh world it is simply another new NPC with a default Character Profile (NOT the Yui Character Pack).
$laneNames = @('yui') + (2..10 | ForEach-Object { 'g5a{0:d2}' -f $_ })

# run isolation proof, shared by g5-preflight.ps1 and collect-g5-evidence.ps1
. (Join-Path $PSScriptRoot 'g5-isolation.ps1')
