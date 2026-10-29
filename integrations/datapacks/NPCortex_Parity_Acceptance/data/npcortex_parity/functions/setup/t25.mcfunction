tellraw @s {"text":"[T25] Despawn, restart and chunk ticket cleanup: after despawn the NPC and its ring are gone.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t25 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"Walk Parity far away (T19), go far from it, then /function npcortex_parity:setup/t25_stage2 ; wait 20 s ; /function npcortex_parity:check/t25. (Expect a one-tick stall when the ring is released: X0 measured up to 333 ms.)","color":"gray"}
