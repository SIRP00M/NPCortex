tellraw @s {"text":"[T04] Hunger/regeneration. Human oracle: full food + damage -> health regenerates (fast when saturated).","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t04 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"After 5 s run /function npcortex_parity:setup/t04_stage2 (second step; commands that need a player source cannot be scheduled).","color":"yellow"}
tellraw @s {"text":"After 4.5 s the NPC is damaged by 8 (c1 damage). Check after 20 s: health must rise above the post-damage value.","color":"gray"}
