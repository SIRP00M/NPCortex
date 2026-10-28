# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
npcortex debug cplus m03 fault-clear
tellraw @s {"text":"[M03] Armed fault cleared. Existing outbox and DB NOT modified.","color":"yellow"}
