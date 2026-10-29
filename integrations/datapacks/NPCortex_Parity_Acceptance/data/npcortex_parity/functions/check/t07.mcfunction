execute at @e[type=minecraft:marker,tag=pp_anchor,limit=1] store success score #gone pp_t unless block ~4 ~ ~1 minecraft:oak_log
execute store result score #logs pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:oak_log"}].Count
execute store result score #dmg pp_t run data get entity @e[tag=npcortex_Parity,limit=1] Inventory[{id:"minecraft:iron_axe"}].tag.Damage
execute if score #gone pp_t matches 1 if score #logs pp_t matches 1.. if score #dmg pp_t matches 1.. run function npcortex_parity:check/t07_pass
execute unless score #gone pp_t matches 1 run say PARITY|T07|PENDING the log is still there (the Owner has not run the action yet)
execute if score #gone pp_t matches 1 unless score #logs pp_t matches 1.. run function npcortex_parity:check/t07_fail
execute if score #gone pp_t matches 1 if score #logs pp_t matches 1.. unless score #dmg pp_t matches 1.. run function npcortex_parity:check/t07_fail
