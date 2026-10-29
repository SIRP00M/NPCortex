scoreboard players set #cnt pp_t 0
execute if entity @e[tag=npcortex_Parity1] run scoreboard players add #cnt pp_t 1
execute if entity @e[tag=npcortex_Parity2] run scoreboard players add #cnt pp_t 1
execute if entity @e[tag=npcortex_Parity3] run scoreboard players add #cnt pp_t 1
execute if entity @e[tag=npcortex_Parity4] run scoreboard players add #cnt pp_t 1
execute if entity @e[tag=npcortex_Parity5] run scoreboard players add #cnt pp_t 1
execute if score #cnt pp_t matches 5 run say PARITY|T24_5|PASS presence 5/5 (performance is NOT judged: record /forge tps, no threshold)
execute unless score #cnt pp_t matches 5 run say PARITY|T24_5|FAIL fewer than 5 NPCs present
execute if score #cnt pp_t matches 5 run scoreboard players set #t24 pp 1
execute unless score #cnt pp_t matches 5 run scoreboard players set #t24 pp 2
