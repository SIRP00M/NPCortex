# owner gate for p18_regress_e3_taskmove (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 18 run say [G5] NOTE gate refused: p18_regress_e3_taskmove is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 18 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p18_regress_e3_taskmove is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 18 if score #settle g5 matches -2 run say [G5] GATE p18_regress_e3_taskmove RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 18 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
