# p17 unfollow (generated); keeps the NPC of p16
execute if score #tick g5 matches 0 run say [G5] BEGIN p17_regress_follow_stop
execute if score #tick g5 matches 0 run function npcortex_g5:lib/reset
execute if score #tick g5 matches 1 if score #adaptive g5 matches 1 run function npcortex_g5:lib/obs_yui
execute if score #tick g5 matches 1 if score #adaptive g5 matches 1 unless score #present g5 matches 1 run scoreboard players set #result g5 4
execute if score #tick g5 matches 1 if score #adaptive g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/prime
execute if score #tick g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/snap
execute if score #tick g5 matches 2 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 unfollow yui
execute if score #tick g5 matches 3 if score #result g5 matches 0 run tp @a[tag=g5_op] -60.5 -60 2.5
execute if score #tick g5 matches 4.. if score #adaptive g5 matches 1 if score #present g5 matches 1 run function npcortex_g5:lib/poll
execute if score #tick g5 matches 4.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap
execute if score #tick g5 matches 8.. if score #adaptive g5 matches 1 if score #result g5 matches 0 if score #still g5 matches 20.. run scoreboard players set #result g5 1
execute if score #tick g5 matches 200.. if score #adaptive g5 matches 1 if score #result g5 matches 0 run scoreboard players set #result g5 2
execute if score #tick g5 matches 40.. if score #adaptive g5 matches 0 if score #result g5 matches 0 run scoreboard players set #result g5 10
execute if score #result g5 matches 1 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop REACHED
execute if score #result g5 matches 2 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop TIMEOUT
execute if score #result g5 matches 3 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop STALLED_TERMINAL_INFERRED
execute if score #result g5 matches 4 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop BLOCKED_NPC_NOT_SELECTABLE
execute if score #result g5 matches 5 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop BLOCKED_TARGET_CHUNK_NOT_LOADED
execute if score #result g5 matches 6 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop NEVER_MOVED
execute if score #result g5 matches 7 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop STOPPED_AFTER_CANCEL
execute if score #result g5 matches 8 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop CANCEL_NOT_TRIGGERED
execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p17_regress_follow_stop AWAITING_OWNER_GATE
scoreboard players set #gn g5 0
execute if score #result g5 matches 2..3 run scoreboard players set #gn g5 1
execute if score #result g5 matches 6 run scoreboard players set #gn g5 1
execute if score #result g5 matches 8 run scoreboard players set #gn g5 1
execute if score #result g5 matches 10 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 1 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 7 run scoreboard players set #gn g5 1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run say [G5] AWAIT p17_regress_follow_stop owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {"text":"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.","color":"gold"}
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2
execute if score #gn g5 matches 0 if score #result g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 4..5 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 7 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 11 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap
execute if score #settle g5 matches 1.. run scoreboard players remove #settle g5 1
execute if score #settle g5 matches 0 run function npcortex_g5:lib/snap
execute if score #settle g5 matches 0 if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1
execute if score #settle g5 matches 0 run say [G5] END p17_regress_follow_stop
execute if score #settle g5 matches 0 run function npcortex_g5:lib/advance
execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs
