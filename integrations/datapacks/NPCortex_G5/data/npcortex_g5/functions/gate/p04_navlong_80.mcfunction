# owner gate for p04_navlong_80 (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 4 run say [G5] NOTE gate refused: p04_navlong_80 is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 4 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p04_navlong_80 is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 4 if score #settle g5 matches -2 run say [G5] GATE p04_navlong_80 RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 4 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
