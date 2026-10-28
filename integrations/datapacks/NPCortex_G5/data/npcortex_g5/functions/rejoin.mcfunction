# Run after Save&Quit + re-opening the world (the M03 session does not survive a restart). Refused unless the p11 checkpoint set #cp (stale-run guard).
tag @s add g5_op
execute unless score #cp g5 matches 1 run tellraw @s {"text":"[G5] rejoin refused: no pending Save&Quit checkpoint (nothing to continue).","color":"red"}
execute unless score #cp g5 matches 1 run say [G5] NOTE rejoin refused: no pending checkpoint
execute if score #cp g5 matches 1 run function npcortex_g5:lib/rejoin_go
