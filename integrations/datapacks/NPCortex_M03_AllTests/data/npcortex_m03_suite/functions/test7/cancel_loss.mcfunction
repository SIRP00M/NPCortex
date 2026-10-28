# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 7 CANCEL-ACK-LOSS: auto-read actual task UUID; only for C2 CANCELLED / backend CANCEL_PENDING.","color":"yellow"}
npcortex debug cplus m03 loss-terminal-auto yui cancel-ack
tellraw @s {"text":"[M03] Wait for terminal ACK response loss. Then run terminal_recover.","color":"aqua"}
