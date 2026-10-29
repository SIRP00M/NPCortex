execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute store result score #hp pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Health 1
execute if score #absent pp_t matches 1 run say PARITY|T04|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t04 pp 3
execute if score #absent pp_t matches 0 if score #hp pp_t matches 13.. run function npcortex_parity:check/t04_pass
execute if score #absent pp_t matches 0 unless score #hp pp_t matches 13.. run function npcortex_parity:check/t04_fail
