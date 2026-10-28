# Removes the arena marker and the loose items. The blocks are left (this is a throw-away world).
kill @e[type=minecraft:item]
kill @e[type=minecraft:marker,tag=npcx_anchor]
tellraw @a {"text": "[NPCortex native] Marker and items removed.", "color": "yellow"}
