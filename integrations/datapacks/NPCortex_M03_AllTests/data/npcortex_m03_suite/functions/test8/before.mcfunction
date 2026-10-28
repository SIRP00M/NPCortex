# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 8 BEFORE Save/Quit: wait for snapshot SAVED, capture screen/log; quit world cleanly.","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus taskmovestatus yui
npcortex debug cplus syncstatus yui
npcortex debug cplus audit yui
npcortex c2 status yui
tellraw @s {"text":"[M03] Now use Save & Quit. Do NOT reset DB, outbox or unresolved HOLD.","color":"aqua"}
