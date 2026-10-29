tellraw @s {"text":"[T10] Food / item use by the NPC through vanilla ServerPlayerGameMode.useItem. Human oracle: eat a golden apple - the stack shrinks by one after ~1.6 s and you gain Absorption + Regeneration.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t10 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
clear @a[tag=npcortex_Parity]
give @a[tag=npcortex_Parity] minecraft:golden_apple 3
tellraw @s {"text":"The NPC holds 3 golden apples (always edible). After ~6 s run /function npcortex_parity:setup/t10_stage2 (the NPC eats one), wait 3 s, then /function npcortex_parity:check/t10.","color":"gray"}
