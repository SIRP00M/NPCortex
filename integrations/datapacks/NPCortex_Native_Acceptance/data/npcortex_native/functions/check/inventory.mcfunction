# prints the oak-log count of the tagged NPC (tag it first with: /tag <name> add npcx_move)
scoreboard objectives add npx_tmp dummy
execute store result score #logs npx_tmp run data get entity @e[type=minecraft:player,tag=npcx_move,limit=1] Inventory[{id:"minecraft:oak_log"}].Count
tellraw @a [{"text":"oak logs in the first matching stack of the tagged NPC: ","color":"gold"},{"score":{"name":"#logs","objective":"npx_tmp"},"color":"white"}]
