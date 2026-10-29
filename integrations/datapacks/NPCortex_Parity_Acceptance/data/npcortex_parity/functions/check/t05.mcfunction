execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute store result score #n pp_t if entity @e[type=minecraft:item,tag=pp_item]
execute if score #absent pp_t matches 0 store success score #inv pp_t if data entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:oak_log"}]
execute if score #absent pp_t matches 1 run say PARITY|T05|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t05 pp 3
execute if score #absent pp_t matches 0 if score #n pp_t matches 0 if score #inv pp_t matches 1 run function npcortex_parity:check/t05_pass
execute if score #absent pp_t matches 0 unless score #n pp_t matches 0 run function npcortex_parity:check/t05_fail
execute if score #absent pp_t matches 0 if score #n pp_t matches 0 unless score #inv pp_t matches 1 run function npcortex_parity:check/t05_fail
