# Releases the CURRENT awaiting scenario (scripts/g5-gate.ps1 prints the exact command and the scenario id). Refused unless a scenario is awaiting.
execute unless score #running g5 matches 1 run tellraw @s {"text":"[G5] gate refused: no run in progress.","color":"red"}
execute if score #running g5 matches 1 if score #phase g5 matches 1 run function npcortex_g5:gate/p00_capability_probe
execute if score #running g5 matches 1 if score #phase g5 matches 2 run function npcortex_g5:gate/p02_navlong_20
execute if score #running g5 matches 1 if score #phase g5 matches 3 run function npcortex_g5:gate/p03_navlong_40
execute if score #running g5 matches 1 if score #phase g5 matches 4 run function npcortex_g5:gate/p04_navlong_80
execute if score #running g5 matches 1 if score #phase g5 matches 5 run function npcortex_g5:gate/p05_navlong_150
execute if score #running g5 matches 1 if score #phase g5 matches 6 run function npcortex_g5:gate/p06_navlong_diag60
execute if score #running g5 matches 1 if score #phase g5 matches 7 run function npcortex_g5:gate/p07_small_wall_detour
execute if score #running g5 matches 1 if score #phase g5 matches 8 run function npcortex_g5:gate/p08_large_wall_no_progress
execute if score #running g5 matches 1 if score #phase g5 matches 9 run function npcortex_g5:gate/p09_cancel_leg_active
execute if score #running g5 matches 1 if score #phase g5 matches 10 run function npcortex_g5:gate/p10_cancel_quiescing
execute if score #running g5 matches 1 if score #phase g5 matches 11 run function npcortex_g5:gate/p11_savequit_checkpoint
execute if score #running g5 matches 1 if score #phase g5 matches 12 run function npcortex_g5:gate/p12_rejoin_no_replay
execute if score #running g5 matches 1 if score #phase g5 matches 13 run function npcortex_g5:gate/p13_new_navlong_after_recovery
execute if score #running g5 matches 1 if score #phase g5 matches 14 run function npcortex_g5:gate/p14_regress_c2_navigate
execute if score #running g5 matches 1 if score #phase g5 matches 15 run function npcortex_g5:gate/p15_regress_c2_move
execute if score #running g5 matches 1 if score #phase g5 matches 16 run function npcortex_g5:gate/p16_regress_follow_start
execute if score #running g5 matches 1 if score #phase g5 matches 17 run function npcortex_g5:gate/p17_regress_follow_stop
execute if score #running g5 matches 1 if score #phase g5 matches 18 run function npcortex_g5:gate/p18_regress_e3_taskmove
execute if score #running g5 matches 1 if score #phase g5 matches 19 run function npcortex_g5:gate/p19_multi_1_npc
execute if score #running g5 matches 1 if score #phase g5 matches 20 run function npcortex_g5:gate/p20_multi_3_npc
execute if score #running g5 matches 1 if score #phase g5 matches 21 run function npcortex_g5:gate/p21_multi_5_npc
execute if score #running g5 matches 1 if score #phase g5 matches 22 run function npcortex_g5:gate/p22_multi_10_npc
