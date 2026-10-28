# Observation of the NPC entity (a ServerPlayer) through its adopted tag. #present=0 means it cannot be identified/seen.
function npcortex_g5:lib/adopt_yui
scoreboard players set #present g5 0
execute if score #sel g5 matches 1 store success score #present g5 if entity @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #sel g5 matches 2 store success score #present g5 if entity @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #present g5 matches 1 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #px g5 run data get entity @s Pos[0]
execute if score #present g5 matches 1 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #pz g5 run data get entity @s Pos[2]
execute if score #present g5 matches 1 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #px g5 run data get entity @s Pos[0]
execute if score #present g5 matches 1 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #pz g5 run data get entity @s Pos[2]
