execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] if block ~34 ~0 ~6 minecraft:diamond_block run tellraw @a {"text":"DENY-diamond PASS: the unlisted block is untouched","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] unless block ~34 ~0 ~6 minecraft:diamond_block run tellraw @a {"text":"DENY-diamond FAIL: the diamond block was broken","color":"red"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] if block ~36 ~0 ~6 minecraft:chest run tellraw @a {"text":"DENY-chest PASS: the chest is untouched","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] unless block ~36 ~0 ~6 minecraft:chest run tellraw @a {"text":"DENY-chest FAIL: the chest was broken","color":"red"}
