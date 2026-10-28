# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 CANCEL ACK: submit one NPC-derived move, then run cancel_begin WHILE MOVING.","color":"yellow"}
npcortex debug cplus m03 move yui
