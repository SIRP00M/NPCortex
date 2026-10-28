# Builds a DISPOSABLE arena at the executor's feet. It clears blocks. Use it ONLY in a throw-away test world.
scoreboard objectives add npx_tmp dummy
kill @e[type=minecraft:marker,tag=npcx_anchor]
summon minecraft:marker ~ ~ ~ {Tags:["npcx_anchor"]}
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run fill ~-2 ~-2 ~-2 ~40 ~8 ~16 minecraft:air
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run fill ~-2 ~-1 ~-2 ~40 ~-1 ~16 minecraft:stone
function npcortex_native:arena/logs
function npcortex_native:arena/items
tellraw @a {"text": "[NPCortex native] Arena built. Next: /function npcortex_native:info  (prints the anchor and the allow-list region)", "color": "green"}
