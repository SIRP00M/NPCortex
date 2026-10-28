# p22_multi_10_npc (generated). lane k: NPC block z=8k+2, goal (42,-60,8k+2). Needs exactly 10 live NPCs for measure-auto.
execute if score #tick g5 matches 0 run say [G5] BEGIN p22_multi_10_npc
execute if score #tick g5 matches 0 run function npcortex_g5:lib/reset
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn yui
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a02
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a03
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a04
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a05
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a06
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a07
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a08
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a09
execute if score #tick g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c1 despawn g5a10
execute if score #tick g5 matches 0 run scoreboard players set #r0 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r1 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r2 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r3 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r4 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r5 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r6 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r7 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r8 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #r9 g5 0
execute if score #tick g5 matches 1 run tp @a[tag=g5_op] 0.5 -60 0.5
execute if score #tick g5 matches 1 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn yui
execute if score #tick g5 matches 2 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_yui
execute if score #tick g5 matches 3 run tp @a[tag=g5_op] 0.5 -60 8.5
execute if score #tick g5 matches 3 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a02
execute if score #tick g5 matches 4 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a02
execute if score #tick g5 matches 5 run tp @a[tag=g5_op] 0.5 -60 16.5
execute if score #tick g5 matches 5 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a03
execute if score #tick g5 matches 6 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a03
execute if score #tick g5 matches 7 run tp @a[tag=g5_op] 0.5 -60 24.5
execute if score #tick g5 matches 7 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a04
execute if score #tick g5 matches 8 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a04
execute if score #tick g5 matches 9 run tp @a[tag=g5_op] 0.5 -60 32.5
execute if score #tick g5 matches 9 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a05
execute if score #tick g5 matches 10 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a05
execute if score #tick g5 matches 11 run tp @a[tag=g5_op] 0.5 -60 40.5
execute if score #tick g5 matches 11 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a06
execute if score #tick g5 matches 12 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a06
execute if score #tick g5 matches 13 run tp @a[tag=g5_op] 0.5 -60 48.5
execute if score #tick g5 matches 13 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a07
execute if score #tick g5 matches 14 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a07
execute if score #tick g5 matches 15 run tp @a[tag=g5_op] 0.5 -60 56.5
execute if score #tick g5 matches 15 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a08
execute if score #tick g5 matches 16 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a08
execute if score #tick g5 matches 17 run tp @a[tag=g5_op] 0.5 -60 64.5
execute if score #tick g5 matches 17 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a09
execute if score #tick g5 matches 18 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a09
execute if score #tick g5 matches 19 run tp @a[tag=g5_op] 0.5 -60 72.5
execute if score #tick g5 matches 19 run execute as @a[tag=g5_op,limit=1] run npcortex c1 spawn g5a10
execute if score #tick g5 matches 20 if score #adaptive g5 matches 1 run function npcortex_g5:lib/adopt_g5a10
execute if score #tick g5 matches 21 run tp @a[tag=g5_op] 0.5 -60 0.5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 2 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a02,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a02,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 10 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a03,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a03,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 18 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a04,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a04,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 26 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a05,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a05,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 34 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a06,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a06,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 42 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a07,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a07,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 50 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a08,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a08,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 58 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a09,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a09,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 66 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 1 unless entity @e[type=player,tag=g5_npc_g5a10,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #adaptive g5 matches 1 if score #sel g5 matches 2 unless entity @e[tag=g5_npc_g5a10,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] run scoreboard players set #result g5 4
execute if score #tick g5 matches 22 if score #result g5 matches 0 unless block 42 -64 74 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot yui
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a02
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a03
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a04
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a05
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a06
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a07
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a08
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a09
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a10
execute if score #tick g5 matches 23 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 measure-auto 10 120
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong yui 42 -60 2
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a02 42 -60 10
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a03 42 -60 18
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a04 42 -60 26
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a05 42 -60 34
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a06 42 -60 42
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a07 42 -60 50
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a08 42 -60 58
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a09 42 -60 66
execute if score #tick g5 matches 24 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong g5a10 42 -60 74
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 2.5 if entity @s[distance=..1.6] run scoreboard players set #r0 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 2.5 if entity @s[distance=..1.6] run scoreboard players set #r0 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a02,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 10.5 if entity @s[distance=..1.6] run scoreboard players set #r1 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a02,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 10.5 if entity @s[distance=..1.6] run scoreboard players set #r1 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a03,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 18.5 if entity @s[distance=..1.6] run scoreboard players set #r2 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a03,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 18.5 if entity @s[distance=..1.6] run scoreboard players set #r2 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a04,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 26.5 if entity @s[distance=..1.6] run scoreboard players set #r3 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a04,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 26.5 if entity @s[distance=..1.6] run scoreboard players set #r3 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a05,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 34.5 if entity @s[distance=..1.6] run scoreboard players set #r4 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a05,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 34.5 if entity @s[distance=..1.6] run scoreboard players set #r4 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a06,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 42.5 if entity @s[distance=..1.6] run scoreboard players set #r5 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a06,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 42.5 if entity @s[distance=..1.6] run scoreboard players set #r5 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a07,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 50.5 if entity @s[distance=..1.6] run scoreboard players set #r6 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a07,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 50.5 if entity @s[distance=..1.6] run scoreboard players set #r6 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a08,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 58.5 if entity @s[distance=..1.6] run scoreboard players set #r7 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a08,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 58.5 if entity @s[distance=..1.6] run scoreboard players set #r7 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a09,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 66.5 if entity @s[distance=..1.6] run scoreboard players set #r8 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a09,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 66.5 if entity @s[distance=..1.6] run scoreboard players set #r8 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 as @e[type=player,tag=g5_npc_g5a10,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 74.5 if entity @s[distance=..1.6] run scoreboard players set #r9 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 as @e[tag=g5_npc_g5a10,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 42.5 -60 74.5 if entity @s[distance=..1.6] run scoreboard players set #r9 g5 1
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players set #rsum g5 0
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r0 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r1 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r2 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r3 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r4 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r5 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r6 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r7 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r8 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 run scoreboard players operation #rsum g5 += #r9 g5
execute if score #tick g5 matches 25.. if score #adaptive g5 matches 1 if score #result g5 matches 0 if score #rsum g5 matches 10 run scoreboard players set #result g5 1
execute if score #tick g5 matches 617.. if score #adaptive g5 matches 1 if score #result g5 matches 0 run scoreboard players set #result g5 2
execute if score #tick g5 matches 277.. if score #adaptive g5 matches 0 if score #result g5 matches 0 run scoreboard players set #result g5 10
execute if score #result g5 matches 1 if score #settle g5 matches -1 run say [G5] OBS p22_multi_10_npc ALL_REACHED
execute if score #result g5 matches 2 if score #settle g5 matches -1 run say [G5] OBS p22_multi_10_npc TIMEOUT
execute if score #result g5 matches 4 if score #settle g5 matches -1 run say [G5] OBS p22_multi_10_npc BLOCKED_NPC_NOT_SELECTABLE
execute if score #result g5 matches 5 if score #settle g5 matches -1 run say [G5] OBS p22_multi_10_npc BLOCKED_TARGET_CHUNK_NOT_LOADED
execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p22_multi_10_npc AWAITING_OWNER_GATE
scoreboard players set #gn g5 0
execute if score #result g5 matches 2..3 run scoreboard players set #gn g5 1
execute if score #result g5 matches 6 run scoreboard players set #gn g5 1
execute if score #result g5 matches 8 run scoreboard players set #gn g5 1
execute if score #result g5 matches 10 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 1 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 7 run scoreboard players set #gn g5 1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run say [G5] AWAIT p22_multi_10_npc owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {"text":"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.","color":"gold"}
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2
execute if score #gn g5 matches 0 if score #result g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 20
execute if score #gn g5 matches 0 if score #result g5 matches 4..5 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 7 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 11 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap_10
execute if score #settle g5 matches 1.. run scoreboard players remove #settle g5 1
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run function npcortex_g5:lib/snap
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run say [G5] END p22_multi_10_npc
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r0 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_yui REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r0 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_yui NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot yui
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r1 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a02 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r1 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a02 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a02
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r2 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a03 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r2 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a03 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a03
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r3 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a04 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r3 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a04 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a04
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r4 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a05 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r4 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a05 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a05
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r5 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a06 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r5 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a06 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a06
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r6 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a07 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r6 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a07 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a07
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r7 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a08 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r7 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a08 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a08
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r8 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a09 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r8 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a09 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a09
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #r9 g5 matches 1 run say [G5] OBS p22_multi_10_npc NPC_g5a10 REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute if score #adaptive g5 matches 1 if score #r9 g5 matches 0 run say [G5] OBS p22_multi_10_npc NPC_g5a10 NOT_REACHED
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 snapshot g5a10
execute if score #settle g5 matches 0 if score #tick g5 matches 277.. run function npcortex_g5:lib/advance
execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs
