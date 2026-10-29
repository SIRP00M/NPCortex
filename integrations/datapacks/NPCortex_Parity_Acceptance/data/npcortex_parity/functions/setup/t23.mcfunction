tellraw @s {"text":"[T23] Idle presence: an NPC with an empty task queue must not despawn/disappear.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t23 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"Leave the NPC idle 10 minutes (12000 ticks), then /function npcortex_parity:check/t23","color":"gray"}
