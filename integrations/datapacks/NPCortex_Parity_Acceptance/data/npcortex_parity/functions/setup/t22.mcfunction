tellraw @s {"text":"[T22] Block simulation in the NPC ring: a furnace 20 blocks from an idle NPC (operator far away) must smelt.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t22 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
setblock ~20 ~ ~0 minecraft:furnace[lit=false]{Items:[{Slot:0b,id:"minecraft:sand",Count:1b},{Slot:1b,id:"minecraft:coal",Count:1b}]}
tellraw @s {"text":"Go >150 blocks away for 30 s (so only the NPC loads the furnace), then /function npcortex_parity:check/t22","color":"gray"}
