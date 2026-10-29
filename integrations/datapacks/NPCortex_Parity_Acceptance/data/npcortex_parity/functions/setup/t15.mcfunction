tellraw @s {"text":"[T15] Statistics: mining counters (scoreboard criteria minecraft.mined:minecraft.stone).","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t15 pp 4
tellraw @s {"text":"Run T14 first (NPC mines stone). Then /function npcortex_parity:check/t15","color":"gray"}
function npcortex_parity:lib/init
