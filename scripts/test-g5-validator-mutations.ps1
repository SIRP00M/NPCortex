# TEST-ONLY: proves the static validator rejects broken packs (each mutation of a copy of the generated pack must FAIL validation).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$src = Join-Path $root 'integrations/datapacks/NPCortex_G5'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("g5-mut-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$fnRel = 'data/npcortex_g5/functions'
$muts = [ordered]@{
    'blind dispatch (navlong without clean #result)' = { param($d) $f = "$d/$fnRel/ph/p02_navlong_20.mcfunction"; (Get-Content -Raw $f) -replace 'if score #result g5 matches 0 run execute as @a\[tag=g5_op,limit=1\] run npcortex c2 navlong', 'run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong' | Set-Content -NoNewline $f }
    'selector outside adaptive mode'                 = { param($d) $f = "$d/$fnRel/ph/p03_navlong_40.mcfunction"; (Get-Content -Raw $f) -replace 'if score #adaptive g5 matches 1 run function npcortex_g5:lib/obs_yui', 'run function npcortex_g5:lib/obs_yui' | Set-Content -NoNewline $f }
    'name= selector'                                 = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'execute if entity @e[type=player,name=yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run say [G5] NOTE x' }
    'forceload'                                      = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'forceload add 0 0' }
    'fill outside the test area'                     = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'fill 500 -60 0 501 -58 1 minecraft:stone' }
    'missing p00 probe'                              = { param($d) Remove-Item "$d/$fnRel/ph/p00_capability_probe.mcfunction" }
    'macro line'                                     = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" '$say [G5] $(x)' }
    'unbounded @e selector (the revision-3 bug class)' = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'execute if entity @e[type=player,tag=g5_npc_yui] run say [G5] NOTE x' }
    'timer-only progression (AWAIT replaced by a countdown)' = { param($d) $f = "$d/$fnRel/ph/p03_navlong_40.mcfunction"; (Get-Content -Raw $f) -replace 'if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2', 'if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 3' | Set-Content -NoNewline $f }
    'owner gate without the awaiting check'          = { param($d) $f = "$d/$fnRel/gate/p05_navlong_150.mcfunction"; (Get-Content -Raw $f) -replace ' if score #settle g5 matches -2 run scoreboard players set #settle g5 1', ' run scoreboard players set #settle g5 1' | Set-Content -NoNewline $f }
    'owner gate function missing'                    = { param($d) Remove-Item "$d/$fnRel/gate/p07_small_wall_detour.mcfunction" }
    'tag command that could hit the operator'        = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'tag @a add g5_npc' }
    'selector text inside say'                       = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'say [G5] NOTE sees @e[type=player]' }
    'PlayerList selector used for observation'       = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'execute if entity @a run say [G5] NOTE x' }
    'distance together with the search box'          = { param($d) Add-Content "$d/$fnRel/lib/snap.mcfunction" 'execute if entity @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300,distance=..2] run say [G5] NOTE x' }
}
$fails = New-Object System.Collections.ArrayList; $n = 0
# control: the unmodified pack must validate, otherwise "mutation rejected" proves nothing
& (Join-Path $PSScriptRoot 'validate-g5-datapack.ps1') -Pack $src -Quiet *> $null
if ($LASTEXITCODE -ne 0) { [void]$fails.Add('control: the unmodified pack does not validate') }
foreach ($k in $muts.Keys) {
    $d = Join-Path $tmp ([string]++$n)
    Copy-Item -Recurse -LiteralPath $src -Destination $d
    & $muts[$k] $d
    & (Join-Path $PSScriptRoot 'validate-g5-datapack.ps1') -Pack $d -Quiet *> $null
    if ($LASTEXITCODE -eq 0) { [void]$fails.Add("mutation not detected: $k") }
}
Remove-Item -LiteralPath $tmp -Recurse -Force
[pscustomobject]@{ mutations = $muts.Count; undetected = $fails.Count; status = $(if ($fails.Count) { 'FAIL' } else { 'PASS' }) } | ConvertTo-Json
$fails | ForEach-Object { "FAIL: $_" }
if ($fails.Count) { exit 1 }
