execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute store result score #items npx_tmp if entity @e[type=minecraft:item,distance=..40,nbt={Item:{id:"minecraft:oak_log"}}]
tellraw @a [{"text":"loose oak-log stacks left in the arena: ","color":"gold"},{"score":{"name":"#items","objective":"npx_tmp"},"color":"white"}]
