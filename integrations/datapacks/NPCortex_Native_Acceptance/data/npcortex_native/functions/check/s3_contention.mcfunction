# S3
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] if block ~13 ~0 ~6 minecraft:air run tellraw @a {"text":"S3 PASS: the contested log is gone","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] unless block ~13 ~0 ~6 minecraft:air run tellraw @a {"text":"S3 FAIL: the contested log is still there","color":"red"}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run execute store result score #drops npx_tmp if entity @e[type=minecraft:item,distance=..3.5,nbt={Item:{id:"minecraft:oak_log"}}]
tellraw @a {"text":"S3: exactly ONE chat outcome per NPC is expected: one EFFECT_VERIFIED and one refusal (claim conflict). Read the chat.","color":"yellow"}
