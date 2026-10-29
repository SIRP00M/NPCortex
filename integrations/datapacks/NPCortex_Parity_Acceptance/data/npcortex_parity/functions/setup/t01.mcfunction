tellraw @s {"text":"[T01] Fire and lava damage. Human oracle: stand in the lava pit -> burning + lava damage.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t01 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/pit
fill ~1 ~-3 ~1 ~2 ~-1 ~2 minecraft:lava
function npcortex_parity:lib/spawn
tellraw @s {"text":"NPC spawns into the lava pit. Wait 10 s (3 s spawn protection), then /function npcortex_parity:check/t01. Compare: jump in yourself (creative off) - same ticks of damage/fire.","color":"gray"}
