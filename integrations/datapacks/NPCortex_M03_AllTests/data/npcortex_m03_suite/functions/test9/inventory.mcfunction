# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 9 Inventory: READ-ONLY. Actual equip/swap regression uses EXISTING items and a unique transaction ID; follow guide.","color":"yellow"}
npcortex c1 inventory yui
npcortex c1 equipment yui
npcortex c1 ledger yui
npcortex debug cplus m03 snapshot yui
