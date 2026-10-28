# owner gate for p03_navlong_40 (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 3 run say [G5] NOTE gate refused: p03_navlong_40 is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 3 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p03_navlong_40 is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 3 if score #settle g5 matches -2 run say [G5] GATE p03_navlong_40 RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 3 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
