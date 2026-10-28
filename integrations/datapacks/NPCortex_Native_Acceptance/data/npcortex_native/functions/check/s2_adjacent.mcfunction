# S2a
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] if block ~8 ~0 ~6 minecraft:air run tellraw @a {"text":"S2a PASS: first log gone","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] unless block ~8 ~0 ~6 minecraft:air run tellraw @a {"text":"S2a FAIL: first log still there","color":"red"}
# S2b
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] if block ~9 ~0 ~6 minecraft:air run tellraw @a {"text":"S2b PASS: second log gone","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] unless block ~9 ~0 ~6 minecraft:air run tellraw @a {"text":"S2b FAIL: second log still there","color":"red"}
