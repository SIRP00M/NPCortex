# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
npcortex debug cplus m03 enable
npcortex debug cplus m03 status
tellraw @s {"text":"[M03] Bridge enabled (if command confirms). No tests auto-start.","color":"aqua"}
function npcortex_m03_suite:menu
