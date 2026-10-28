# While a scenario waits for the owner gate: one M03 snapshot every 10 polls (5 s) so scripts/g5-gate.ps1 can judge quiescence.
scoreboard players operation #m g5 = #tick g5
scoreboard players operation #m g5 %= #aw g5
execute if score #m g5 matches 0 run function npcortex_g5:lib/snap
