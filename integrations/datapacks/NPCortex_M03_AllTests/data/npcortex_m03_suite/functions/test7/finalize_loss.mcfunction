# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 FINALIZE-LOSS: auto-read REAL task UUID, match C2 COMPLETED, arm and submit exact finalize ONCE.","color":"yellow"}
npcortex debug cplus m03 loss-terminal-auto yui finalize
tellraw @s {"text":"[M03] Wait for G3 HOLD terminal response withheld; then run test7/terminal_recover.","color":"aqua"}
