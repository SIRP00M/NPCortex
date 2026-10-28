# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 9: Expected guarded move REJECT while Follow owns Yui. No goal theft!","color":"yellow"}
npcortex debug cplus m03 move yui
npcortex c2 followstatus yui
