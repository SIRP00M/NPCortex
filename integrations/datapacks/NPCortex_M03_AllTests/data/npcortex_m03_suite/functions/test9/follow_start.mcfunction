# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 9 Follow: follow YOU (OP) using current username, no name entry. Walk to test tracking.","color":"yellow"}
npcortex debug cplus m03 follow-self yui
npcortex c2 followstatus yui
npcortex debug cplus m03 snapshot yui
