# p18 E3 (generated): only observes that the NPC moved and stopped; E3 terminal/ACK acceptance is NOT inferred
execute if score #tick g5 matches 0 run say [G5] BEGIN p18_regress_e3_taskmove
execute if score #tick g5 matches 0 run function npcortex_g5:lib/fresh_start
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 run function npcortex_g5:lib/obs_yui
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 unless score #present g5 matches 1 run scoreboard players set #result g5 4
execute if score #tick g5 matches 3 if score #adaptive g5 matches 1 if score #result g5 matches 0 run function npcortex_g5:lib/prime
execute if score #tick g5 matches 4 if score #result g5 matches 0 run function npcortex_g5:lib/snap
execute if score #tick g5 matches 4 if score #result g5 matches 0 run execute as @a[tag=g5_op,limit=1] run npcortex debug cplus m03 move yui
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #present g5 matches 1 run function npcortex_g5:lib/poll
execute if score #tick g5 matches 5.. if score #result g5 matches 0 run function npcortex_g5:lib/periodic_snap
execute if score #tick g5 matches 5.. if score #adaptive g5 matches 1 if score #result g5 matches 0 if score #moved g5 matches 1 if score #still g5 matches 30.. run scoreboard players set #result g5 10
execute if score #tick g5 matches 600.. if score #adaptive g5 matches 1 if score #result g5 matches 0 run scoreboard players set #result g5 2
execute if score #tick g5 matches 100.. if score #adaptive g5 matches 0 if score #result g5 matches 0 run scoreboard players set #result g5 10
execute if score #result g5 matches 2 if score #adaptive g5 matches 1 if score #moved g5 matches 0 run scoreboard players set #result g5 6
execute if score #result g5 matches 10 if score #settle g5 matches -1 if score #moved g5 matches 1 run say [G5] OBS p18_regress_e3_taskmove MOVED_AWAITING_TYPED_FINALIZE
execute if score #result g5 matches 10 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] [{"text":"[G5] p18: type NOW, as the operator and in this order: ","color":"gold"},{"text":"/npcortex debug cplus taskmovefinalize yui","color":"aqua"},{"text":"  then  ","color":"gold"},{"text":"/npcortex debug cplus taskmovestatus yui","color":"aqua"},{"text":"  - wait for each answer in chat (G3 COMPLETED ... durable ACK). Only then run scripts/g5-gate.ps1 and the gate command.","color":"gold"}]
execute if score #result g5 matches 1 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove REACHED
execute if score #result g5 matches 2 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove TIMEOUT
execute if score #result g5 matches 3 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove STALLED_TERMINAL_INFERRED
execute if score #result g5 matches 4 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove BLOCKED_NPC_NOT_SELECTABLE
execute if score #result g5 matches 5 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove BLOCKED_TARGET_CHUNK_NOT_LOADED
execute if score #result g5 matches 6 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove NEVER_MOVED
execute if score #result g5 matches 7 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove STOPPED_AFTER_CANCEL
execute if score #result g5 matches 8 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove CANCEL_NOT_TRIGGERED
execute if score #result g5 matches 10 if score #settle g5 matches -1 run say [G5] OBS p18_regress_e3_taskmove AWAITING_OWNER_GATE
scoreboard players set #gn g5 0
execute if score #result g5 matches 2..3 run scoreboard players set #gn g5 1
execute if score #result g5 matches 6 run scoreboard players set #gn g5 1
execute if score #result g5 matches 8 run scoreboard players set #gn g5 1
execute if score #result g5 matches 10 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 1 run scoreboard players set #gn g5 1
execute if score #strict g5 matches 1 if score #result g5 matches 7 run scoreboard players set #gn g5 1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run say [G5] AWAIT p18_regress_e3_taskmove owner gate required (no in-game terminal evidence); run scripts/g5-gate.ps1
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run tellraw @a[tag=g5_op] {"text":"[G5] AWAITING OWNER GATE: run scripts/g5-gate.ps1 -WorldName <world> -Watch and use /function npcortex_g5:gate only after it prints GO.","color":"gold"}
execute if score #gn g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 -2
execute if score #gn g5 matches 0 if score #result g5 matches 1 if score #settle g5 matches -1 run scoreboard players set #settle g5 20
execute if score #gn g5 matches 0 if score #result g5 matches 4..5 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 7 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #gn g5 matches 0 if score #result g5 matches 11 if score #settle g5 matches -1 run scoreboard players set #settle g5 5
execute if score #settle g5 matches -2 run function npcortex_g5:lib/await_snap
execute if score #settle g5 matches 1.. run scoreboard players remove #settle g5 1
execute if score #settle g5 matches 0 run function npcortex_g5:lib/snap
execute if score #settle g5 matches 0 if score #result g5 matches 4..5 run scoreboard players add #blocked g5 1
execute if score #settle g5 matches 0 run say [G5] END p18_regress_e3_taskmove
execute if score #settle g5 matches 0 run function npcortex_g5:lib/advance
execute if score #result g5 matches 4 run function npcortex_g5:lib/abort_obs
