# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
npcortex c2 status yui
npcortex debug cplus taskmovefinalize yui
npcortex debug cplus taskmovestatus yui
npcortex debug cplus m03 snapshot yui
tellraw @s {"text":"[M03] Review goal/ACK; do not claim success if C2 was not COMPLETED.","color":"yellow"}
