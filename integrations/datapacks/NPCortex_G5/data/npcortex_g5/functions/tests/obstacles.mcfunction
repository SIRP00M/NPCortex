# start obstacles
execute unless score #ready g5 matches 1 run tellraw @s {"text":"[G5] run npcortex_g5:setup first","color":"red"}
execute if score #ready g5 matches 1 unless score #running g5 matches 1 run say [G5] START obstacles
execute if score #ready g5 matches 1 unless score #running g5 matches 1 unless score #probed g5 matches 1 run say [G5] NOTE mode=OWNER_GATED_UNPROBED (p00 not run in this setup: no in-game observation, every scenario needs the owner gate; run npcortex_g5:probe first for adaptive control)
execute if score #ready g5 matches 1 unless score #running g5 matches 1 run function npcortex_g5:lib/begin_obstacles
