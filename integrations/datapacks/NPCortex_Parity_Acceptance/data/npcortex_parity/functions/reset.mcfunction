kill @e[type=minecraft:zombie,tag=pp_mob]
effect give @e[tag=npcortex_Parity,limit=1] minecraft:instant_health 1 10 true
schedule clear npcortex_parity:lib/sample
# A dead NPC cannot be despawned+respawned (lifecycle DEAD blocks spawn): revive it first (no-op message if it is alive), then despawn.
npcortex c1 respawn Parity
npcortex c1 despawn Parity
kill @e[type=minecraft:marker,tag=pp_anchor]
kill @e[type=minecraft:zombie,tag=pp_mob]
kill @e[type=minecraft:item,tag=pp_item]
# death drops and block drops carry no tag; vanilla pickup would hand them to the next scenario's NPC (throw-away world only)
kill @e[type=minecraft:item,distance=..24]
execute at @s run fill ~-3 ~-5 ~-3 ~9 ~8 ~9 minecraft:air
gamerule keepInventory false
scoreboard players reset * pp
tellraw @s {"text":"[parity] reset: NPC despawned, cell cleared, results cleared. Re-run the setup of the test you want.","color":"gold"}
