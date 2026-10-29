tellraw @s {"text":"[T21] Natural mob spawning eligibility around an idle NPC. OBSERVATION.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t21 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
gamerule doMobSpawning true
difficulty normal
time set night
tellraw @s {"text":"Leave the NPC idle in the open, then go >150 blocks away for 5 minutes (only the NPC keeps the area). Then /function npcortex_parity:check/t21","color":"gray"}
