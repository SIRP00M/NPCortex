execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute store result score #air pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Air
execute if score #absent pp_t matches 1 run say PARITY|T03|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t03 pp 3
execute if score #absent pp_t matches 0 if score #air pp_t matches ..299 run function npcortex_parity:check/t03_pass
execute if score #absent pp_t matches 0 unless score #air pp_t matches ..299 run function npcortex_parity:check/t03_fail
