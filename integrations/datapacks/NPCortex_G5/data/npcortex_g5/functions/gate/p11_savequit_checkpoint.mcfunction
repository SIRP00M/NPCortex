# owner gate for p11_savequit_checkpoint (generated)
execute unless score #running g5 matches 1 run say [G5] NOTE gate refused: no run in progress
execute if score #running g5 matches 1 unless score #phase g5 matches 11 run say [G5] NOTE gate refused: p11_savequit_checkpoint is not the current phase
execute if score #running g5 matches 1 if score #phase g5 matches 11 unless score #settle g5 matches -2 run say [G5] NOTE gate refused: p11_savequit_checkpoint is not awaiting the gate
execute if score #running g5 matches 1 if score #phase g5 matches 11 if score #settle g5 matches -2 run say [G5] GATE p11_savequit_checkpoint RELEASED
execute if score #running g5 matches 1 if score #phase g5 matches 11 if score #settle g5 matches -2 run function npcortex_g5:lib/checkpoint_savequit
