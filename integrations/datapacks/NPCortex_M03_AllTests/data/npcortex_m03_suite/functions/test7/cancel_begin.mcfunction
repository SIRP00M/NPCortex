# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
npcortex debug cplus taskmovecancel yui
npcortex c2 status yui
tellraw @s {"text":"[M03] Wait until C2=CANCELLED and Backend=CANCEL_PENDING before cancel_loss.","color":"yellow"}
