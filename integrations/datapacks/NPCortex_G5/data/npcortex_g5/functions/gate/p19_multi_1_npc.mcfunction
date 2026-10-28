# owner gate for p19_multi_1_npc (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 19 run say [G5] NOTE gate refused: p19_multi_1_npc is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 19 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p19_multi_1_npc is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 19 if score #settle g5 matches -2 run say [G5] GATE p19_multi_1_npc RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 19 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
