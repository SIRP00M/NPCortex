# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 6B ONLINE AGAIN: record state; recover metadata ONCE. Do not redo move.","color":"yellow"}
npcortex debug cplus m03 snapshot yui
npcortex debug cplus tasktransportrecover yui
npcortex debug cplus taskmovestatus yui
npcortex debug cplus m03 snapshot yui
tellraw @s {"text":"[M03] Compare actor/session/task/outbox hashes and unchanged C2 goal; accepted-goal restart requires separate evidence.","color":"aqua"}
