tellraw @s {"text":"[T16] Dimension change (controlled transfer only; portals are refused).","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t16 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"Go to the Nether yourself, then: /npcortex c1 dimension Parity minecraft:the_nether, then /function npcortex_parity:check/t16 (portal travel itself must stay refused - see GameTest t16).","color":"gray"}
