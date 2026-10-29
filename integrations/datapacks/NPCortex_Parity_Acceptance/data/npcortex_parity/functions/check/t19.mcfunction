execute store result score #ahead pp_t if entity @e[tag=npcortex_Parity]
execute at @e[tag=npcortex_Parity,limit=1] store success score #l1 pp_t if loaded ~80 ~ ~
execute at @e[tag=npcortex_Parity,limit=1] store success score #l2 pp_t if loaded ~-80 ~ ~
execute at @e[tag=npcortex_Parity,limit=1] store success score #l3 pp_t if loaded ~ ~ ~80
execute at @e[tag=npcortex_Parity,limit=1] store success score #l4 pp_t if loaded ~ ~ ~-80
execute if score #ahead pp_t matches 0 run say PARITY|T19|NOT TESTED NPC not found
execute if score #ahead pp_t matches 1 if score #l1 pp_t matches 1 if score #l2 pp_t matches 1 if score #l3 pp_t matches 1 if score #l4 pp_t matches 1 run function npcortex_parity:check/t19_pass
execute if score #ahead pp_t matches 1 unless score #l1 pp_t matches 1 run function npcortex_parity:check/t19_fail
execute if score #ahead pp_t matches 1 unless score #l2 pp_t matches 1 run function npcortex_parity:check/t19_fail
execute if score #ahead pp_t matches 1 unless score #l3 pp_t matches 1 run function npcortex_parity:check/t19_fail
execute if score #ahead pp_t matches 1 unless score #l4 pp_t matches 1 run function npcortex_parity:check/t19_fail
