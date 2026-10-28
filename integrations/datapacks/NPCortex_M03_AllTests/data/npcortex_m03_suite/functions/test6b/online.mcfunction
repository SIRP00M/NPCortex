# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 6B ONLINE: take evidence BEFORE stopping OWNED backend. Do not kill unrelated processes.","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus taskmovestatus yui
npcortex debug cplus syncstatus yui
npcortex debug cplus audit yui
tellraw @s {"text":"[M03] Wait for snapshot SAVED. Stop test-owned Backend using PowerShell controller, then run test6b/offline.","color":"aqua"}
