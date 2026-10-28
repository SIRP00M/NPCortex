execute if score #sel g5 matches 1 run tag @e[type=player,tag=!g5_op,tag=!g5_npc,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300,limit=1] add g5_npc_g5n04
execute if score #sel g5 matches 2 run tag @e[type=!item,tag=!g5_op,tag=!g5_npc,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300,limit=1] add g5_npc_g5n04
execute if score #sel g5 matches 1 run tag @e[type=player,tag=g5_npc_g5n04,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] add g5_npc
execute if score #sel g5 matches 2 run tag @e[tag=g5_npc_g5n04,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] add g5_npc
