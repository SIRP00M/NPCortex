execute store success score #here pp_t if entity @e[tag=npcortex_Parity,limit=1]
execute store success score #pos pp_t if data storage npcortex_parity:t25 pos
execute if score #pos pp_t matches 0 run say PARITY|T25|PENDING run setup/t25_stage2 first
execute if score #pos pp_t matches 1 if score #here pp_t matches 1 run function npcortex_parity:check/t25_fail
execute if score #pos pp_t matches 1 if score #here pp_t matches 0 run function npcortex_parity:check/t25_pass
