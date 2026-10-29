execute at @e[type=minecraft:marker,tag=pp_anchor,limit=1] store success score #lever pp_t if block ~6 ~ ~2 minecraft:lever[powered=true]
execute store result score #cob pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:cobblestone"}].Count
execute if score #lever pp_t matches 1 run function npcortex_parity:check/t08_pass
execute unless score #lever pp_t matches 1 run say PARITY|T08|PENDING lever not powered yet
