tellraw @a {"text":"S4: the gravel falls into the broken cell AFTER the receipt (a later change the receipt cannot attribute). The torch pops off at once (collateral).","color":"yellow"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~19 ~ ~6 minecraft:wall_torch run tellraw @a {"text":"S4 FAIL: the torch is still there","color":"red"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute unless block ~19 ~ ~6 minecraft:wall_torch run tellraw @a {"text":"S4 PASS: the torch popped off (collateral)","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute if block ~18 ~ ~6 minecraft:gravel run tellraw @a {"text":"S4 PASS: the gravel fell into the cell later","color":"green"}
