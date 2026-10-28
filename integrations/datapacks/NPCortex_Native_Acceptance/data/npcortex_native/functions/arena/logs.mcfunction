# S1 one log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~4 ~ ~6 minecraft:oak_log
# S2 two adjacent logs
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~8 ~ ~6 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~9 ~ ~6 minecraft:oak_log
# S3 one log between two NPC tiles (same-block contention)
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~13 ~ ~6 minecraft:oak_log
# S4 collateral: gravel on top, a wall torch on the east face
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~18 ~ ~6 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~18 ~1 ~6 minecraft:gravel
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~19 ~ ~6 minecraft:wall_torch[facing=east]
# denied targets: not on the allow-list (diamond block), a block entity (chest)
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~34 ~ ~6 minecraft:diamond_block
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~36 ~ ~6 minecraft:chest
# S8 a row of ten logs
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~2 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~4 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~6 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~8 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~10 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~12 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~14 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~16 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~18 ~ ~12 minecraft:oak_log
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run setblock ~20 ~ ~12 minecraft:oak_log
