execute at @e[type=minecraft:marker,tag=pp_anchor,limit=1] store success score #glass pp_t if data block ~20 ~ ~0 Items[{id:"minecraft:glass"}]
execute if score #glass pp_t matches 1 run function npcortex_parity:check/t22_pass
execute unless score #glass pp_t matches 1 run say PARITY|T22|PENDING no glass yet (needs ~10 s of ticking; if far chunks are not ticking this stays PENDING -> FAIL after 30 s)
