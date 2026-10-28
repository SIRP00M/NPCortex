# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 6A: scoped BEFORE-NETWORK failure for ONE Yui reserve; backend must stay ONLINE","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus m03 outage6a yui
