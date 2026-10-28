# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 TERMINAL RECOVERY: no replay, compare same task/attempt/goal and one durable terminal effect.","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus tasktransportrecover yui
npcortex debug cplus m03 snapshot yui
npcortex debug cplus taskmovestatus yui
