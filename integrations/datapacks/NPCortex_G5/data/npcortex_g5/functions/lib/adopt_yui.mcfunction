# tag the single untagged non-operator candidate as yui (no-op when already tagged)
scoreboard players set #nu g5 0
execute if score #sel g5 matches 1 store result score #nu g5 if entity @e[type=player,tag=!g5_op,tag=!g5_npc,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #sel g5 matches 2 store result score #nu g5 if entity @e[type=!item,tag=!g5_op,tag=!g5_npc,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] if score #nu g5 matches 1 run function npcortex_g5:lib/adopt_do_yui
execute if score #sel g5 matches 2 unless entity @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] if score #nu g5 matches 1 run function npcortex_g5:lib/adopt_do_yui
