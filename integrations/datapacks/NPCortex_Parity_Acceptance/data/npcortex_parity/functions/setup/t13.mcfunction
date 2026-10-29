tellraw @s {"text":"[T13] Sleeping through vanilla bed use. Human oracle: right-click a bed at night - you lie down.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t13 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
setblock ~2 ~ ~ minecraft:red_bed[facing=east,part=foot]
setblock ~3 ~ ~ minecraft:red_bed[facing=east,part=head]
time set 14000
tellraw @s {"text":"A bed stands 2 blocks away and it is night. Run /function npcortex_parity:setup/t13_stage2 (the NPC right-clicks the bed), then /function npcortex_parity:check/t13.","color":"gray"}
