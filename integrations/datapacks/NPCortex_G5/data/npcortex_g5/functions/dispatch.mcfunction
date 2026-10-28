# Runs the current phase once per poll. Phase files may advance #phase; later lines then see tick -1 and do nothing.
execute if score #phase g5 matches 1 run function npcortex_g5:ph/p00_capability_probe
execute if score #phase g5 matches 2 run function npcortex_g5:ph/p02_navlong_20
execute if score #phase g5 matches 3 run function npcortex_g5:ph/p03_navlong_40
execute if score #phase g5 matches 4 run function npcortex_g5:ph/p04_navlong_80
execute if score #phase g5 matches 5 run function npcortex_g5:ph/p05_navlong_150
execute if score #phase g5 matches 6 run function npcortex_g5:ph/p06_navlong_diag60
execute if score #phase g5 matches 7 run function npcortex_g5:ph/p07_small_wall_detour
execute if score #phase g5 matches 8 run function npcortex_g5:ph/p08_large_wall_no_progress
execute if score #phase g5 matches 9 run function npcortex_g5:ph/p09_cancel_leg_active
execute if score #phase g5 matches 10 run function npcortex_g5:ph/p10_cancel_quiescing
execute if score #phase g5 matches 11 run function npcortex_g5:ph/p11_savequit_checkpoint
execute if score #phase g5 matches 12 run function npcortex_g5:ph/p12_rejoin_no_replay
execute if score #phase g5 matches 13 run function npcortex_g5:ph/p13_new_navlong_after_recovery
execute if score #phase g5 matches 14 run function npcortex_g5:ph/p14_regress_c2_navigate
execute if score #phase g5 matches 15 run function npcortex_g5:ph/p15_regress_c2_move
execute if score #phase g5 matches 16 run function npcortex_g5:ph/p16_regress_follow_start
execute if score #phase g5 matches 17 run function npcortex_g5:ph/p17_regress_follow_stop
execute if score #phase g5 matches 18 run function npcortex_g5:ph/p18_regress_e3_taskmove
execute if score #phase g5 matches 19 run function npcortex_g5:ph/p19_multi_1_npc
execute if score #phase g5 matches 20 run function npcortex_g5:ph/p20_multi_3_npc
execute if score #phase g5 matches 21 run function npcortex_g5:ph/p21_multi_5_npc
execute if score #phase g5 matches 22 run function npcortex_g5:ph/p22_multi_10_npc
scoreboard players add #tick g5 1
execute unless entity @a[tag=g5_op] run function npcortex_g5:lib/abort_no_operator
