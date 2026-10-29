execute store result score #gt pp_t run time query gametime
scoreboard players operation #gt pp_t -= #spawn_gt pp_t
execute store success score #here pp_t if entity @e[tag=npcortex_Parity]
execute if score #here pp_t matches 0 run function npcortex_parity:check/t23_fail
execute if score #here pp_t matches 1 if score #gt pp_t matches 12000.. run function npcortex_parity:check/t23_pass
execute if score #here pp_t matches 1 if score #gt pp_t matches ..11999 run say PARITY|T23|PENDING NPC present but fewer than 12000 ticks elapsed
