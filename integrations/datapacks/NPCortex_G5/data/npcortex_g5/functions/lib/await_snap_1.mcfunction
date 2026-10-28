scoreboard players operation #m g5 = #tick g5
scoreboard players operation #m g5 %= #aw g5
execute if score #m g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot yui
