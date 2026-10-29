execute unless data storage npcortex_parity:t17 inv run say PARITY|T17|PENDING nothing recorded yet
data modify storage npcortex_parity:cmp inv set from entity @e[tag=npcortex_Parity,limit=1] Inventory
execute if data storage npcortex_parity:t17 inv store success score #diff pp_t run data modify storage npcortex_parity:cmp inv set from storage npcortex_parity:t17 inv
execute if data storage npcortex_parity:t17 inv if score #diff pp_t matches 0 run function npcortex_parity:check/t17_pass
execute if data storage npcortex_parity:t17 inv if score #diff pp_t matches 1 run function npcortex_parity:check/t17_fail
