execute unless score #ready g5 matches 1 run tellraw @s {"text":"[G5] run npcortex_g5:setup first","color":"red"}
execute if score #ready g5 matches 1 unless score #running g5 matches 1 run say [G5] START only:p09_cancel_leg_active
execute if score #ready g5 matches 1 unless score #running g5 matches 1 run function npcortex_g5:lib/begin_9
execute if score #ready g5 matches 1 unless score #probed g5 matches 1 run say [G5] NOTE mode=OWNER_GATED_UNPROBED (p00 not run in this setup: every scenario needs the owner gate)
