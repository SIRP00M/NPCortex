tellraw @s {"text":"[T06] Fall damage. Human oracle: fall 6 blocks -> 3 damage (distance - 3).","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t06 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
fill ~0 ~5 ~0 ~3 ~5 ~3 minecraft:stone
tp @s ~0.5 ~6 ~0.5
function npcortex_parity:lib/spawn
tellraw @s {"text":"After 5 s run /function npcortex_parity:setup/t06_stage2 (second step; commands that need a player source cannot be scheduled).","color":"yellow"}
tellraw @s {"text":"NPC stands on a 6-high platform; after 5 s the platform under it is removed (the trap-door precondition). Check 5 s later.","color":"gray"}
