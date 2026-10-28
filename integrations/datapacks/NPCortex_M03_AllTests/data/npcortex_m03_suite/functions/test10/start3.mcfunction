# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 10: EXACTLY 3 active NPC required; 120-sec tick measurement. Re-run three rounds with same conditions.","color":"yellow"}
npcortex debug cplus m03 measure-auto 3 120
tellraw @s {"text":"[M03] Once measurement running, issue /function npcortex_m03_suite:test10/workload3 to send one guarded move each (no replay).","color":"aqua"}
