# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
npcortex debug cplus m03 snapshot yui
npcortex debug cplus taskmovestatus yui
npcortex c2 status yui
tellraw @s {"text":"[M03] Check HOLD CONNECT_FAILED / no new navigation / durable never-handed-off reserve. NOT real process restart.","color":"yellow"}
