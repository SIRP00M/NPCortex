# owner gate for p08_large_wall_no_progress (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 8 run say [G5] NOTE gate refused: p08_large_wall_no_progress is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 8 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p08_large_wall_no_progress is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 8 if score #settle g5 matches -2 run say [G5] GATE p08_large_wall_no_progress RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 8 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
