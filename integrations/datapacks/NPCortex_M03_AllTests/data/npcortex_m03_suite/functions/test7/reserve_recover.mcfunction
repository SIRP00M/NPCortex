# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 RECOVER: inspect exact retained attempt; metadata recovery must NEVER start movement.","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus tasktransportrecover yui
npcortex debug cplus m03 snapshot yui
npcortex debug cplus taskmovestatus yui
tellraw @s {"text":"[M03] If identity/ACK evidence uncertain: HOLD, keep records. No retry.","color":"yellow"}
