# p10 per-tick watch (generated). Bounded: re-schedules itself ONLY while #w = 1; attempt end, cancel and every result clear #w.
scoreboard players set #qx g5 -999998
scoreboard players set #qz g5 -999998
execute if score #w g5 matches 1 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #qx g5 run data get entity @s Pos[0] 1000
execute if score #w g5 matches 1 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #qz g5 run data get entity @s Pos[2] 1000
execute if score #w g5 matches 1 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #qx g5 run data get entity @s Pos[0] 1000
execute if score #w g5 matches 1 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #qz g5 run data get entity @s Pos[2] 1000
scoreboard players set #q g5 0
execute unless score #qx g5 matches -999998 if score #qx g5 = #lqx g5 if score #qz g5 = #lqz g5 run scoreboard players set #q g5 1
# cancel once: the first tick the NPC stands still after >= 10 moving ticks, still >= 25 blocks from the target, never twice (#cancelled, #w)
execute if score #w g5 matches 1 if score #cancelled g5 matches 0 if score #q g5 matches 1 if score #qrun g5 matches 10.. if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 152.5 -60 2.5 unless entity @s[distance=..25] run function npcortex_g5:lib/p10_cancel
execute if score #w g5 matches 1 if score #cancelled g5 matches 0 if score #q g5 matches 1 if score #qrun g5 matches 10.. if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 152.5 -60 2.5 unless entity @s[distance=..25] run function npcortex_g5:lib/p10_cancel
execute unless score #qx g5 matches -999998 if score #q g5 matches 0 run scoreboard players add #qrun g5 1
execute if score #q g5 matches 1 run scoreboard players set #qrun g5 0
scoreboard players operation #lqx g5 = #qx g5
scoreboard players operation #lqz g5 = #qz g5
execute if score #w g5 matches 1 run schedule function npcortex_g5:lib/p10_watch 1t replace
