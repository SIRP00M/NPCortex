# owner gate for p13_new_navlong_after_recovery (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 13 run say [G5] NOTE gate refused: p13_new_navlong_after_recovery is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 13 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p13_new_navlong_after_recovery is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 13 if score #settle g5 matches -2 run say [G5] GATE p13_new_navlong_after_recovery RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 13 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
