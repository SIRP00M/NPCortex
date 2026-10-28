# owner gate for p15_regress_c2_move (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 15 run say [G5] NOTE gate refused: p15_regress_c2_move is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 15 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p15_regress_c2_move is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 15 if score #settle g5 matches -2 run say [G5] GATE p15_regress_c2_move RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 15 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
