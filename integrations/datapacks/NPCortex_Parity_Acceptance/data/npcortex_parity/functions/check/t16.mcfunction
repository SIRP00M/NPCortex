execute in minecraft:the_nether store success score #nether pp_t if entity @e[tag=npcortex_Parity]
execute if score #nether pp_t matches 1 run function npcortex_parity:check/t16_pass
execute unless score #nether pp_t matches 1 run say PARITY|T16|PENDING the NPC is not in the Nether
