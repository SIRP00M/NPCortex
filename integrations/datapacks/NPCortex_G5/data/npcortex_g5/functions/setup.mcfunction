# Run once as the operator, in a disposable SUPERFLAT world (cheats on). Verifies the test area; changes only test-world rules.
# Also purges a stale scheduled loop and every state of an earlier run (stale-run protection).
schedule clear npcortex_g5:loop
schedule clear npcortex_g5:lib/p10_watch
scoreboard objectives add g5 dummy
tag @s add g5_op
scoreboard players set #ready g5 0
execute if block 0 -61 0 minecraft:grass_block if block 0 -64 0 minecraft:bedrock if block 0 -60 0 minecraft:air run scoreboard players set #ready g5 1
execute if score #ready g5 matches 0 run tellraw @s {"text":"[G5] ABORT: this is not the default superflat layout (grass y=-61, bedrock y=-64 at 0,0). Create a new SUPERFLAT world.","color":"red"}
execute if score #ready g5 matches 1 run gamerule doMobSpawning false
execute if score #ready g5 matches 1 run gamerule doDaylightCycle false
execute if score #ready g5 matches 1 run gamerule doWeatherCycle false
execute if score #ready g5 matches 1 run difficulty peaceful
execute if score #ready g5 matches 1 run gamemode creative @s
execute if score #ready g5 matches 1 run npcortex debug cplus m03 enable
execute if score #ready g5 matches 1 run say [G5] SETUP ok
execute if score #ready g5 matches 1 run tellraw @s {"text":"[G5] ready. Next: /function npcortex_g5:probe (5 s), then /function npcortex_g5:start. Status: npcortex_g5:status","color":"green"}
scoreboard players set #running g5 0
scoreboard players set #single g5 0
scoreboard players set #last g5 99
scoreboard players set #phase g5 0
scoreboard players set #adaptive g5 0
scoreboard players set #probed g5 0
scoreboard players set #sel g5 0
scoreboard players set #per g5 5
scoreboard players set #blocked g5 0
scoreboard players set #cp g5 0
scoreboard players set #strict g5 0
scoreboard players set #aw g5 10
scoreboard players set #gn g5 0
scoreboard players set #settle g5 -1
scoreboard players set #result g5 0
scoreboard players set #w g5 0
scoreboard players set #att g5 0
scoreboard players set #p10on g5 0
