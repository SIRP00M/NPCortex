# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 RESERVE-LOSS: Backend receives/commits reserve, response deliberately withheld ONCE.","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus m03 loss-reserve yui
tellraw @s {"text":"[M03] Wait for G2 HOLD and fault AFTER_SUCCESSFUL_RESPONSE before calling reserve_recover.","color":"aqua"}
