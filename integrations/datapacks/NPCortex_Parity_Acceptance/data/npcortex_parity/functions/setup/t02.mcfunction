tellraw @s {"text":"[T02] Poison effect ticking. Human oracle: /effect give @s poison 20 0 -> health drops by 1 each 25 ticks, stops at 1 HP.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t02 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"After 5 s run /function npcortex_parity:setup/t02_stage2 (second step; commands that need a player source cannot be scheduled).","color":"yellow"}
tellraw @s {"text":"Effect is applied after spawn protection (4.5 s). Wait 15 s then /function npcortex_parity:check/t02","color":"gray"}
