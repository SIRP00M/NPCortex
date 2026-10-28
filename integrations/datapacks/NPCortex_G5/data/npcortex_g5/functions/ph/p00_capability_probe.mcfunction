# p00 (generated). A missing capability selects OWNER_GATED mode (M03 snapshot evidence + owner gate), never a blind run. say text must not contain @ (vanilla expands selectors in say).
execute if score #tick g5 matches 0 run say [G5] BEGIN p00_capability_probe capability probe v2
execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start
execute if score #tick g5 matches 0 run scoreboard players set #adaptive g5 0
execute if score #tick g5 matches 0 run scoreboard players set #probed g5 0
execute if score #tick g5 matches 0 run scoreboard players set #sel g5 0
execute if score #tick g5 matches 0 run scoreboard players set #nu g5 0
execute if score #tick g5 matches 0 run scoreboard players set #s_ctl g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #s_v1 g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #s_v2 g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #s_t1 g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #s_t2 g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #s_dat g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #s_pos g5 -1
execute if score #tick g5 matches 0 run scoreboard players set #n_a g5 0
execute if score #tick g5 matches 0 run scoreboard players set #n_v1 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #n_v2 g5 0
execute if score #tick g5 matches 0 run scoreboard players set #px g5 -999999
execute if score #tick g5 matches 0 run scoreboard players set #pz g5 -999999
execute if score #tick g5 matches 3 store result score #n_a g5 if entity @a
execute if score #tick g5 matches 3 store result score #n_v1 g5 if entity @e[type=player,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #tick g5 matches 3 store result score #n_v2 g5 if entity @e[type=!item,tag=!g5_op,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #tick g5 matches 3 if score #n_a g5 matches 1 run scoreboard players set #s_ctl g5 1
execute if score #tick g5 matches 3 unless score #n_a g5 matches 1 run scoreboard players set #s_ctl g5 0
execute if score #tick g5 matches 3 if score #n_v1 g5 matches 2 run scoreboard players set #s_v1 g5 1
execute if score #tick g5 matches 3 unless score #n_v1 g5 matches 2 run scoreboard players set #s_v1 g5 0
execute if score #tick g5 matches 3 if score #n_v2 g5 matches 1 run scoreboard players set #s_v2 g5 1
execute if score #tick g5 matches 3 unless score #n_v2 g5 matches 1 run scoreboard players set #s_v2 g5 0
execute if score #tick g5 matches 3 if score #s_ctl g5 matches 1 run say [G5] PROBE playerlist_control=PASS (PlayerList sees only the operator: the NPC is not a PlayerList member, as designed)
execute if score #tick g5 matches 3 if score #s_ctl g5 matches 0 run say [G5] PROBE playerlist_control=FAIL (PlayerList sees a different number of players than 1: informational, the NPC may be a PlayerList member)
execute if score #tick g5 matches 3 if score #s_v1 g5 matches 1 run say [G5] PROBE world_limited_player=PASS (operator and NPC found by the player selector with the search box)
execute if score #tick g5 matches 3 if score #s_v1 g5 matches 0 run say [G5] PROBE world_limited_player=FAIL (NPC not found, or more than one other player: see count)
execute if score #tick g5 matches 3 if score #n_v1 g5 matches ..1 run say [G5] PROBE world_limited_player_count=0_or_1 (only the operator or nobody)
execute if score #tick g5 matches 3 if score #n_v1 g5 matches 3.. run say [G5] PROBE world_limited_player_count=3_or_more (ambiguous: another player or NPC exists)
execute if score #tick g5 matches 3 if score #s_v2 g5 matches 1 run say [G5] PROBE typeless_entity=PASS (exactly one non-operator entity found by the typeless selector with the search box)
execute if score #tick g5 matches 3 if score #s_v2 g5 matches 0 run say [G5] PROBE typeless_entity=FAIL (none, or more than one non-operator entity: ambiguous)
execute if score #tick g5 matches 3 if score #n_v2 g5 matches 0 run say [G5] PROBE typeless_entity_count=0 (no non-operator entity found)
execute if score #tick g5 matches 3 if score #n_v2 g5 matches 2.. run say [G5] PROBE typeless_entity_count=2_or_more (ambiguous: another entity exists)
execute if score #tick g5 matches 4 if score #s_v1 g5 matches 1 run tag @e[type=player,tag=!g5_op,tag=!g5_npc,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300,limit=1] add g5_pv1
execute if score #tick g5 matches 4 if score #s_v1 g5 matches 1 store success score #s_t1 g5 if entity @e[type=player,tag=g5_pv1,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #tick g5 matches 4 if score #s_v1 g5 matches 1 run tag @e[type=player,tag=g5_pv1,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] remove g5_pv1
execute if score #tick g5 matches 4 if score #s_v2 g5 matches 1 run tag @e[type=!item,tag=!g5_op,tag=!g5_npc,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300,limit=1] add g5_pv2
execute if score #tick g5 matches 4 if score #s_v2 g5 matches 1 store success score #s_t2 g5 if entity @e[tag=g5_pv2,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]
execute if score #tick g5 matches 4 if score #s_v2 g5 matches 1 run tag @e[tag=g5_pv2,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] remove g5_pv2
execute if score #tick g5 matches 4 run function npcortex_g5:lib/snap
execute if score #tick g5 matches 4 if score #s_t1 g5 matches 1 run say [G5] PROBE tag_v1=PASS (tag add and verify through the player selector)
execute if score #tick g5 matches 4 if score #s_t1 g5 matches 0 run say [G5] PROBE tag_v1=FAIL (tag was not found after adding)
execute if score #tick g5 matches 4 if score #s_t1 g5 matches -1 run say [G5] PROBE tag_v1=NOT_ATTEMPTED (world_limited_player did not pass)
execute if score #tick g5 matches 4 if score #s_t2 g5 matches 1 run say [G5] PROBE tag_v2=PASS (tag add and verify through the typeless selector)
execute if score #tick g5 matches 4 if score #s_t2 g5 matches 0 run say [G5] PROBE tag_v2=FAIL (tag was not found after adding)
execute if score #tick g5 matches 4 if score #s_t2 g5 matches -1 run say [G5] PROBE tag_v2=NOT_ATTEMPTED (typeless_entity did not pass)
execute if score #tick g5 matches 5 if score #s_t1 g5 matches 1 run scoreboard players set #sel g5 1
execute if score #tick g5 matches 5 unless score #sel g5 matches 1 if score #s_t2 g5 matches 1 run scoreboard players set #sel g5 2
execute if score #tick g5 matches 5 unless score #sel g5 matches 0 run function npcortex_g5:lib/adopt_yui
execute if score #tick g5 matches 5 if score #sel g5 matches 1 run say [G5] PROBE selector_chosen=world_limited_player
execute if score #tick g5 matches 5 if score #sel g5 matches 2 run say [G5] PROBE selector_chosen=typeless_entity
execute if score #tick g5 matches 5 if score #sel g5 matches 0 run say [G5] PROBE selector_chosen=NONE (no selector kind passed selector and tag together)
execute if score #tick g5 matches 6 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store success score #s_dat g5 run data get entity @s Pos[0]
execute if score #tick g5 matches 6 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store success score #s_dat g5 run data get entity @s Pos[0]
execute if score #tick g5 matches 6 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #px g5 run data get entity @s Pos[0]
execute if score #tick g5 matches 6 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #px g5 run data get entity @s Pos[0]
execute if score #tick g5 matches 6 if score #sel g5 matches 1 as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #pz g5 run data get entity @s Pos[2]
execute if score #tick g5 matches 6 if score #sel g5 matches 2 as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] store result score #pz g5 run data get entity @s Pos[2]
execute if score #tick g5 matches 6 if score #s_dat g5 matches 1 run scoreboard players set #s_pos g5 0
execute if score #tick g5 matches 6 if score #s_dat g5 matches 1 if score #px g5 matches 1..3 if score #pz g5 matches 1..3 run scoreboard players set #s_pos g5 1
execute if score #tick g5 matches 6 if score #s_dat g5 matches 1 run say [G5] PROBE entity_data=PASS (data get entity Pos works on the NPC)
execute if score #tick g5 matches 6 if score #s_dat g5 matches 0 run say [G5] PROBE entity_data=FAIL (data get entity Pos failed on the NPC)
execute if score #tick g5 matches 6 if score #s_dat g5 matches -1 run say [G5] PROBE entity_data=NOT_ATTEMPTED (no selector kind passed, or the adopted NPC was not selected)
execute if score #tick g5 matches 6 if score #s_pos g5 matches 1 run say [G5] PROBE position=PASS (Pos agrees with the spawn block 2,2)
execute if score #tick g5 matches 6 if score #s_pos g5 matches 0 run say [G5] PROBE position=FAIL (Pos does not agree with the spawn block 2,2)
execute if score #tick g5 matches 6 if score #s_pos g5 matches -1 run say [G5] PROBE position=NOT_ATTEMPTED (entity data was not readable)
execute if score #tick g5 matches 7 unless score #sel g5 matches 0 if score #s_dat g5 matches 1 if score #s_pos g5 matches 1 run scoreboard players set #adaptive g5 1
execute if score #tick g5 matches 7 if score #adaptive g5 matches 1 run scoreboard players set #per g5 10
execute if score #tick g5 matches 7 if score #adaptive g5 matches 0 run scoreboard players set #per g5 5
execute if score #tick g5 matches 7 run scoreboard players set #probed g5 1
execute if score #tick g5 matches 7 if score #adaptive g5 matches 1 run say [G5] PROBE mode=ADAPTIVE (in-game observation verified; M03 snapshots stay the independent evidence)
execute if score #tick g5 matches 7 if score #adaptive g5 matches 0 run say [G5] PROBE mode=OWNER_GATED (M03 snapshots + logs are the evidence; progression needs scripts/g5-gate.ps1 and the owner gate)
execute if score #tick g5 matches 7 if score #adaptive g5 matches 1 run scoreboard players set #result g5 1
execute if score #tick g5 matches 7 if score #adaptive g5 matches 0 run scoreboard players set #result g5 11
execute if score #result g5 matches 1 if score #settle g5 matches -1 run say [G5] OBS p00_capability_probe ADAPTIVE
execute if score #result g5 matches 11 if score #settle g5 matches -1 run say [G5] OBS p00_capability_probe OWNER_GATED
scoreboard players set #gn g5 0
execute if score #result g5 matches 2..3 run scoreboard players set #gn g5 1
execute if score #result g5 matches 6 run scoreboard players set #gn g5 1
execute if score #result g5 matches 8 run scoreboard players set #gn g5 1
execute if score #result g5 matches 10 run scoreboard players set #gn g5 1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run say [G5] AWAIT p00_capability_probe owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {"text":"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.","color":"gold"}
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2
execute if score #gn g5 matches 0 if score #result g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 3
execute if score #gn g5 matches 0 if score #result g5 matches 4..5 if score #settle g5 matches -1 run scoreboard players set #settle g5 3
execute if score #gn g5 matches 0 if score #result g5 matches 7 if score #settle g5 matches -1 run scoreboard players set #settle g5 3
execute if score #gn g5 matches 0 if score #result g5 matches 11 if score #settle g5 matches -1 run scoreboard players set #settle g5 3
execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap
execute if score #settle g5 matches 1.. run scoreboard players remove #settle g5 1
execute if score #settle g5 matches 0 run function npcortex_g5:lib/snap
execute if score #settle g5 matches 0 if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1
execute if score #settle g5 matches 0 run say [G5] END p00_capability_probe
execute if score #settle g5 matches 0 run function npcortex_g5:lib/advance
