# S1
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] if block ~4 ~0 ~6 minecraft:air run tellraw @a {"text":"S1 PASS: the log is gone","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] unless block ~4 ~0 ~6 minecraft:air run tellraw @a {"text":"S1 FAIL: the log is still there (or something fell into it)","color":"red"}
