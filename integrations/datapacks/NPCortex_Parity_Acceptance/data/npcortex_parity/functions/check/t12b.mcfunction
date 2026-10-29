execute unless entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 1
execute if entity @e[tag=npcortex_Parity,limit=1] run scoreboard players set #absent pp_t 0
execute store result score #logs pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:oak_log"}].Count
execute if score #absent pp_t matches 1 run say PARITY|T12|PENDING respawn first: /npcortex c1 respawn Parity
execute if score #absent pp_t matches 0 if score #logs pp_t matches 1.. run say PARITY|T12|FAIL respawned NPC still holds logs (keepInventory=false)
execute if score #absent pp_t matches 0 unless score #logs pp_t matches 1.. run say PARITY|T12|PASS respawned with an empty inventory
