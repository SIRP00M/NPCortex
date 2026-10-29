tellraw @s {"text":"[T17] Save, quit, restart, recovery: inventory and health must survive Save&Quit.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t17 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"After 5 s run /function npcortex_parity:setup/t17_stage2 (second step; commands that need a player source cannot be scheduled).","color":"yellow"}
tellraw @s {"text":"After 4.5 s the NPC state is recorded. Then Save and Quit, reload, /function npcortex_parity:check/t17","color":"gray"}
