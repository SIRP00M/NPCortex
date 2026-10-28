# Stops the loop and removes ONLY the blocks this pack placed (stone walls inside the test area).
schedule clear npcortex_g5:loop
schedule clear npcortex_g5:lib/p10_watch
scoreboard players set #w g5 0
scoreboard players set #p10on g5 0
scoreboard players set #att g5 0
scoreboard players set #running g5 0
fill 12 -60 -3 12 -58 7 minecraft:air
fill 12 -60 -80 12 -58 80 minecraft:air
say [G5] STOP
tellraw @s {"text":"[G5] stopped; test walls removed. NPCs are left in place (identities untouched).","color":"yellow"}
