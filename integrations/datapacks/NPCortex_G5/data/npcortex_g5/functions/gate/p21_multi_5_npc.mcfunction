# owner gate for p21_multi_5_npc (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 21 run say [G5] NOTE gate refused: p21_multi_5_npc is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 21 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p21_multi_5_npc is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 21 if score #settle g5 matches -2 run say [G5] GATE p21_multi_5_npc RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 21 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
