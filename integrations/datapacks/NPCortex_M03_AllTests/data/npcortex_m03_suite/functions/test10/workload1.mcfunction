# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 10: ONE guarded move per active NPC, strict preflight count=1, no auto-retry.","color":"yellow"}
npcortex debug cplus m03 move-all 1
tellraw @s {"text":"[M03] For completed moves, finalize matching each NPC by normal G3 command; measure observer itself never marks PASS.","color":"aqua"}
