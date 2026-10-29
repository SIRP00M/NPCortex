tellraw @s {"text":"[T09] Combat from the world: a hostile zombie must target and damage the NPC like a player. NPC-initiated attack has NO action contract (NOT TESTED; GameTest t09 covers vanilla Player.attack).","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t09 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
difficulty normal
tellraw @s {"text":"After 5 s run /function npcortex_parity:setup/t09_stage2 (second step; commands that need a player source cannot be scheduled).","color":"yellow"}
tellraw @s {"text":"A zombie is spawned 6 blocks away after 4.5 s. Check after 15 s: the NPC must have lost health (stand back).","color":"gray"}
