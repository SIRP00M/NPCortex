# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 8 AFTER REJOIN: operator session must be re-enabled; compare world/actor/profile and NEW session.","color":"yellow"}
npcortex debug cplus m03 enable
npcortex debug cplus m03 snapshot yui
npcortex debug cplus taskmovestatus yui
npcortex debug cplus syncstatus yui
npcortex debug cplus audit yui
tellraw @s {"text":"[M03] Wait for snapshot SAVED. Then run test8/recover.","color":"aqua"}
