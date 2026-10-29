# Poison (1 damage / 25 ticks) can be out-healed by natural regeneration, so judge by the LOWEST Health sampled by lib/sample, not the current one.
execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute if score #absent pp_t matches 0 store result score #hp pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Health 1
execute if score #absent pp_t matches 0 if score #hp pp_t < #minhp pp_t run scoreboard players operation #minhp pp_t = #hp pp_t
execute if score #absent pp_t matches 1 if score #minhp pp_t matches 20.. run say PARITY|T02|NOT TESTED NPC Parity not found (dead, despawned or not spawned)
execute if score #absent pp_t matches 1 if score #minhp pp_t matches 20.. run scoreboard players set #t02 pp 3
execute if score #minhp pp_t matches ..19 run function npcortex_parity:check/t02_pass
execute if score #absent pp_t matches 0 if score #minhp pp_t matches 20.. run function npcortex_parity:check/t02_fail
