execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,tag=npcx_move,limit=1] ~18.5 ~ ~4.5
tag @e[type=minecraft:player,tag=npcx_move] remove npcx_move
