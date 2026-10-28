# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 6B OFFLINE: issue ONE guarded move; expect CONNECT_FAILED + HOLD; no C2 movement.","color":"yellow"}
npcortex debug cplus m03 move yui
npcortex debug cplus m03 snapshot yui
tellraw @s {"text":"[M03] Wait for reserve failure + snapshot SAVED. Restart SAME owned backend and run test6b/restart.","color":"aqua"}
