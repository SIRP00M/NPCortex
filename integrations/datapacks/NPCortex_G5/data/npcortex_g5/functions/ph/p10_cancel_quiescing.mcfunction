# p10_cancel_quiescing (generated; revision 6). Attempt counter #att (1..5), per-tick watch lib/p10_watch, cancel lib/p10_cancel.
execute if score #tick g5 matches 0 unless score #p10on g5 matches 1 run function npcortex_g5:lib/p10_init
execute if score #tick g5 matches 0 if score #att g5 matches 1 run say [G5] BEGIN p10_cancel_quiescing cancel at the first stand-still tick after moving while far from the target (up to 5 attempts)
execute if score #tick g5 matches 0 if score #att g5 matches 1 run say [G5] NOTE attempt 1
execute if score #tick g5 matches 0 if score #att g5 matches 2 run say [G5] NOTE attempt 2
execute if score #tick g5 matches 0 if score #att g5 matches 3 run say [G5] NOTE attempt 3
execute if score #tick g5 matches 0 if score #att g5 matches 4 run say [G5] NOTE attempt 4
execute if score #tick g5 matches 0 if score #att g5 matches 5 run say [G5] NOTE attempt 5
execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 run function npcortex_g5:lib/obs_yui
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 unless score #present g5 matches 1 run scoreboard players set #result g5 4
execute if score #tick g5 matches 3 if score #result g5 matches 0 unless block 152 -64 2 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/prime
execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap
execute if score #tick g5 matches 4 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong yui 152 -60 2
execute if score #tick g5 matches 4 if score #adaptive g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/p10_arm
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #present g5 matches 1 run function npcortex_g5:lib/poll
execute if score #tick g5 matches 5.. if score #result g5 matches 0 if score #cancelled g5 matches 0 run function npcortex_g5:lib/periodic_snap
execute if score #tick g5 matches 5.. if score #cancelled g5 matches 1 unless score #settle g5 matches -2 run function npcortex_g5:lib/periodic_snap
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #sel g5 matches 1 if score #result g5 matches 0 if score #cancelled g5 matches 0 if score #still g5 matches 3.. as @e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 152.5 -60 2.5 if entity @s[distance=..1.6] run scoreboard players set #result g5 1
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #sel g5 matches 2 if score #result g5 matches 0 if score #cancelled g5 matches 0 if score #still g5 matches 3.. as @e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300] positioned 152.5 -60 2.5 if entity @s[distance=..1.6] run scoreboard players set #result g5 1
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #result g5 matches 0 if score #cancelled g5 matches 1 if score #still g5 matches 8.. run scoreboard players set #result g5 7
execute if score #result g5 matches 7 if score #moving g5 matches 1 run say [G5] OBS p10_cancel_quiescing MOVED_AFTER_CANCEL
execute if score #tick g5 matches 150.. if score #adaptive g5 matches 1 if score #result g5 matches 0 run scoreboard players set #result g5 2
execute if score #result g5 matches 2 if score #adaptive g5 matches 1 if score #moved g5 matches 0 run scoreboard players set #result g5 6
execute if score #result g5 matches 2 run function npcortex_g5:lib/p10_timeout
execute if score #tick g5 matches 24.. if score #adaptive g5 matches 0 if score #result g5 matches 0 run scoreboard players set #result g5 10
execute if score #result g5 matches 1.. run scoreboard players set #w g5 0
execute if score #result g5 matches 1 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing COMPLETED_WITHOUT_CANCEL
execute if score #result g5 matches 4 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing BLOCKED_NPC_NOT_SELECTABLE
execute if score #result g5 matches 5 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing BLOCKED_TARGET_CHUNK_NOT_LOADED
execute if score #result g5 matches 6 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing NEVER_MOVED
execute if score #result g5 matches 7 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing STOPPED_AFTER_CANCEL
execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing AWAITING_OWNER_GATE
execute if score #result g5 matches 11 if score #settle g5 matches -1 run say [G5] OBS p10_cancel_quiescing ATTEMPT_TIMEOUT_CLEANED_UP
scoreboard players set #gn g5 0
execute if score #result g5 matches 2..3 run scoreboard players set #gn g5 1
execute if score #result g5 matches 6 run scoreboard players set #gn g5 1
execute if score #result g5 matches 8 run scoreboard players set #gn g5 1
execute if score #result g5 matches 10 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 1 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 7 run scoreboard players set #gn g5 1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run say [G5] AWAIT p10_cancel_quiescing owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {"text":"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.","color":"gold"}
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2
execute if score #gn g5 matches 0 if score #result g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 10
execute if score #gn g5 matches 0 if score #result g5 matches 4..5 if score #settle g5 matches -1 run scoreboard players set #settle g5 40
execute if score #gn g5 matches 0 if score #result g5 matches 7 if score #settle g5 matches -1 run scoreboard players set #settle g5 40
execute if score #gn g5 matches 0 if score #result g5 matches 11 if score #settle g5 matches -1 run scoreboard players set #settle g5 40
execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap
execute if score #settle g5 matches 1.. run scoreboard players remove #settle g5 1
execute if score #settle g5 matches 0 if score #att g5 matches ..4 if score #result g5 matches 1 run function npcortex_g5:lib/p10_next
execute if score #settle g5 matches 0 if score #att g5 matches ..4 if score #result g5 matches 7 run function npcortex_g5:lib/p10_next
execute if score #settle g5 matches 0 if score #att g5 matches ..4 if score #result g5 matches 11 run function npcortex_g5:lib/p10_next
execute if score #settle g5 matches 0 run function npcortex_g5:lib/snap
execute if score #settle g5 matches 0 if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1
execute if score #settle g5 matches 0 run say [G5] END p10_cancel_quiescing
execute if score #settle g5 matches 0 run scoreboard players set #w g5 0
execute if score #settle g5 matches 0 run scoreboard players set #p10on g5 0
execute if score #settle g5 matches 0 run scoreboard players set #att g5 0
execute if score #settle g5 matches 0 run function npcortex_g5:lib/advance
execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs
