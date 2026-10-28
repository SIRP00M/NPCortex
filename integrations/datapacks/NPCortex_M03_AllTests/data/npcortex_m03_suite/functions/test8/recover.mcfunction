# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
npcortex debug cplus tasktransportrecover yui
npcortex debug cplus m03 snapshot yui
npcortex c2 status yui
tellraw @s {"text":"[M03] Compare before/after via m03_final.py compare-rejoin. No movement replay; HOLD if unresolved.","color":"yellow"}
