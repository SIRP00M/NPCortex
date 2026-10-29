execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
scoreboard players set #stick pp_t -1
execute if score #absent pp_t matches 0 store result score #stick pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:stick"}].Count
execute if score #absent pp_t matches 1 run say PARITY|T11|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t11 pp 3
execute if score #absent pp_t matches 0 if score #stick pp_t matches 5 run function npcortex_parity:check/t11_pass
execute if score #absent pp_t matches 0 unless score #stick pp_t matches 5 run function npcortex_parity:check/t11_fail
