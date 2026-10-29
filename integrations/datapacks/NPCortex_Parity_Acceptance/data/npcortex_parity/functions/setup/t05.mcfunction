tellraw @s {"text":"[T05] Item pickup / pickup delay. Vanilla touch pickup is ON (Owner decision D1). Human oracle: stand next to a dropped item - it is picked up after its 0.5 s delay.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t05 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
execute at @e[tag=npcortex_Parity,limit=1] run summon minecraft:item ~ ~ ~ {Item:{id:"minecraft:oak_log",Count:3b},PickupDelay:10,Tags:["pp_item"],Motion:[0.0,0.0,0.0]}
tellraw @s {"text":"A log item lies at the NPC feet with a 10-tick delay. Wait 5 s, then /function npcortex_parity:check/t05. Expected: the item is gone and the NPC holds oak logs (vanilla touch pickup).","color":"gray"}
