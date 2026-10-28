# owner gate for p06_navlong_diag60 (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 6 run say [G5] NOTE gate refused: p06_navlong_diag60 is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 6 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p06_navlong_diag60 is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 6 if score #settle g5 matches -2 run say [G5] GATE p06_navlong_diag60 RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 6 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
