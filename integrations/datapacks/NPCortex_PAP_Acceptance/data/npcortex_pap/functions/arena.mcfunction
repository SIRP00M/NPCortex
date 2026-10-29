kill @e[type=minecraft:marker,tag=npcx_pap_anchor]
summon minecraft:marker ~ ~ ~ {Tags:["npcx_pap_anchor"]}
fill ~-2 ~-1 ~-2 ~12 ~-1 ~12 minecraft:stone
fill ~-2 ~ ~-2 ~12 ~4 ~12 minecraft:air
setblock ~6 ~ ~2 minecraft:lever[face=floor,facing=north,powered=false]
setblock ~8 ~ ~2 minecraft:stone_button[face=floor,facing=north,powered=false]
tellraw @s [{"text":"[NPCortex PAP] arena built. Anchor (SW corner, feet level): ","color":"gold"},{"nbt":"Pos","entity":"@e[type=minecraft:marker,tag=npcx_pap_anchor,limit=1]","color":"white"}]
tellraw @s {"text":"Lever at anchor+(6,0,2), button at +(8,0,2). Stand the NPC at +(4,0,4); look at the stone floor next to it and say: <npc>, place cobblestone","color":"gray"}
