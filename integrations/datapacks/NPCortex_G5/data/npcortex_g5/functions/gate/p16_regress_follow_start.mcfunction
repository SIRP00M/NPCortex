# owner gate for p16_regress_follow_start (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 16 run say [G5] NOTE gate refused: p16_regress_follow_start is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 16 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p16_regress_follow_start is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 16 if score #settle g5 matches -2 run say [G5] GATE p16_regress_follow_start RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 16 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
