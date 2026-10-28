# p11 checkpoint (generated). Adaptive: the checkpoint starts when the NPC is seen walking. Owner-gated: the gate (g5-gate.ps1 saw a mid-goal M03 snapshot) starts it.
execute if score #tick g5 matches 0 run say [G5] BEGIN p11_savequit_checkpoint
execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 run function npcortex_g5:lib/obs_yui
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 unless score #present g5 matches 1 run scoreboard players set #result g5 4
execute if score #tick g5 matches 3 if score #result g5 matches 0 unless block 152 -64 2 minecraft:bedrock run scoreboard players set #result g5 5
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/prime
execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap
execute if score #tick g5 matches 4 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex c2 navlong yui 152 -60 2
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #present g5 matches 1 run function npcortex_g5:lib/poll
execute if score #tick g5 matches 5.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #result g5 matches 0 if score #moved g5 matches 1 if score #moving g5 matches 1 run scoreboard players set #result g5 9
execute if score #tick g5 matches 24.. if score #adaptive g5 matches 0 if score #result g5 matches 0 run scoreboard players set #result g5 10
execute if score #tick g5 matches 700.. if score #adaptive g5 matches 1 if score #result g5 matches 0 run scoreboard players set #result g5 2
execute if score #result g5 matches 9 run function npcortex_g5:lib/checkpoint_savequit
execute if score #result g5 matches 2 if score #adaptive g5 matches 1 if score #moved g5 matches 0 run scoreboard players set #result g5 6
execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p11_savequit_checkpoint AWAITING_OWNER_GATE
execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] AWAIT p11_savequit_checkpoint owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1
execute if score #result g5 matches 10 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {"text":"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.","color":"gold"}
execute if score #result g5 matches 10 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2
execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap
execute if score #result g5 matches 2..8 run say [G5] OBS p11_savequit_checkpoint ABORTED_BEFORE_CHECKPOINT
execute if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1
execute if score #result g5 matches 2..8 run function npcortex_g5:lib/finished
