# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 9: C1/C2/Inventory baseline (observation only).","color":"yellow"}
npcortex c1 info yui
npcortex c2 status yui
npcortex debug cplus taskmovestatus yui
npcortex c1 inventory yui
npcortex c1 equipment yui
npcortex c1 ledger yui
npcortex debug cplus m03 snapshot yui
