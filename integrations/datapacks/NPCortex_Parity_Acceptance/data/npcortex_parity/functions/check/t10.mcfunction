execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
scoreboard players set #bread pp_t -1
execute if score #absent pp_t matches 0 store result score #bread pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:golden_apple"}].Count
execute if score #absent pp_t matches 1 run say PARITY|T10|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t10 pp 3
execute if score #absent pp_t matches 0 if score #bread pp_t matches 1..2 run function npcortex_parity:check/t10_pass
execute if score #absent pp_t matches 0 unless score #bread pp_t matches 1..2 run function npcortex_parity:check/t10_fail
