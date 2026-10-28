# Only while a scenario is AWAITING the gate. Cancels the C2 goals of all G5 NPCs; g5-gate.ps1 then waits for CANCELLED/terminal + quiescence before it prints GO.
execute unless score #settle g5 matches -2 run tellraw @s {"text":"[G5] cancel_current refused: no scenario is awaiting the gate.","color":"red"}
execute unless score #settle g5 matches -2 run say [G5] NOTE cancel_current refused: not awaiting
execute if score #settle g5 matches -2 run say [G5] NOTE cancel_current issued
execute if score #settle g5 matches -2 run function npcortex_g5:lib/snap
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel yui
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a02
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a03
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a04
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a05
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a06
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a07
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a08
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a09
execute if score #settle g5 matches -2 run execute as @a[tag=g5_op,limit=1] run npcortex c2 cancel g5a10
