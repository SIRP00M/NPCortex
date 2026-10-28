# Deterministic start: NPC yui at block (2,2) on the flat test area. All G5 NPCs are despawned first (identities are kept).
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn yui
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a02
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a03
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a04
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a05
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a06
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a07
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a08
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a09
execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a10
tp @a[tag=g5_op] 0.5 -60 0.5
execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn yui
function npcortex_g5:lib/reset
