function npcortex_parity:lib/init
scoreboard players set #t24 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
npcortex c1 spawn Parity1
npcortex c1 spawn Parity2
npcortex c1 spawn Parity3
npcortex c1 spawn Parity4
npcortex c1 spawn Parity5
npcortex c1 spawn Parity6
npcortex c1 spawn Parity7
npcortex c1 spawn Parity8
npcortex c1 spawn Parity9
npcortex c1 spawn Parity10
tellraw @s {"text":"[T24] 10 NPCs spawned (they stand at the same spot: OVERLAPPING rings). For non-overlapping rings walk them apart with c2. Run /forge tps now and after 60 s; then /function npcortex_parity:check/t24_10","color":"gold"}
