# Night attracts monsters to the NPC (it is a player for natural spawning): vanilla refuses sleep with monsters nearby. A killed monster stays in the world for its death animation and still blocks, so move them out of the way (test PREPARATION only, throw-away world).
execute as @e[type=!minecraft:player,type=!minecraft:item,type=!minecraft:marker,type=!minecraft:armor_stand,distance=..48] at @s run tp @s ~ -400 ~
# Monsters and the night's wandering may have pushed the NPC away; stand it by the bed again (vanilla needs it within reach).
execute at @s run tp @e[tag=npcortex_Parity,limit=1] ~1.5 ~ ~1.5
npcortex c1 act Parity sleep ~2 ~ ~
