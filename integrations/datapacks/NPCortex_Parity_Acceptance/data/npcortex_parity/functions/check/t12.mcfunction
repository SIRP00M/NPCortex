execute store result score #n pp_t if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:oak_log"}}]
execute if score #n pp_t matches 1.. run function npcortex_parity:check/t12_pass
execute if score #n pp_t matches 0 run function npcortex_parity:check/t12_fail
