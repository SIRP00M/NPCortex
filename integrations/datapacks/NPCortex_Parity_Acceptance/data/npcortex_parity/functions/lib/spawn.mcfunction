npcortex c1 spawn Parity
scoreboard players set #spawn_gt pp_t 0
execute store result score #spawn_gt pp_t run time query gametime
# Test PREPARATION only: the identity persists between scenarios (health, food), so start each one from full health and food.
effect give @e[tag=npcortex_Parity,limit=1] minecraft:instant_health 1 10 true
effect give @e[tag=npcortex_Parity,limit=1] minecraft:saturation 1 10 true
scoreboard players set #minhp pp_t 20
schedule clear npcortex_parity:lib/sample
function npcortex_parity:lib/sample
