execute if score #blocked g5 matches 0 run say [G5] SEQUENCE_DONE outcome=ALL_SCENARIOS_RAN (not a verdict)
execute if score #blocked g5 matches 1.. run say [G5] SEQUENCE_DONE outcome=HAD_BLOCKED_SCENARIOS (not a verdict)
scoreboard players set #running g5 0
tellraw @a[tag=g5_op] {"text":"[G5] sequence finished. Run scripts/collect-g5-evidence.ps1 to package evidence.","color":"green"}
