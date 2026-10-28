# p10 cancel (generated): the journal snapshot of the cancel tick, then the ONE cancel of this attempt. D1-B logs longState at the cancel() invocation.
scoreboard players set #cancelled g5 1
scoreboard players set #w g5 0
scoreboard players set #still g5 0
execute if score #att g5 matches 1 run say [G5] NOTE cancel attempt 1
execute if score #att g5 matches 2 run say [G5] NOTE cancel attempt 2
execute if score #att g5 matches 3 run say [G5] NOTE cancel attempt 3
execute if score #att g5 matches 4 run say [G5] NOTE cancel attempt 4
execute if score #att g5 matches 5 run say [G5] NOTE cancel attempt 5
execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot yui
execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel yui
