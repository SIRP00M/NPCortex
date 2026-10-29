tellraw @s {"text":"[T03] Drowning / air supply. Human oracle: submerged head -> Air counts down from 300, then 2 damage per second.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t03 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/pit
fill ~1 ~-3 ~1 ~2 ~1 ~2 minecraft:water
function npcortex_parity:lib/spawn
tellraw @s {"text":"NPC spawns underwater. Check after 20 s: Air must have dropped (and damage after ~15 s + 3 s protection).","color":"gray"}
