execute store success score #adv pp_t if entity @e[tag=npcortex_Parity,advancements={minecraft:story/mine_stone=true}]
execute if score #adv pp_t matches 1 run function npcortex_parity:check/t14_pass
execute unless score #adv pp_t matches 1 run say PARITY|T14|PENDING advancement not granted (yet)
