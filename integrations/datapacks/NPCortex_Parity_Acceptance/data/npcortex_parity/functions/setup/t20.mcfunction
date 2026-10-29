tellraw @s {"text":"[T20] Fresh chunk generation and readiness: after T19 in unexplored terrain the NPC stands on solid ground in a loaded chunk.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t20 pp 4
tellraw @s {"text":"Run in unexplored terrain (new world area), after T19 walk. Then /function npcortex_parity:check/t20","color":"gray"}
