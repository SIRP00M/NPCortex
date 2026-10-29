execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute if score #absent pp_t matches 1 run say PARITY|T13|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t13 pp 3
execute if score #absent pp_t matches 0 store success score #slp pp_t if data entity @e[tag=npcortex_Parity,limit=1] SleepingX
execute if score #absent pp_t matches 0 if score #slp pp_t matches 1 run function npcortex_parity:check/t13_pass
execute if score #absent pp_t matches 0 unless score #slp pp_t matches 1 run function npcortex_parity:check/t13_fail
