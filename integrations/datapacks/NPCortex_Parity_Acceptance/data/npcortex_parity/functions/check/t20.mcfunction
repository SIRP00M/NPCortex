execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute if score #absent pp_t matches 1 run say PARITY|T20|NOT TESTED NPC Parity not found
execute if score #absent pp_t matches 1 run scoreboard players set #t20 pp 3
execute if score #absent pp_t matches 0 at @e[tag=npcortex_Parity,limit=1] store success score #here pp_t if loaded ~ ~ ~
execute if score #absent pp_t matches 0 store result score #ong pp_t run data get entity @e[tag=npcortex_Parity,limit=1] OnGround
execute if score #absent pp_t matches 0 if score #here pp_t matches 1 if score #ong pp_t matches 1 run function npcortex_parity:check/t20_pass
execute if score #absent pp_t matches 0 unless score #here pp_t matches 1 run function npcortex_parity:check/t20_fail
execute if score #absent pp_t matches 0 if score #here pp_t matches 1 unless score #ong pp_t matches 1 run say PARITY|T20|PENDING NPC not on ground (still moving or falling?)
