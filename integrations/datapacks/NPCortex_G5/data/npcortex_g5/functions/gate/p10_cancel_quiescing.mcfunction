# owner gate for p10_cancel_quiescing (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 10 run say [G5] NOTE gate refused: p10_cancel_quiescing is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 10 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p10_cancel_quiescing is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 10 if score #settle g5 matches -2 run say [G5] GATE p10_cancel_quiescing RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 10 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
