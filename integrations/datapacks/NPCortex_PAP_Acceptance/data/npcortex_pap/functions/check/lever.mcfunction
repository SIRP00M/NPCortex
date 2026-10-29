execute at @e[type=minecraft:marker,tag=npcx_pap_anchor,limit=1] if block ~6 ~ ~2 minecraft:lever[powered=true] run tellraw @s {"text":"PASS lever is powered (the NPC used it)","color":"green"}
execute at @e[type=minecraft:marker,tag=npcx_pap_anchor,limit=1] unless block ~6 ~ ~2 minecraft:lever[powered=true] run tellraw @s {"text":"PENDING lever is not powered yet","color":"yellow"}
