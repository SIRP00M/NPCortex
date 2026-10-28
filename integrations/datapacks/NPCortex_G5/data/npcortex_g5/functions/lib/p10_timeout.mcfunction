# attempt timed out after the NPC had moved: clean up (this is NOT an attempt cancel)
scoreboard players set #w g5 0
execute if score #moved g5 matches 1 run say [G5] NOTE cleanup_cancel
execute if score #moved g5 matches 1 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel yui
execute if score #moved g5 matches 1 run scoreboard players set #result g5 11
