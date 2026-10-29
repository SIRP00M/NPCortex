# The NPC may legitimately be dead by now (lava/fall): npcortex_parity:lib/sample recorded the lowest Health seen while it lived.
execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute if score #absent pp_t matches 0 store result score #hp pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Health 1
execute if score #absent pp_t matches 0 if score #hp pp_t < #minhp pp_t run scoreboard players operation #minhp pp_t = #hp pp_t
execute if score #absent pp_t matches 1 if score #minhp pp_t matches 20.. run say PARITY|T06|NOT TESTED NPC Parity not found (dead, despawned or not spawned)
execute if score #absent pp_t matches 1 if score #minhp pp_t matches 20.. run scoreboard players set #t06 pp 3
execute if score #minhp pp_t matches ..19 run function npcortex_parity:check/t06_pass
execute if score #absent pp_t matches 0 if score #minhp pp_t matches 20.. run function npcortex_parity:check/t06_fail
