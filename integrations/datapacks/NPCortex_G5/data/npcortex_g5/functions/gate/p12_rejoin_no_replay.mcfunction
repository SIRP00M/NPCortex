# owner gate for p12_rejoin_no_replay (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 12 run say [G5] NOTE gate refused: p12_rejoin_no_replay is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 12 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p12_rejoin_no_replay is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 12 if score #settle g5 matches -2 run say [G5] GATE p12_rejoin_no_replay RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 12 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
