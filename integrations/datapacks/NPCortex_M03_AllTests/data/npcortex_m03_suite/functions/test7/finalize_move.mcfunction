# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 FINALIZE: one NPC-derived movement. WAIT for C2 COMPLETED before finalize_loss.","color":"yellow"}
npcortex debug cplus m03 move yui
npcortex c2 status yui
