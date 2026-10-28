# S8: how many of the ten logs are gone
scoreboard objectives add npx_tmp dummy
scoreboard players set #gone npx_tmp 0
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~2 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~4 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~6 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~8 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~10 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~12 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~14 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~16 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~18 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~20 ~ ~12 minecraft:air run scoreboard players add #gone npx_tmp 1
tellraw @a [{"text":"S8: logs gone: ","color":"gold"},{"score":{"name":"#gone","objective":"npx_tmp"},"color":"white"},{"text":" / 10 (expected 10)","color":"gray"}]
