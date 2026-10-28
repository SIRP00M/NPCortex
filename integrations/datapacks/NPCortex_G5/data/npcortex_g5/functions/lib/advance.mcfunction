scoreboard players add #phase g5 1
scoreboard players set #tick g5 -1
scoreboard players set #settle g5 -1
scoreboard players set #result g5 0
execute if score #phase g5 > #last g5 run function npcortex_g5:lib/finished
