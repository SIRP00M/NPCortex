# Snapshot (journal state at this instant) and cancel in the same tick; the snapshot proves which state was cancelled.
scoreboard players set #cancelled g5 1
scoreboard players set #still g5 0
execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot yui
# Revision 6: no `c2 status` here - command feedback of a function is suppressed by vanilla, so it never reached the log. The longState at the cancel is recorded by production (D1-B).
execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel yui
say [G5] NOTE cancel issued
