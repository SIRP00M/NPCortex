# owner gate for p20_multi_3_npc (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 20 run say [G5] NOTE gate refused: p20_multi_3_npc is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 20 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p20_multi_3_npc is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 20 if score #settle g5 matches -2 run say [G5] GATE p20_multi_3_npc RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 20 if score #settle g5 matches -2 run scoreboard players set #settle g5 1
